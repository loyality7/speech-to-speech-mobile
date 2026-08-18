package com.s2s.mobile.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInGateTest {

    @Test
    fun testRequiresConsecutiveFramesNotJustOne() {
        // Was issue #57's real gap: a single voiced frame used to trigger barge-in
        // outright. 7 in a row must stay silent; the 8th (matching bargeInFrames)
        // must fire. Grace period given as already-elapsed so only the frame count
        // is under test here.
        val gate = BargeInGate(requiredFrames = 8, graceMs = 0)
        repeat(7) { assertFalse(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 1000)) }
        assertTrue(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 1000))
    }

    @Test
    fun testNonVoicedFrameResetsTheCount() {
        val gate = BargeInGate(requiredFrames = 3, graceMs = 0)
        assertFalse(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 1000))
        assertFalse(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 1000))
        assertFalse(gate.onFrame(voiced = false, elapsedSinceSpeakingMs = 1000)) // breaks the streak
        assertFalse(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 1000))
        assertFalse(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 1000))
        assertTrue(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 1000)) // fresh streak of 3
    }

    @Test
    fun testGracePeriodBlocksEvenWithEnoughFrames() {
        // Enough consecutive frames, but still inside the post-playback-start
        // grace window — must not fire yet.
        val gate = BargeInGate(requiredFrames = 2, graceMs = 400)
        assertFalse(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 100))
        assertFalse(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 200))
        assertTrue(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 500))
    }

    @Test
    fun testResetClearsCarryoverFromPreviousSession() {
        // A turn that ends just under the threshold must not give the next
        // SPEAKING session a head start.
        val gate = BargeInGate(requiredFrames = 5, graceMs = 0)
        repeat(4) { gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 1000) }
        gate.reset()
        assertFalse(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 1000))
    }

    @Test
    fun testTriggerResetsInternalCountSoItCanFireAgain() {
        val gate = BargeInGate(requiredFrames = 2, graceMs = 0)
        assertFalse(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 1000))
        assertTrue(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 1000))
        // Without the internal reset-on-trigger, a 3rd consecutive voiced frame
        // would also read as "true" purely because the count kept climbing.
        assertFalse(gate.onFrame(voiced = true, elapsedSinceSpeakingMs = 1000))
    }
}
