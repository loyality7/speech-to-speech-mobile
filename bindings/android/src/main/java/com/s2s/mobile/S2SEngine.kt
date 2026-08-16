package com.s2s.mobile

import android.content.Context
import android.util.Log
import com.s2s.mobile.audio.AudioFocusController
import com.s2s.mobile.audio.MicrophoneInput
import com.s2s.mobile.audio.SpeakerOutput
import com.s2s.mobile.audio.VoiceSessionService
import com.s2s.mobile.config.S2SConfig
import com.s2s.mobile.internal.TurnGuard
import com.s2s.mobile.llm.ChatHistory
import com.s2s.mobile.llm.LlamaLanguageModel
import com.s2s.mobile.pipeline.AudioInput
import com.s2s.mobile.pipeline.AudioOutput
import com.s2s.mobile.pipeline.LanguageModel
import com.s2s.mobile.pipeline.SpeechRecognizer
import com.s2s.mobile.pipeline.SpeechSynthesizer
import com.s2s.mobile.pipeline.TextChunker
import com.s2s.mobile.pipeline.TokenSink
import com.s2s.mobile.pipeline.ToolDefinition
import com.s2s.mobile.pipeline.ToolFunction
import com.s2s.mobile.pipeline.Tools
import com.s2s.mobile.pipeline.Transcript
import com.s2s.mobile.pipeline.VoiceActivityDetector
import com.s2s.mobile.stt.OfflineVadRecognizer
import com.s2s.mobile.stt.SherpaStreamingRecognizer
import com.s2s.mobile.text.SentenceChunker
import com.s2s.mobile.tools.ToolRegistry
import com.s2s.mobile.tts.SherpaSynthesizer
import com.s2s.mobile.vad.SileroVad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Picks the recogniser implementation the configured backend requires.
 *
 * Streaming and offline models need fundamentally different drivers — one is fed
 * frame by frame and reports its own endpoints, the other needs speech segmented
 * for it — so the backend choice selects the driver rather than a flag inside one.
 */
private fun defaultRecognizer(config: S2SConfig): SpeechRecognizer =
    if (config.stt.backend.streaming) {
        SherpaStreamingRecognizer(config.stt, config.audio, config.models.sttDir)
    } else {
        OfflineVadRecognizer(
            sttConfig = config.stt,
            vadConfig = config.vad,
            audioConfig = config.audio,
            modelDir = config.models.sttDir,
            vadModelPath = config.models.vadModel,
        )
    }

/**
 * Fully on-device speech-to-speech engine.
 *
 * ```
 * mic ─▶ Silero VAD ─▶ streaming ASR ─▶ llama.cpp ─▶ chunker ─▶ neural TTS ─▶ speaker
 * ```
 *
 * Nothing leaves the device once the models are on disk. Every stage is an
 * interface, so any of them can be swapped by passing a different implementation
 * to the constructor.
 *
 * ```kotlin
 * val engine = S2SEngine(S2SConfig(models = ModelPaths(...)))
 * engine.initialize().getOrThrow()   // slow: call off the main thread
 * engine.start()
 * lifecycleScope.launch { engine.events.collect { render(it) } }
 * ```
 *
 * `RECORD_AUDIO` must be granted before [start].
 */
class S2SEngine @JvmOverloads constructor(
    private val context: Context,
    private val config: S2SConfig,
    private val vad: VoiceActivityDetector = defaultVad(config),
    private val recognizer: SpeechRecognizer = defaultRecognizer(config),
    private val languageModel: LanguageModel = LlamaLanguageModel(config.llm, config.models.llmModel),
    private val synthesizer: SpeechSynthesizer = SherpaSynthesizer(config.tts, config.models.ttsDir),
    private val microphone: AudioInput = MicrophoneInput(config.audio),
    private val chunker: TextChunker =
        SentenceChunker(config.tts.firstChunkMinChars, config.tts.maxChunkChars, config.tts.minChunkChars),
    /** Register device capabilities here before [initialize] to enable tool calling. */
    val tools: Tools = ToolRegistry(),
) {

    private val history = ChatHistory(
        systemPrompt = config.llm.systemPrompt,
        keepTurns = config.llm.historyTurns,
        compact = config.llm.compactHistory,
    )

    private val turns = TurnGuard()
    private var speaker: AudioOutput? = null

    // Generation and synthesis run on separate threads so sentence two is being
    // written while sentence one is still being spoken.
    private val llmWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "S2S-Llm") }
    private val ttsWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "S2S-Tts") }

    private val _state = MutableStateFlow(S2SState.IDLE)
    val state: StateFlow<S2SState> = _state.asStateFlow()

    // 256 rather than 64 because one event per token overruns a slow collector
    // quickly. Deliberately NOT DROP_OLDEST: that makes tryEmit always succeed,
    // so overflow becomes undetectable and tokens vanish silently — which is the
    // whole of issue #17. With the default strategy tryEmit reports the failure
    // and emit() below can say so.
    private val _events = MutableSharedFlow<S2SEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<S2SEvent> = _events.asSharedFlow()

    @Volatile private var initialized = false
    @Volatile private var running = false
    @Volatile private var synthesisDone = true
    @Volatile private var turnEndedAt = 0L
    @Volatile private var firstTokenMs = 0L
    @Volatile private var sawFirstAudio = false

    /** When the assistant last started talking, for the barge-in grace window. */
    @Volatile private var speakingSince = 0L

    /** Set by any thread; applied by the audio thread, which owns those objects. */
    @Volatile private var resetRecognitionPending = false

    /** Held only while listening; abandoned in [stop]. */
    private var focus: AudioFocusController? = null

    /** Capture is suspended for a call or alarm, with the models still loaded. */
    @Volatile private var pausedForFocus = false

    /** What the user has said so far in the turn currently being answered. */
    @Volatile private var pendingUserText: String? = null

    /** What the assistant has generated so far in the turn being answered. */
    @Volatile private var partialReply: String = ""

    /** Voices the loaded TTS bundle exposes. Empty until [initialize] succeeds. */
    val voices get() = synthesizer.voices

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Loads every model. Takes seconds, and always runs on [Dispatchers.IO].
     *
     * Suspending rather than blocking is deliberate: this reads ~800 MB from
     * disk, and when it was an ordinary function every caller had to remember to
     * wrap it themselves or silently ANR. The compiler now enforces what the
     * documentation used to only ask for.
     *
     * Idempotent: calling it again on a running engine would load a second copy of
     * every model and drop the first set unreleased, which on a ~490 MB GGUF
     * exhausts a mid-range device within a few restarts.
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (initialized) return@runCatching Unit
            loadStages()
            Unit
        }.onFailure {
            Log.e(TAG, "initialize failed", it)
            // A partial load still holds native memory; free whatever came up
            // before the failure so a retry does not stack a second set of
            // models on top.
            releaseStages()
            emit(S2SEvent.Error("Initialisation failed: ${it.message}", it))
        }
    }

    private fun loadStages() {
        // The detector is trained on a fixed window — Silero 512 samples, TEN 256 —
        // and a mismatch does not throw: the native side simply scores the wrong
        // shape and barge-in behaves oddly. ModelConfigFactory derives the capture
        // size from the backend so this should hold by construction; this catches
        // a hand-built config that got it wrong.
        check(microphone.frameSize == vad.frameSize) {
            "Capture frame size ${microphone.frameSize} does not match the " +
                "${config.vad.backend} VAD window of ${vad.frameSize} samples. " +
                "Set AudioConfig.frameSize to ${vad.frameSize}, or build the config " +
                "with ModelConfigFactory, which derives it from the backend."
        }

        vad.initialize().getOrThrow()
        recognizer.initialize().getOrThrow()
        synthesizer.initialize().getOrThrow()
        languageModel.initialize().getOrThrow()

        speaker = SpeakerOutput(context, config.audio.playbackSampleRate ?: synthesizer.sampleRate).apply {
            onDrained = { onPlaybackDrained() }
        }
        initialized = true
    }

    /**
     * Opens the microphone and starts listening.
     *
     * Also claims audio focus and, unless the host opted out, starts a
     * microphone-typed foreground service — without one Android stops delivering
     * audio as soon as the app is backgrounded, and does it silently.
     */
    fun start(): Boolean {
        check(initialized) { "initialize() must succeed before start()" }
        if (running) return true

        if (config.audio.manageAudioFocus && !acquireFocus()) {
            emit(S2SEvent.Error("Audio focus denied — something else owns the microphone"))
            return false
        }

        if (config.audio.manageForegroundService) {
            val started = VoiceSessionService.start(
                context,
                config.audio.serviceNotificationTitle,
                config.audio.serviceNotificationText,
            )
            // Not fatal: capture works while the app is in front. Say so rather
            // than let the mic die later with no explanation.
            if (!started) {
                emit(
                    S2SEvent.Error(
                        "Foreground service refused — listening will stop when the app is backgrounded",
                    ),
                )
            }
        }

        speaker?.start()
        recognizer.reset()
        vad.reset()
        chunker.reset()
        synthesisDone = true

        if (!microphone.start(::onFrame)) {
            emit(S2SEvent.Error("Microphone unavailable — is RECORD_AUDIO granted?"))
            speaker?.release()
            releaseSessionResources()
            return false
        }
        running = true
        setState(S2SState.LISTENING)
        return true
    }

    /** Closes the microphone and cancels anything in flight. */
    fun stop() {
        if (!running) return
        running = false
        turns.begin()
        languageModel.cancel()
        microphone.stop()
        speaker?.flush()
        speaker?.release()
        releaseSessionResources()
        setState(S2SState.IDLE)
    }

    /**
     * Responds to OS memory pressure signals (e.g. ComponentCallbacks2.onTrimMemory).
     *
     * Trims non-essential KV cache buffers when memory is low to prevent process termination.
     */
    fun onTrimMemory(level: Int) {
        Log.i(TAG, "onTrimMemory level=$level received")
        languageModel.trimMemory()
    }

    /**
     * Claims focus, wiring the loss callbacks to pause or stop.
     *
     * A transient loss — a call, an alarm — cuts the current turn and closes the
     * microphone but keeps the models loaded, so resuming costs nothing. A
     * permanent loss stops the engine outright.
     */
    private fun acquireFocus(): Boolean {
        val controller = AudioFocusController(
            context = context,
            onLoss = {
                emit(S2SEvent.AudioFocusLost(willResume = false))
                stop()
            },
            onTransientLoss = {
                emit(S2SEvent.AudioFocusLost(willResume = true))
                pauseForFocus()
            },
            onRegained = {
                emit(S2SEvent.AudioFocusRegained)
                resumeAfterFocus()
            },
        )
        focus = controller
        if (controller.request()) return true
        focus = null
        return false
    }

    /**
     * Stops capture and playback but keeps every model loaded.
     *
     * Reloading ~800 MB because someone's phone rang would be absurd, so this
     * deliberately does far less than [stop].
     */
    private fun pauseForFocus() {
        if (!running || pausedForFocus) return
        pausedForFocus = true
        turns.begin()
        languageModel.cancel()
        microphone.stop()
        speaker?.flush()
        // Release the track so the other app gets a clean audio path; start()
        // rebuilds it, and routing is restored with it.
        speaker?.release()
        if (config.audio.manageForegroundService) {
            VoiceSessionService.update(
                context,
                config.audio.serviceNotificationPausedTitle,
                config.audio.serviceNotificationPausedText,
            )
        }
        setState(S2SState.IDLE)
    }

    private fun resumeAfterFocus() {
        if (!running || !pausedForFocus) return
        pausedForFocus = false
        speaker?.start()
        resetRecognitionPending = true
        chunker.reset()
        synthesisDone = true
        if (!microphone.start(::onFrame)) {
            emit(S2SEvent.Error("Microphone did not come back after the interruption"))
            stop()
            return
        }
        if (config.audio.manageForegroundService) {
            VoiceSessionService.update(
                context,
                config.audio.serviceNotificationTitle,
                config.audio.serviceNotificationText,
            )
        }
        setState(S2SState.LISTENING)
    }

    private fun releaseSessionResources() {
        pausedForFocus = false
        focus?.abandon()
        focus = null
        if (config.audio.manageForegroundService) VoiceSessionService.stop(context)
    }

    /** Frees every model. The engine cannot be reused afterwards. */
    fun release() {
        stop()
        llmWorker.shutdownNow()
        ttsWorker.shutdownNow()
        releaseStages()
    }

    /**
     * Frees native handles. Safe to call on a partially initialised engine, and
     * only after [stop] has joined the audio thread — these objects are not
     * thread-safe and that thread reads them every frame.
     */
    private fun releaseStages() {
        runCatching { recognizer.release() }.onFailure { Log.w(TAG, "recognizer release", it) }
        runCatching { vad.release() }.onFailure { Log.w(TAG, "vad release", it) }
        runCatching { synthesizer.release() }.onFailure { Log.w(TAG, "synthesizer release", it) }
        runCatching { languageModel.release() }.onFailure { Log.w(TAG, "llm release", it) }
        speaker = null
        initialized = false
    }

    // ── Control ─────────────────────────────────────────────────────────

    /**
     * Cancels the current reply and returns to listening.
     *
     * One counter bump invalidates generation, synthesis and playback together,
     * so this is safe to call from any thread at any point in a turn.
     */
    fun interrupt() {
        turns.begin()
        pendingUserText = null
        languageModel.cancel()

        // Record what was actually said before the cut. The user heard it, so the
        // model should know it said it — and it keeps any KV cache consistent with
        // the prompt, which is what lets the next turn reuse the cache instead of
        // rebuilding the whole conversation. If interrupted before any tokens were
        // generated, drop the unanswered user turn so history does not contain
        // consecutive user turns.
        val replyText = partialReply.trim()
        if (replyText.isNotEmpty()) {
            history.addAssistant(replyText)
            partialReply = ""
        } else {
            history.dropLastUserIfUnanswered()
        }
        speaker?.flush()
        chunker.reset()
        synthesisDone = true
        // The recogniser and VAD are owned by the audio thread — sherpa's stream
        // objects are not thread-safe, and resetting one here would free it while
        // that thread is decoding into it. Ask, do not touch.
        resetRecognitionPending = true
        if (running) setState(S2SState.LISTENING)
    }

    /** Injects a typed message as if the user had spoken it. */
    fun sendText(text: String) {
        if (text.isBlank()) return
        emit(S2SEvent.UserTranscript(text.trim(), isFinal = true))
        beginTurn(text.trim())
    }

    /** Registers a device capability the assistant can invoke. */
    fun registerTool(definition: ToolDefinition, function: ToolFunction) =
        tools.register(definition, function)

    fun setSystemPrompt(prompt: String) = history.setSystemPrompt(prompt)

    /** Switches TTS voice for subsequent replies. */
    fun selectVoice(voiceId: Int) = synthesizer.selectVoice(voiceId)

    /** Clears conversation memory. Models stay loaded. */
    fun resetConversation() {
        interrupt()
        history.clear()
        languageModel.resetContext()
    }

    /** Serializes active conversation memory to JSON for process death persistence. */
    fun saveConversationState(): String = history.toJson()

    /** Restores conversation memory from serialized JSON. */
    fun restoreConversationState(json: String) {
        interrupt()
        history.fromJson(json)
        languageModel.resetContext()
    }

    // ── Audio in ────────────────────────────────────────────────────────

    private fun onFrame(frame: FloatArray) {
        if (!running) return

        // Applied here because this thread owns the recogniser and VAD.
        if (resetRecognitionPending) {
            resetRecognitionPending = false
            recognizer.reset()
            vad.reset()
        }

        when (_state.value) {
            S2SState.LISTENING -> when (val heard = recognizer.accept(frame)) {
                is Transcript.Partial -> emit(S2SEvent.UserTranscript(heard.text, isFinal = false))
                is Transcript.Final -> {
                    val text = normalizeForModel(heard.text)
                    emit(S2SEvent.UserTranscript(text, isFinal = true))
                    beginTurn(text)
                }
                Transcript.Nothing -> Unit
            }

            // Nothing is playing yet, so there is nothing to barge into. Keep
            // listening: a user who pauses mid-thought and carries on is
            // continuing the same question, not starting a new one. Treating
            // that as an interruption threw the first half away and answered
            // only the fragment after the pause.
            S2SState.THINKING -> when (val heard = recognizer.accept(frame)) {
                is Transcript.Final -> continueTurn(normalizeForModel(heard.text))
                is Transcript.Partial -> emit(S2SEvent.UserTranscript(heard.text, isFinal = false))
                Transcript.Nothing -> Unit
            }

            // Audio is playing: the recogniser stays idle so leaked assistant
            // audio can never be transcribed back as if the user had said it.
            S2SState.SPEAKING -> {
                val speech = config.vad.bargeInEnabled && vad.accept(frame)
                if (speech && System.currentTimeMillis() - speakingSince >= config.vad.bargeInGraceMs) {
                    emit(S2SEvent.BargeIn)
                    interrupt()
                }
            }

            S2SState.IDLE -> Unit
        }
    }

    /**
     * Converts ALL-CAPS recogniser output to ordinary sentence case.
     *
     * English ASR bundles emit uppercase with no punctuation. Chat models are
     * trained almost entirely on normal casing and treat a shouted, unpunctuated
     * line as noise — in practice they fall back to a generic greeting instead of
     * answering. Lowercasing costs nothing and visibly improves the replies.
     */
    private fun normalizeForModel(text: String): String {
        if (text.any { it.isLowerCase() }) return text
        return text.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun onPlaybackDrained() {
        // Audio ran out, but the turn is only over once synthesis stopped too.
        //
        // Runs on the playback thread, so the recogniser and VAD are asked to
        // reset rather than reset here — they belong to the audio thread and
        // touching them from here throws inside sherpa mid-frame.
        if (running && synthesisDone && _state.value == S2SState.SPEAKING) {
            resetRecognitionPending = true
            setState(S2SState.LISTENING)
        }
    }

    // ── Turn ────────────────────────────────────────────────────────────

    private fun beginTurn(userText: String) {
        pendingUserText = userText
        history.addUser(userText)
        startGeneration()
    }

    /**
     * The user paused, we started answering, and now they have carried on.
     *
     * Their earlier words are kept and the turn is restarted with everything
     * they have said. Discarding the first half — which is what treating this as
     * an interruption did — answered only the fragment after the pause.
     */
    private fun continueTurn(moreText: String) {
        val merged = listOfNotNull(pendingUserText, moreText)
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (merged.isEmpty()) return

        pendingUserText = merged
        emit(S2SEvent.UserTranscript(merged, isFinal = true))
        // Replace rather than append, or the model sees the half-question twice.
        history.replaceLastUser(merged)
        startGeneration()
    }

    private fun startGeneration() {
        partialReply = ""
        val turn = turns.begin()
        languageModel.cancel()
        speaker?.flush()
        chunker.reset()
        synthesisDone = false
        sawFirstAudio = false
        turnEndedAt = System.currentTimeMillis()
        firstTokenMs = 0
        setState(S2SState.THINKING)

        llmWorker.execute { generate(turn) }
    }

    private fun generate(turn: Int, depth: Int = 0) {
        if (turns.isStale(turn)) return

        val toolPrompt = if (config.llm.toolsEnabled) tools.promptSection() else null
        val reply = StringBuilder()

        try {
            languageModel.generate(
                history.messages(extraSystem = toolPrompt),
                object : TokenSink {
                    override fun onToken(text: String) {
                        if (turns.isStale(turn)) return
                        if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis() - turnEndedAt
                        reply.append(text)
                        partialReply = reply.toString()
                        emit(S2SEvent.AssistantDelta(text))
                        // A tool call must not be spoken aloud, and it only becomes
                        // recognisable once the object closes — so hold synthesis
                        // until completion when tools are on.
                        if (!config.llm.toolsEnabled) {
                            chunker.accept(text).forEach { speak(turn, it) }
                        }
                    }

                    override fun onComplete() {
                        if (turns.isStale(turn)) return
                        val full = reply.toString()

                        if (config.llm.toolsEnabled) {
                            val call = tools.parse(full)
                            if (call != null) {
                                runTool(turn, call.name, full, depth)
                                return
                            }
                            // Not a tool call after all: speak it now.
                            chunker.accept(full).forEach { speak(turn, it) }
                        }
                        chunker.flush()?.let { speak(turn, it) }
                        if (full.isNotBlank()) {
                            history.addAssistant(full)
                            emit(S2SEvent.AssistantDone(full))
                        }
                        markSynthesisDone(turn)
                        if (full.isBlank() && running && _state.value == S2SState.THINKING) {
                            setState(S2SState.LISTENING)
                        }
                    }

                    override fun onError(message: String, cause: Throwable?) {
                        if (turns.isStale(turn)) return
                        synthesisDone = true
                        emit(S2SEvent.Error("LLM error: $message", cause))
                        if (running) setState(S2SState.LISTENING)
                    }
                },
            )
        } catch (e: Throwable) {
            if (!turns.isStale(turn)) {
                Log.e(TAG, "Turn $turn generation failed with exception", e)
                synthesisDone = true
                emit(S2SEvent.Error("LLM error: ${e.message}", e))
                if (running) setState(S2SState.LISTENING)
            }
        }
    }

    private fun runTool(turn: Int, name: String, raw: String, depth: Int = 0) {
        if (depth >= MAX_TOOL_RECURSION_DEPTH) {
            Log.w(TAG, "Tool recursion depth $depth reached max limit $MAX_TOOL_RECURSION_DEPTH for tool $name")
            emit(S2SEvent.Error("Tool recursion depth exceeded maximum limit ($MAX_TOOL_RECURSION_DEPTH)"))
            if (running) setState(S2SState.LISTENING)
            return
        }

        val call = tools.parse(raw) ?: return
        val result = tools.execute(call)
        emit(S2SEvent.ToolExecuted(name, result.output, result.isError))
        if (turns.isStale(turn)) return

        // The tool call itself was generated, so it is already in the KV cache.
        // Recording it keeps the history and the cache describing the same
        // conversation — without it the cache anchor points at text that appears
        // nowhere in the prompt, and every turn after a tool call pays a full
        // prefill.
        history.addAssistant(raw)

        // Feed the result back so the model can say what it did, rather than the
        // user hearing silence after a successful action.
        history.addToolResult(name, result.output)
        chunker.reset()
        llmWorker.execute { generate(turn, depth + 1) }
    }

    private fun speak(turn: Int, sentence: String) {
        ttsWorker.execute {
            if (turns.isStale(turn)) return@execute
            synthesizer.synthesize(
                text = sentence,
                keepGoing = { turns.isCurrent(turn) && running },
            ) { chunk ->
                if (turns.isStale(turn)) return@synthesize
                if (!sawFirstAudio) {
                    sawFirstAudio = true
                    speakingSince = System.currentTimeMillis()
                    val metrics = TurnMetrics(
                        timeToFirstTokenMs = firstTokenMs,
                        timeToFirstAudioMs = System.currentTimeMillis() - turnEndedAt,
                    )
                    Log.i(
                        TAG,
                        "turn latency: first token ${metrics.timeToFirstTokenMs}ms, " +
                            "first audio ${metrics.timeToFirstAudioMs}ms",
                    )
                    emit(S2SEvent.Metrics(metrics))
                    setState(S2SState.SPEAKING)
                }
                speaker?.write(chunk)
            }
        }
    }

    /**
     * Runs after every queued sentence, because the TTS executor is serial.
     *
     * Also rescues the case where a reply produced no audio at all — an empty or
     * fully-cancelled synthesis would otherwise strand the engine in THINKING.
     */
    private fun markSynthesisDone(turn: Int) {
        ttsWorker.execute {
            if (turns.isStale(turn)) return@execute
            synthesisDone = true
            if (running && speaker?.hasPending() != true) {
                // On the TTS worker here; the audio thread owns those objects.
                resetRecognitionPending = true
                setState(S2SState.LISTENING)
            }
        }
    }

    private fun setState(next: S2SState) {
        if (_state.value == next) return
        _state.value = next
        emit(S2SEvent.StateChanged(next))
    }

    private fun emit(event: S2SEvent) {
        if (!_events.tryEmit(event)) Log.w(TAG, "event dropped, collector too slow: $event")
    }

    private companion object {
        const val TAG = "S2SEngine"
        const val MAX_TOOL_RECURSION_DEPTH = 3
    }
}

private fun defaultVad(config: S2SConfig): VoiceActivityDetector =
    when (config.vad.backend) {
        com.s2s.mobile.config.VadBackend.TEN -> com.s2s.mobile.vad.TenVad(config.vad, config.audio, config.models.vadModel)
        com.s2s.mobile.config.VadBackend.SILERO -> com.s2s.mobile.vad.SileroVad(config.vad, config.audio, config.models.vadModel)
    }

