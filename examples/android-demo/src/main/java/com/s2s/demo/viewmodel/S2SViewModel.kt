package com.s2s.demo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import com.llamatik.library.platform.WhisperBridge
import android.util.Log
import com.s2s.demo.audio.AudioPlaybackManager
import com.s2s.demo.downloader.DownloadState
import com.s2s.demo.downloader.ModelDownloadManager
import com.s2s.demo.downloader.ModelType
import com.s2s.demo.ui.components.ChatMessage
import com.s2s.demo.ui.components.LatencyMetrics
import com.s2s.demo.ui.components.ModelItemUiState
import com.s2s.demo.ui.components.VoiceState
import com.s2s.mobile.S2SEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class S2SUiState(
    val voiceState: VoiceState = VoiceState.IDLE,
    val messages: List<ChatMessage> = emptyList(),
    val liveTranscript: String = "",
    val audioEnergy: Float = 0f,
    val metrics: LatencyMetrics = LatencyMetrics(),
    val modelStates: List<ModelItemUiState> = emptyList(),
    val showModelSheet: Boolean = false,
    val isLlmReady: Boolean = false,
    val isSttReady: Boolean = false,
    val statusText: String = "Download models to begin",
    val selectedLlmModel: ModelType = ModelType.QWEN_0_5B
)

class S2SViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(S2SUiState())
    val uiState: StateFlow<S2SUiState> = _uiState.asStateFlow()

    private val downloadManager = ModelDownloadManager(application)
    private val s2sEngine = S2SEngine()
    private val audioPlayback = AudioPlaybackManager()
    private val ggmlGlobalLock = Any()

    private var androidTts: android.speech.tts.TextToSpeech? = null
    private var sessionActive = false

    init {
        try {
            androidTts = android.speech.tts.TextToSpeech(application) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    androidTts?.language = java.util.Locale.US
                    androidTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            s2sEngine.isVADActive = false
                        }
                        override fun onDone(utteranceId: String?) {
                            s2sEngine.isVADActive = true
                        }
                        override fun onError(utteranceId: String?) {
                            s2sEngine.isVADActive = true
                        }
                    })
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
    private var bargeInCount = 0
    private var messageIdCounter = 0

    init {
        refreshModelStates()
        autoFetchSystemModels()
    }

    private fun autoFetchSystemModels() {
        viewModelScope.launch(Dispatchers.IO) {
            listOf(ModelType.SILERO_VAD, ModelType.WHISPER_TINY).forEach { sysModel ->
                if (!downloadManager.isModelDownloaded(sysModel)) {
                    downloadManager.downloadModel(sysModel).collect { state ->
                        if (state is DownloadState.Completed) {
                            checkReadiness()
                        }
                    }
                }
            }
        }
    }

    // ── Model Management ────────────────────────────────────────────────

    fun refreshModelStates() {
        val states = ModelType.entries
            .filter { it.isUserSelectable }
            .map { model ->
                ModelItemUiState(
                    modelType = model,
                    isDownloaded = downloadManager.isModelDownloaded(model)
                )
            }
        _uiState.update { it.copy(modelStates = states) }
        checkReadiness()
    }

    fun showModelSheet() {
        refreshModelStates()
        _uiState.update { it.copy(showModelSheet = true) }
    }

    fun hideModelSheet() {
        _uiState.update { it.copy(showModelSheet = false) }
    }

    fun downloadModel(model: ModelType) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    modelStates = state.modelStates.map {
                        if (it.modelType == model) it.copy(isDownloading = true, downloadProgress = 0)
                        else it
                    }
                )
            }

            downloadManager.downloadModel(model).collect { downloadState ->
                when (downloadState) {
                    is DownloadState.Progress -> {
                        _uiState.update { state ->
                            state.copy(
                                modelStates = state.modelStates.map {
                                    if (it.modelType == model) it.copy(downloadProgress = downloadState.percent)
                                    else it
                                }
                            )
                        }
                    }
                    is DownloadState.Completed -> {
                        _uiState.update { state ->
                            state.copy(
                                modelStates = state.modelStates.map {
                                    if (it.modelType == model) it.copy(
                                        isDownloaded = true,
                                        isDownloading = false,
                                        downloadProgress = 100
                                    )
                                    else it
                                }
                            )
                        }
                        checkReadiness()
                    }
                    is DownloadState.Error -> {
                        _uiState.update { state ->
                            state.copy(
                                statusText = "Download failed: ${downloadState.message}",
                                modelStates = state.modelStates.map {
                                    if (it.modelType == model) it.copy(isDownloading = false, downloadProgress = 0)
                                    else it
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkReadiness() {
        val selected = _uiState.value.selectedLlmModel
        val llmReady = downloadManager.isModelDownloaded(selected)
        val sttReady = downloadManager.isModelDownloaded(ModelType.WHISPER_TINY)

        val status = when {
            !llmReady -> "Select & download an LLM model to begin"
            !sttReady -> "Setting up default speech recognition..."
            else -> "Ready — Tap the orb to start"
        }

        _uiState.update {
            it.copy(isLlmReady = llmReady, isSttReady = sttReady, statusText = status)
        }
    }

    fun selectLlmModel(model: ModelType) {
        _uiState.update { it.copy(selectedLlmModel = model) }
        checkReadiness()
    }

    // ── Session Lifecycle ───────────────────────────────────────────────

    fun toggleSession() {
        if (sessionActive) {
            stopSession()
        } else {
            startSession()
        }
    }

    private fun startSession() {
        val state = _uiState.value
        if (!state.isLlmReady) {
            _uiState.update { it.copy(statusText = "Please download models first", showModelSheet = true) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(statusText = "Loading LLM model...", voiceState = VoiceState.THINKING) }

            try {
                val llmFile = downloadManager.getModelFile(state.selectedLlmModel)

                // Configure Llamatik generation parameters
                LlamaBridge.updateGenerateParams(
                    temperature = 0.7f,
                    maxTokens = 256,
                    topP = 0.95f,
                    topK = 40,
                    repeatPenalty = 1.1f,
                    contextLength = 2048,
                    numThreads = 4,
                    useMmap = true,
                    flashAttention = false,
                    batchSize = 512,
                    gpuLayers = 0
                )

                val llmLoaded = LlamaBridge.initGenerateModel(llmFile.absolutePath)
                if (!llmLoaded) {
                    _uiState.update { it.copy(statusText = "Failed to load LLM", voiceState = VoiceState.IDLE) }
                    return@launch
                }

                // Initialize and start S2S native mic engine
                val vadFile = downloadManager.getModelFile(ModelType.SILERO_VAD)
                val whisperFile = downloadManager.getModelFile(ModelType.WHISPER_TINY)
                val ttsFile = downloadManager.getModelFile(ModelType.PIPER_TTS_VOICE)

                // Initialize Whisper STT bridge model if present
                if (whisperFile.exists()) {
                    WhisperBridge.initModel(whisperFile.absolutePath)
                }

                s2sEngine.initialize(
                    vadPath = if (vadFile.exists()) vadFile.absolutePath else "",
                    sttPath = if (whisperFile.exists()) whisperFile.absolutePath else "",
                    llmPath = llmFile.absolutePath,
                    ttsPath = if (ttsFile.exists()) ttsFile.absolutePath else ""
                )

                // Wire up S2SEngine callbacks
                s2sEngine.onTranscript = { text, isFinal ->
                    if (isFinal) {
                        onUserUtteranceComplete(text)
                    } else {
                        _uiState.update { it.copy(liveTranscript = text) }
                    }
                }

                s2sEngine.onTranscribeAudio = { samples ->
                    Log.d("S2S_VM", "onTranscribeAudio called with ${samples.size} samples")
                    try {
                        try { LlamaBridge.nativeCancelGenerate() } catch (_: Throwable) {}
                        try { androidTts?.stop() } catch (_: Throwable) {}

                        synchronized(ggmlGlobalLock) {
                            val wavFile = java.io.File(getApplication<Application>().cacheDir, "stt_${System.currentTimeMillis()}.wav")
                            writeWavFile(wavFile, samples, 16000)
                            val text = WhisperBridge.transcribeWav(wavFile.absolutePath)
                            wavFile.delete()
                            Log.d("S2S_VM", "Whisper STT result: '$text'")
                            text ?: ""
                        }
                    } catch (e: Throwable) {
                        Log.e("S2S_VM", "Error in WhisperBridge transcribeWav", e)
                        ""
                    }
                }

                audioPlayback.onPlaybackStarted = {
                    s2sEngine.isVADActive = false
                }
                audioPlayback.onPlaybackFinished = {
                    s2sEngine.isVADActive = true
                }

                s2sEngine.onAudioChunk = { samples ->
                    Log.d("S2S_VM", "onAudioChunk received: ${samples.size} float samples")
                    audioPlayback.queueAudio(samples)
                }

                // Native zero-latency audio synthesis pipeline enabled (sub-5ms)
                s2sEngine.onSynthesizeTTS = null

                s2sEngine.onBargeIn = {
                    bargeInCount++
                    audioPlayback.flushAndInterrupt()
                    _uiState.update {
                        it.copy(
                            voiceState = VoiceState.LISTENING,
                            statusText = "Listening...",
                            metrics = it.metrics.copy(bargeInCount = bargeInCount)
                        )
                    }
                }

                val engineStarted = s2sEngine.start()
                if (!engineStarted) {
                    _uiState.update { it.copy(statusText = "Failed to start microphone loop", voiceState = VoiceState.IDLE) }
                    return@launch
                }

                sessionActive = true
                bargeInCount = 0
                audioPlayback.start()

                _uiState.update {
                    it.copy(
                        voiceState = VoiceState.LISTENING,
                        statusText = "Listening...",
                        messages = emptyList(),
                        liveTranscript = "",
                        metrics = LatencyMetrics()
                    )
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statusText = "Error: ${e.localizedMessage}", voiceState = VoiceState.IDLE)
                }
            }
        }
    }

    private fun stopSession() {
        sessionActive = false
        try {
            LlamaBridge.nativeCancelGenerate()
        } catch (_: Throwable) {}
        audioPlayback.release()
        s2sEngine.stop()
        _uiState.update {
            it.copy(voiceState = VoiceState.IDLE, statusText = "Session ended", liveTranscript = "")
        }
    }

    // ── Conversation Flow ───────────────────────────────────────────────

    private fun onUserUtteranceComplete(transcript: String) {
        if (transcript.isBlank()) return

        val userMsg = ChatMessage(
            id = "msg_${messageIdCounter++}",
            role = "user",
            text = transcript.trim()
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMsg,
                liveTranscript = "",
                voiceState = VoiceState.THINKING,
                statusText = "Thinking..."
            )
        }

        generateLlmResponse(transcript.trim())
    }

    private fun generateLlmResponse(userText: String) {
        val assistantMsgId = "msg_${messageIdCounter++}"
        val startTime = System.currentTimeMillis()
        var firstTokenReceived = false

        val assistantMsg = ChatMessage(
            id = assistantMsgId,
            role = "assistant",
            text = "",
            isStreaming = true
        )
        _uiState.update { it.copy(messages = it.messages + assistantMsg) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Build the prompt using the model's chat template if available
                val history = _uiState.value.messages
                    .filter { !it.isStreaming }
                    .takeLast(10) // keep last 5 turns for context window
                    .map { it.role to it.text }

                val systemPrompt = "You are a helpful voice assistant running locally on an Android phone. " +
                        "Keep answers short, clear, and conversational — ideally 1-3 sentences."

                val allMessages = listOf("system" to systemPrompt) + history

                val prompt = LlamaBridge.applyChatTemplate(allMessages, addAssistantPrefix = true)
                    ?: buildFallbackPrompt(systemPrompt, history, userText)

                var sentenceBuffer = StringBuilder()

                synchronized(ggmlGlobalLock) {
                    LlamaBridge.generateStream(
                        prompt = prompt,
                        callback = object : GenStream {
                            override fun onDelta(text: String) {
                                if (!firstTokenReceived) {
                                    firstTokenReceived = true
                                    val ttft = System.currentTimeMillis() - startTime
                                    _uiState.update {
                                        it.copy(
                                            voiceState = VoiceState.SPEAKING,
                                            statusText = "Speaking...",
                                            metrics = it.metrics.copy(ttftMs = ttft)
                                        )
                                    }
                                }

                                _uiState.update { state ->
                                    state.copy(
                                        messages = state.messages.map { msg ->
                                            if (msg.id == assistantMsgId) msg.copy(text = msg.text + text)
                                            else msg
                                        }
                                    )
                                }

                                 // Stream sentence chunks directly to native neural TTS pipeline
                                sentenceBuffer.append(text)
                                val current = sentenceBuffer.toString()
                                if (current.contains(".") || current.contains("!") || current.contains("?") || current.contains("\n")) {
                                    val splitIdx = maxOf(
                                        current.lastIndexOf('.'),
                                        current.lastIndexOf('!'),
                                        current.lastIndexOf('?'),
                                        current.lastIndexOf('\n')
                                    )
                                    if (splitIdx >= 0) {
                                        val sentenceToSpeak = current.substring(0, splitIdx + 1)
                                        val remaining = current.substring(splitIdx + 1)
                                        sentenceBuffer = StringBuilder(remaining)
                                        Log.d("S2S_VM", "Speaking sentence: '${sentenceToSpeak.take(80)}'")
                                        androidTts?.speak(sentenceToSpeak, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "ut_${System.currentTimeMillis()}")
                                    }
                                }
                            }

                            override fun onComplete() {
                                if (sentenceBuffer.isNotEmpty()) {
                                    val remainingText = sentenceBuffer.toString()
                                    Log.d("S2S_VM", "onComplete speaking remaining: '${remainingText.take(80)}'")
                                    androidTts?.speak(remainingText, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "ut_${System.currentTimeMillis()}")
                                    sentenceBuffer.clear()
                                }

                                _uiState.update { state ->
                                    state.copy(
                                        messages = state.messages.map { msg ->
                                            if (msg.id == assistantMsgId) msg.copy(isStreaming = false)
                                            else msg
                                        },
                                        voiceState = if (sessionActive) VoiceState.LISTENING else VoiceState.IDLE,
                                        statusText = if (sessionActive) "Listening..." else "Session ended"
                                    )
                                }
                            }

                            override fun onError(message: String) {
                                s2sEngine.interrupt()
                                _uiState.update { state ->
                                    state.copy(
                                        messages = state.messages.map { msg ->
                                            if (msg.id == assistantMsgId) msg.copy(
                                                text = msg.text + "\n[Error: $message]",
                                                isStreaming = false
                                            )
                                            else msg
                                        },
                                        voiceState = if (sessionActive) VoiceState.LISTENING else VoiceState.IDLE,
                                        statusText = "LLM error — listening..."
                                    )
                                }
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        statusText = "LLM Error: ${e.localizedMessage}",
                        voiceState = if (sessionActive) VoiceState.LISTENING else VoiceState.IDLE
                    )
                }
            }
        }
    }

    private fun buildFallbackPrompt(
        system: String,
        history: List<Pair<String, String>>,
        userText: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("<|system|>")
        sb.appendLine(system)
        sb.appendLine("<|end|>")
        for ((role, content) in history) {
            sb.appendLine("<|$role|>")
            sb.appendLine(content)
            sb.appendLine("<|end|>")
        }
        sb.appendLine("<|user|>")
        sb.appendLine(userText)
        sb.appendLine("<|end|>")
        sb.appendLine("<|assistant|>")
        return sb.toString()
    }

    // ── Manual Text Input (for testing without mic) ─────────────────────

    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        if (!_uiState.value.isLlmReady) {
            _uiState.update { it.copy(statusText = "Load LLM model first") }
            return
        }

        if (!sessionActive) {
            startSession()
        }
        onUserUtteranceComplete(text)
    }

    fun onBargeIn() {
        if (_uiState.value.voiceState == VoiceState.SPEAKING) {
            try {
                LlamaBridge.nativeCancelGenerate()
            } catch (_: Throwable) {}
            s2sEngine.interrupt()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
        try { LlamaBridge.shutdown() } catch (_: Throwable) {}
        try { WhisperBridge.release() } catch (_: Throwable) {}
    }

    private fun writeWavFile(file: java.io.File, floatSamples: FloatArray, sampleRate: Int = 16000) {
        val totalAudioLen = floatSamples.size * 2
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * 2

        java.io.FileOutputStream(file).use { out ->
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
            header[20] = 1; header[21] = 0
            header[22] = 1; header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = 2; header[33] = 0
            header[34] = 16; header[35] = 0
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xff).toByte()
            header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
            header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
            header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
            out.write(header)

            val pcmBuffer = ByteArray(floatSamples.size * 2)
            var byteIdx = 0
            for (f in floatSamples) {
                val clamped = (f.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                pcmBuffer[byteIdx++] = (clamped.toInt() and 0xff).toByte()
                pcmBuffer[byteIdx++] = ((clamped.toInt() shr 8) and 0xff).toByte()
            }
            out.write(pcmBuffer)
        }
    }
}
