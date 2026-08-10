package com.s2s.mobile.internal

import java.util.concurrent.atomic.AtomicInteger

/**
 * Barge-in controller. Port of the Python `pipeline/cancel_scope.py`.
 *
 * Every turn carries a generation number and every stage checks it before doing
 * work or emitting audio, so one increment invalidates recognition, generation,
 * synthesis and playback together. Cheaper and far less racy than trying to stop
 * each stage individually.
 */
internal class TurnGuard {

    private val generation = AtomicInteger(0)

    val current: Int get() = generation.get()

    /** Invalidates everything in flight and returns the new turn id. */
    fun begin(): Int = generation.incrementAndGet()

    fun isCurrent(turn: Int): Boolean = turn == generation.get()

    fun isStale(turn: Int): Boolean = turn != generation.get()
}
