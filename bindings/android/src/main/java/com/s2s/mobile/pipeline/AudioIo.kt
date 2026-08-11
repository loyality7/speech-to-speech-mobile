package com.s2s.mobile.pipeline

/**
 * Source of microphone audio.
 *
 * Implementations must deliver frames of exactly [frameSize] mono float samples
 * in `[-1, 1]`. Partial frames break both the VAD window and the encoder, so a
 * short read has to be buffered, never forwarded.
 */
interface AudioInput {
    val sampleRate: Int
    val frameSize: Int

    /** Begins capture. Returns false if the device or permission is unavailable. */
    fun start(onFrame: (FloatArray) -> Unit): Boolean

    fun stop()
}

/**
 * Sink for synthesised audio.
 *
 * [flush] must drop queued *and* in-flight audio — that is what makes an
 * interruption feel immediate rather than "after the current sentence".
 */
interface AudioOutput {
    val sampleRate: Int

    fun start()

    fun write(samples: FloatArray)

    /** True while audio is queued or still playing out of the device buffer. */
    fun hasPending(): Boolean

    fun flush()

    fun release()

    /** Invoked once the queue empties and the hardware has played everything out. */
    var onDrained: (() -> Unit)?
}
