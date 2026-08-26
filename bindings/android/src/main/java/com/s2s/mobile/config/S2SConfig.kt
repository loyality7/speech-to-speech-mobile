package com.s2s.mobile.config

/**
 * Everything the engine needs, grouped per pipeline stage.
 *
 * Only [models] is required; every stage has defaults tuned for a mid-range
 * phone running a sub-1B model.
 */
data class S2SConfig(
    val models: ModelPaths,
    val audio: AudioConfig = AudioConfig(),
    val vad: VadConfig = VadConfig(),
    val stt: SttConfig = SttConfig(),
    val generation: GenerationConfig = GenerationConfig(),
    val tts: TtsConfig = TtsConfig(),
    /**
     * Runs a silent warmup pass over VAD, STT and the LLM during [initialize],
     * so the first real turn does not pay ONNX graph allocation and KV session
     * setup on top of inference. TTS warms itself already via [TtsConfig.warmUp].
     */
    val warmUpOnInit: Boolean = true,
)
