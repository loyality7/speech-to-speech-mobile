package com.s2s.mobile.pipeline

/**
 * Neural text-to-speech families supported by the bundled sherpa-onnx runtime.
 *
 * Each expects a different set of files in the model directory, so switching
 * family means switching model bundle, not just a flag.
 */
enum class TtsBackend {
    /** Kokoro-82M. Multi-voice, multilingual, the pipeline default. */
    KOKORO,

    /** VITS / Piper. Single voice per bundle, smallest and fastest. */
    VITS,

    /** Matcha-TTS. Needs a separate vocoder file alongside the acoustic model. */
    MATCHA,

    /** Kitten-TTS. Very small multi-voice model. */
    KITTEN,

    /**
     * Pocket-TTS. Matches the `pocket` backend in the Python pipeline. Ships as
     * several ONNX parts plus JSON vocabularies rather than one model file.
     */
    POCKET,
}

/** A voice offered by the loaded model. */
data class Voice(val id: Int, val name: String)

/**
 * Streaming speech synthesis.
 *
 * Audio arrives chunk by chunk as the model produces it so playback can start
 * before the sentence is finished, and [keepGoing] can abort mid-word when the
 * user interrupts.
 */
interface SpeechSynthesizer {
    /** Model output rate. Only valid after a successful [initialize]. */
    val sampleRate: Int

    /** Voices the loaded bundle exposes. Single-voice models return one entry. */
    val voices: List<Voice>

    fun initialize(): Result<Unit>

    /**
     * Synthesises [text], handing each chunk to [onChunk].
     *
     * [keepGoing] is polled per chunk; returning false aborts immediately.
     */
    fun synthesize(
        text: String,
        keepGoing: () -> Boolean,
        onChunk: (FloatArray) -> Unit,
    )

    /** Switches voice for subsequent calls. Ignored by single-voice models. */
    fun selectVoice(voiceId: Int)

    fun release()
}
