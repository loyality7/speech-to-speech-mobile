package com.s2s.mobile

import android.content.Context
import android.util.Log
import com.s2s.mobile.audio.MicrophoneInput
import com.s2s.mobile.audio.SpeakerOutput
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
import com.s2s.mobile.stt.SherpaStreamingRecognizer
import com.s2s.mobile.text.SentenceChunker
import com.s2s.mobile.tools.ToolRegistry
import com.s2s.mobile.tts.SherpaSynthesizer
import com.s2s.mobile.vad.SileroVad
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

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
class S2SEngine(
    private val context: Context,
    private val config: S2SConfig,
    private val vad: VoiceActivityDetector = SileroVad(config.vad, config.audio, config.models.vadModel),
    private val recognizer: SpeechRecognizer =
        SherpaStreamingRecognizer(config.stt, config.audio, config.models.sttDir),
    private val languageModel: LanguageModel = LlamaLanguageModel(config.llm, config.models.llmModel),
    private val synthesizer: SpeechSynthesizer = SherpaSynthesizer(config.tts, config.models.ttsDir),
    private val microphone: AudioInput = MicrophoneInput(config.audio),
    private val chunker: TextChunker =
        SentenceChunker(config.tts.firstChunkMinChars, config.tts.maxChunkChars),
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

    private val _events = MutableSharedFlow<S2SEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<S2SEvent> = _events.asSharedFlow()

    @Volatile private var initialized = false
    @Volatile private var running = false
    @Volatile private var synthesisDone = true
    @Volatile private var turnEndedAt = 0L
    @Volatile private var firstTokenMs = 0L
    @Volatile private var sawFirstAudio = false

    /** When the assistant last started talking, for the barge-in grace window. */
    @Volatile private var speakingSince = 0L

    /** Voices the loaded TTS bundle exposes. Empty until [initialize] succeeds. */
    val voices get() = synthesizer.voices

    /** True when the platform echo canceller is active — required for barge-in. */
    val echoCancellationActive: Boolean get() = microphone.echoCancellationActive

    // ── Lifecycle ───────────────────────────────────────────────────────

    /** Loads every model. Takes seconds — never call this on the main thread. */
    fun initialize(): Result<Unit> = runCatching {
        vad.initialize().getOrThrow()
        recognizer.initialize().getOrThrow()
        synthesizer.initialize().getOrThrow()
        languageModel.initialize().getOrThrow()

        speaker = SpeakerOutput(context, config.audio.playbackSampleRate ?: synthesizer.sampleRate).apply {
            onDrained = { onPlaybackDrained() }
        }
        initialized = true
        Unit
    }.onFailure {
        Log.e(TAG, "initialize failed", it)
        emit(S2SEvent.Error("Initialisation failed: ${it.message}", it))
    }

    /** Opens the microphone and starts listening. */
    fun start(): Boolean {
        check(initialized) { "initialize() must succeed before start()" }
        if (running) return true

        speaker?.start()
        recognizer.reset()
        vad.reset()
        chunker.reset()
        synthesisDone = true

        if (!microphone.start(::onFrame)) {
            emit(S2SEvent.Error("Microphone unavailable — is RECORD_AUDIO granted?"))
            speaker?.release()
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
        setState(S2SState.IDLE)
    }

    /** Frees every model. The engine cannot be reused afterwards. */
    fun release() {
        stop()
        llmWorker.shutdownNow()
        ttsWorker.shutdownNow()
        recognizer.release()
        vad.release()
        synthesizer.release()
        languageModel.release()
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
        languageModel.cancel()
        speaker?.flush()
        chunker.reset()
        synthesisDone = true
        recognizer.reset()
        vad.reset()
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
    }

    // ── Audio in ────────────────────────────────────────────────────────

    private fun onFrame(frame: FloatArray) {
        if (!running) return
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

            // Assistant is thinking or talking: watch only for an interruption.
            // The recogniser stays idle, so leaked assistant audio can never be
            // transcribed back as if the user had said it.
            S2SState.THINKING, S2SState.SPEAKING -> {
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
        if (running && synthesisDone && _state.value == S2SState.SPEAKING) {
            recognizer.reset()
            vad.reset()
            setState(S2SState.LISTENING)
        }
    }

    // ── Turn ────────────────────────────────────────────────────────────

    private fun beginTurn(userText: String) {
        val turn = turns.begin()
        languageModel.cancel()
        speaker?.flush()
        chunker.reset()
        synthesisDone = false
        sawFirstAudio = false
        turnEndedAt = System.currentTimeMillis()
        firstTokenMs = 0
        setState(S2SState.THINKING)

        history.addUser(userText)
        llmWorker.execute { generate(turn) }
    }

    private fun generate(turn: Int) {
        if (turns.isStale(turn)) return

        val toolPrompt = if (config.llm.toolsEnabled) tools.promptSection() else null
        val reply = StringBuilder()

        languageModel.generate(
            history.messages(extraSystem = toolPrompt),
            object : TokenSink {
                override fun onToken(text: String) {
                    if (turns.isStale(turn)) return
                    if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis() - turnEndedAt
                    reply.append(text)
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
                            runTool(turn, call.name, full)
                            return
                        }
                        // Not a tool call after all: speak it now.
                        chunker.accept(full).forEach { speak(turn, it) }
                    }
                    chunker.flush()?.let { speak(turn, it) }
                    history.addAssistant(full)
                    emit(S2SEvent.AssistantDone(full))
                    markSynthesisDone(turn)
                }

                override fun onError(message: String, cause: Throwable?) {
                    if (turns.isStale(turn)) return
                    synthesisDone = true
                    emit(S2SEvent.Error("LLM error: $message", cause))
                    if (running) setState(S2SState.LISTENING)
                }
            },
        )
    }

    private fun runTool(turn: Int, name: String, raw: String) {
        val call = tools.parse(raw) ?: return
        val result = tools.execute(call)
        emit(S2SEvent.ToolExecuted(name, result.output, result.isError))
        if (turns.isStale(turn)) return

        // Feed the result back so the model can say what it did, rather than the
        // user hearing silence after a successful action.
        history.addToolResult(name, result.output)
        chunker.reset()
        llmWorker.execute { generate(turn) }
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
                recognizer.reset()
                vad.reset()
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
    }
}
