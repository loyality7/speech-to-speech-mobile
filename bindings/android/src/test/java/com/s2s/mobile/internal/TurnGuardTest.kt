package com.s2s.mobile.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnGuardTest {

    @Test
    fun testInitialState() {
        val guard = TurnGuard()
        assertEquals(0, guard.current)
    }

    @Test
    fun testTurnIncrementInvalidatesPreviousTurn() {
        val guard = TurnGuard()
        val turn1 = guard.current
        assertTrue(guard.isCurrent(turn1))

        val turn2 = guard.begin()
        assertEquals(turn1 + 1, turn2)
        assertFalse(guard.isCurrent(turn1))
        assertTrue(guard.isCurrent(turn2))
        assertTrue(guard.isStale(turn1))
    }
}
