package com.s2s.mobile.internal

/**
 * Decides whether voice detected during SPEAKING is a real barge-in.
 *
 * Two independent guards, both required: [requiredFrames] consecutive voiced
 * frames (a single frame is cheap for an echo blip or a click to produce, several
 * in a row is not), and [graceMs] elapsed since playback started (the echo
 * canceller needs time to converge — without this, the assistant's own opening
 * syllable can trigger its own cutoff).
 */
internal class BargeInGate(
    private val requiredFrames: Int,
    private val graceMs: Long,
) {
    // Written by the audio thread every frame; reset() is called from the TTS
    // thread when a new SPEAKING session starts — needs cross-thread visibility,
    // same reasoning as S2SEngine.speakingSince.
    @Volatile private var consecutive = 0

    /** Call once when a new SPEAKING session starts — otherwise a count left over
     * from a turn that ended just under the threshold carries into the next one. */
    fun reset() {
        consecutive = 0
    }

    /** Feed one frame's voice-activity result. Returns true exactly once per real
     * barge-in — the internal count resets on trigger, same as on non-speech. */
    fun onFrame(voiced: Boolean, elapsedSinceSpeakingMs: Long): Boolean {
        consecutive = if (voiced) consecutive + 1 else 0
        if (consecutive >= requiredFrames && elapsedSinceSpeakingMs >= graceMs) {
            consecutive = 0
            return true
        }
        return false
    }
}
