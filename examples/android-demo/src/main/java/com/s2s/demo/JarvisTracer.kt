package com.s2s.demo

import android.util.Log
import com.s2s.agent.trace.AgentTracer
import com.s2s.agent.trace.TraceKind
import com.s2s.agent.trace.TraceRecord
import java.util.ArrayDeque

/**
 * The production [AgentTracer].
 *
 * Before this existed, [com.s2s.demo.JarvisRuntime] took `AgentRuntime`'s
 * default tracer — `NoopTracer` — so every trace record the harness produced
 * was discarded. The agent's whole lifecycle was invisible, and "why did it do
 * that?" could only be answered by reading raw logcat and guessing.
 *
 * Two sinks, because the two questions are different:
 *
 * - **logcat**, one line per record, for watching a live device.
 * - **[recent]**, a bounded in-memory ring, so a UI or a bug report can show
 *   the last N records of the turn that just happened without re-running it.
 *
 * Deliberately NOT persisted to disk. A trace contains task ids, timings and
 * tool names for every turn; writing that to storage creates a durable record
 * of when the user talked to their assistant and what it did, which is a
 * privacy cost this app has no reason to pay. The ring dies with the process,
 * same as the conversation UI.
 *
 * ponytail: fixed-size ring, single lock. Contention is a handful of records
 * per turn from one agent thread plus UI reads — if tracing ever moves onto a
 * hot path, make [recent] a lock-free snapshot instead.
 */
class JarvisTracer(private val capacity: Int = DEFAULT_CAPACITY) : AgentTracer {

    private val lock = Any()
    private val ring = ArrayDeque<TraceRecord>(capacity)

    override fun record(trace: TraceRecord) {
        synchronized(lock) {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(trace)
        }
        Log.println(priorityFor(trace), TAG, format(trace))
    }

    /** The most recent records, oldest first. A copy — safe to iterate while the agent keeps tracing. */
    fun recent(limit: Int = capacity): List<TraceRecord> = synchronized(lock) {
        ring.toList().takeLast(limit)
    }

    /** Records for one task only — the "explain this turn" view. */
    fun forTask(taskId: String): List<TraceRecord> = synchronized(lock) {
        ring.filter { it.taskId == taskId }
    }

    fun clear() = synchronized(lock) { ring.clear() }

    /**
     * A failure that stopped work logs at WARN so it survives a release
     * build's log filtering, while the ordinary stage-by-stage flow stays at
     * DEBUG. Without the split, either the interesting records drown in the
     * routine ones or the routine ones are missing when something breaks.
     */
    private fun priorityFor(trace: TraceRecord): Int = when {
        trace.errorType != null -> Log.WARN
        trace.kind == TraceKind.BUDGET_EXHAUSTED -> Log.WARN
        trace.status == "fell-back-to-text" -> Log.WARN
        trace.kind == TraceKind.TASK_STARTED || trace.kind == TraceKind.TASK_COMPLETED -> Log.INFO
        else -> Log.DEBUG
    }

    /**
     * One grep-able line per record.
     *
     * The task id is truncated to 8 characters: full UUIDs made every line
     * wrap in logcat, and 8 hex characters are already unique among the
     * handful of tasks alive at once. Fields that are null are omitted rather
     * than printed as "null", so a line stays short enough to read.
     */
    private fun format(trace: TraceRecord): String = buildString {
        append('[').append(trace.taskId.take(TASK_ID_CHARS)).append("] ")
        append(trace.kind.name)
        trace.status?.let { append(" status=").append(it) }
        trace.toolName?.let { append(" tool=").append(it) }
        trace.durationMs?.let { append(" ").append(it).append("ms") }
        if (trace.retryCount > 0) append(" retry=").append(trace.retryCount)
        trace.errorType?.let { append(" error=").append(it) }
        trace.detail?.let { append(" | ").append(it) }
    }

    private companion object {
        const val TAG = "JarvisTrace"

        /**
         * Enough for several complete turns, since the useful question is
         * usually "what happened in the last few turns", not "the whole
         * session". A turn currently produces roughly 5-10 records.
         */
        const val DEFAULT_CAPACITY = 200

        const val TASK_ID_CHARS = 8
    }
}
