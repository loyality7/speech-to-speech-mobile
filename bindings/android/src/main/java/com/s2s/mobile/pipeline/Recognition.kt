package com.s2s.mobile.pipeline

/**
 * Frame-level voice activity detection.
 *
 * Used for barge-in only: it answers "is someone talking right now", which the
 * recogniser cannot answer while it is deliberately idle.
 */
interface VoiceActivityDetector {
    /** Samples per call to [accept]. Silero v5 is trained on 512 at 16 kHz. */
    val frameSize: Int

    fun initialize(): Result<Unit>

    /** Feeds one frame. Returns true while speech is present. */
    fun accept(frame: FloatArray): Boolean

    fun reset()

    fun release()
}

/** What the recogniser makes of the audio so far. */
sealed interface Transcript {
    /** Nothing decodable yet. */
    data object Nothing : Transcript

    /** Text decoded so far in the current utterance; may still change. */
    data class Partial(val text: String) : Transcript

    /** The user stopped talking. [text] is the settled utterance. */
    data class Final(val text: String) : Transcript
}

/**
 * Streaming speech recognition with built-in endpointing.
 *
 * Decoding happens while the user is still talking, so [Transcript.Final] costs
 * no extra wait once the endpoint fires. That is where most of the latency
 * budget is won or lost.
 */
interface SpeechRecognizer {
    fun initialize(): Result<Unit>

    /** Feeds one frame of user audio. */
    fun accept(frame: FloatArray): Transcript

    /** Discards partial state and starts a fresh utterance. */
    fun reset()

    fun release()
}
