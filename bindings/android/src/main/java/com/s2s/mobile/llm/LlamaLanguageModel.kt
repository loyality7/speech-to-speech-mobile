package com.s2s.mobile.llm

import android.util.Log
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import com.llamatik.library.platform.LlamaSession
import com.s2s.mobile.config.LlmConfig
import com.s2s.mobile.pipeline.ChatMessage
import com.s2s.mobile.pipeline.LanguageModel
import com.s2s.mobile.pipeline.TokenSink
import java.io.File

/**
 * Streaming generation on llama.cpp, through the Llamatik runtime.
 *
 * Every entry point is wrapped: these are native calls, and a missing symbol or
 * a bad model surfaces as an `Error` rather than an `Exception`. Catching only
 * `Exception` here is what let a bad load take the whole process down.
 */
class LlamaLanguageModel(
    private val config: LlmConfig,
    private val modelPath: String,
) : LanguageModel {

    @Volatile
    private var loaded = false

    /**
     * Session that keeps llama.cpp's KV cache warm between turns.
     *
     * Without it every turn re-prefills the whole conversation. Measured on a
     * mid-range device that costs ~5.9 ms per prompt character — linear in
     * conversation length, so a chat that starts at 2.6 s reaches 8.9 s by the
     * time the prompt is 1400 characters. With the session only the new user
     * text is prefilled, so cost stops growing with the conversation.
     */
    private var session: LlamaSession? = null

    /**
     * Set when the conversation was rewritten behind the cache — the user
     * continued a half-finished question, or a turn was cancelled mid-generation.
     * The cache then holds text that is no longer in the prompt, so it has to be
     * rebuilt once before reuse is safe again.
     */
    @Volatile
    private var contextDirty = false

    /**
     * Content of the last message the session ingested, used to find where the
     * cache ends in a possibly-trimmed history.
     *
     * Matching on content rather than a counter survives ChatHistory trimming
     * old turns, and stays correct when a cancelled reply is committed as a
     * partial assistant message.
     */
    @Volatile
    private var cacheTail: String? = null

    /** True once reuse has worked, so losing it later can be reported as a fault. */
    @Volatile
    private var reusedBefore = false

    /**
     * Set while a generation is streaming.
     *
     * trimMemory() arrives on whatever thread onTrimMemory() runs on — usually the
     * main thread — while generation runs on the LLM worker. Resetting the session
     * underneath an active stream would pull the KV state out from under it, so a
     * purge that lands mid-turn is deferred instead.
     */
    @Volatile
    private var generating = false

    /** A purge asked for while generating; applied when the turn ends. */
    @Volatile
    private var purgePending = false

    /** True once this instance won the process-wide claim, so failure knows to give it back. */
    private var claimed = false

    /** Guards cache-state mutation against a concurrent purge. */
    private val cacheLock = Any()

    override fun initialize(): Result<Unit> = runCatching {
        val gguf = File(modelPath)
        require(gguf.isFile) { "LLM model not found: ${gguf.absolutePath}" }

        // LlamaBridge is a process-wide object holding one model and one KV
        // session, so a second live instance would silently share both: the newer
        // model would replace the older, and either instance's sessionReset()
        // would wipe the other's cache. The symptom is wrong answers, not a
        // crash, so refuse it here rather than let it be debugged in the field.
        synchronized(initLock) {
            check(!isInstanceActive) {
                "Another S2SEngine is already initialised in this process. " +
                    "The llama.cpp runtime is process-global, so only one engine " +
                    "may be live at a time — release() the first one first."
            }
            isInstanceActive = true
            claimed = true
        }

        LlamaBridge.updateGenerateParams(
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            topP = config.topP,
            topK = config.topK,
            repeatPenalty = config.repeatPenalty,
            contextLength = config.contextLength,
            numThreads = config.numThreads,
            useMmap = config.useMmap,
            flashAttention = config.flashAttention,
            batchSize = config.batchSize,
            gpuLayers = config.gpuLayers,
        )
        require(LlamaBridge.initGenerateModel(gguf.absolutePath)) {
            "llama.cpp refused the model: ${gguf.absolutePath}"
        }

        if (config.reuseKvCache) {
            session = runCatching { LlamaBridge.createSession("s2s") }
                .onFailure { Log.w(TAG, "session unavailable, falling back to full prefill", it) }
                .getOrNull()
            contextDirty = false
        }
        loaded = true
        Unit
    }.onFailure {
        // Give the claim back, or a failed load bricks the process for every
        // later attempt. Only if we took it — otherwise the rejected second
        // instance would free the first instance's claim.
        releaseClaim()
        Log.e(TAG, "initialize failed", it)
    }

    private fun releaseClaim() = synchronized(initLock) {
        if (claimed) {
            claimed = false
            isInstanceActive = false
        }
    }

    override fun generate(messages: List<ChatMessage>, sink: TokenSink) {
        if (!loaded) {
            sink.onError("Model not loaded")
            return
        }
        try {
            val entered = System.currentTimeMillis()
            val active = session

            // The cache ends at whatever was last fed to the session, so the delta
            // is everything after it. Anchoring on content rather than a count
            // survives history trimming and a cancelled reply being committed as a
            // partial assistant message.
            //
            // Compared trimmed on both sides. Models routinely emit a trailing
            // newline, and a barge-in commits the partial reply through trim(), so
            // an exact match would fail on whitespace alone — and once it fails it
            // never recovers, silently costing a full prefill on every later turn.
            val anchor = CacheAnchor.indexIn(messages, cacheTail)
            val reusable = active != null && !contextDirty && anchor >= 0
            val outgoing = if (reusable) messages.drop(anchor + 1) else messages

            if (!reusable) {
                // Warn rather than inform once reuse has worked before: losing it
                // costs seconds per turn, and the only previous symptom was a log
                // line nobody reads and a conversation that quietly got slower.
                val lost = reusedBefore && active != null && !contextDirty
                val message = "full prefill — session=${active != null}, dirty=$contextDirty, " +
                    "anchor=$anchor, messages=${messages.size}"
                if (lost) Log.w(TAG, "$message — cache reuse LOST, turns will be slow from here") else Log.i(TAG, message)
            } else {
                reusedBefore = true
            }

            if (active != null && !reusable) {
                runCatching { LlamaBridge.sessionReset() }
                contextDirty = false
            }

            val prompt = buildPrompt(outgoing)
            val built = System.currentTimeMillis()
            var first = true

            // Everything submitted is now in the cache, and so is whatever the
            // model generates from it.
            // Trimmed, so it compares equal to whatever the engine commits.
            cacheTail = outgoing.lastOrNull()?.content?.trim() ?: cacheTail
            val generated = StringBuilder()
            val utf8Decoder = Utf8StreamDecoder()
            var stoppedEarly = false

            val callback = object : GenStream {
                override fun onDelta(text: String) {
                    if (stoppedEarly) return
                    val decoded = try {
                        utf8Decoder.decodeChunk(text)
                    } catch (e: Throwable) {
                        Log.w(TAG, "UTF-8 streaming decode exception caught: ${e.message}", e)
                        text
                    }
                    if (decoded.isEmpty()) return

                    generated.append(decoded)
                    if (first) {
                        first = false
                        Log.i(
                            TAG,
                            "prompt ${prompt.length} chars (${if (reusable) "session" else "full"}), " +
                                "built in ${built - entered}ms, " +
                                "first token ${System.currentTimeMillis() - built}ms after submit",
                        )
                    }

                    // The native side has no concept of stop sequences, so a match is
                    // only ever seen after it has already been generated — this trims
                    // it back out of both the emitted stream and the cached tail.
                    val stopAt = config.stopSequences.asSequence()
                        .map { generated.indexOf(it) to it }
                        .filter { it.first >= 0 }
                        .minByOrNull { it.first }
                    if (stopAt != null) {
                        val (index, _) = stopAt
                        val alreadyEmitted = generated.length - decoded.length
                        val toEmit = decoded.take((index - alreadyEmitted).coerceAtLeast(0))
                        if (toEmit.isNotEmpty()) sink.onToken(toEmit)
                        generated.setLength(index)
                        stoppedEarly = true
                        cacheTail = generated.toString().trim().ifBlank { cacheTail }
                        runCatching { active?.cancel() ?: LlamaBridge.nativeCancelGenerate() }
                        sink.onComplete()
                        return
                    }

                    sink.onToken(decoded)
                }

                override fun onComplete() {
                    // The cancel() triggered by an early stop still runs its own
                    // onComplete callback on the native thread — already reported.
                    if (stoppedEarly) return
                    val tail = utf8Decoder.flush()
                    if (tail.isNotEmpty()) {
                        generated.append(tail)
                        sink.onToken(tail)
                    }
                    cacheTail = generated.toString().trim().ifBlank { cacheTail }
                    sink.onComplete()
                }

                override fun onError(message: String) {
                    if (stoppedEarly) return
                    sink.onError(message)
                }
            }

            generating = true
            try {
                if (active != null) {
                    active.stream(prompt, callback)
                } else {
                    LlamaBridge.generateStream(prompt = prompt, callback = callback)
                }
            } finally {
                generating = false
                if (purgePending) {
                    purgePending = false
                    purgeCache()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "generation failed", e)
            sink.onError(e.message ?: e.javaClass.simpleName, e)
        }
    }

    override fun cancel() {
        runCatching { session?.cancel() }
        runCatching { LlamaBridge.nativeCancelGenerate() }
            .onFailure { Log.w(TAG, "cancel failed", it) }
    }

    override fun resetContext() {
        contextDirty = true
    }

    override fun trimMemory() {
        if (generating) {
            // Resetting the session mid-stream would corrupt the state the active
            // generation is reading from. The next turn will pay a full prefill
            // either way, so waiting costs nothing.
            Log.i(TAG, "trimMemory requested during generation — deferred to end of turn")
            purgePending = true
            return
        }
        purgeCache()
    }

    private fun purgeCache() = synchronized(cacheLock) {
        Log.i(TAG, "purging KV session cache buffers")
        contextDirty = true
        cacheTail = null
        runCatching { LlamaBridge.sessionReset() }
            .onFailure { Log.w(TAG, "sessionReset failed during trimMemory", it) }
    }

    override fun release() {
        loaded = false
        runCatching { session?.close() }
        session = null
        contextDirty = false
        runCatching { LlamaBridge.shutdown() }
            .onFailure { Log.w(TAG, "shutdown failed", it) }
        releaseClaim()
    }

    /**
     * Prefers the chat template baked into the GGUF, since a mismatched template
     * is a common cause of rambling or empty replies. Falls back to a generic
     * ChatML-ish layout only when the model carries none.
     */
    private fun buildPrompt(messages: List<ChatMessage>): String {
        val pairs = messages.map { it.role to it.content }
        val templated = runCatching {
            LlamaBridge.applyChatTemplate(pairs, addAssistantPrefix = true)
        }.getOrNull()
        if (!templated.isNullOrBlank()) return templated

        return buildString {
            messages.forEach { (role, content) ->
                append("<|").append(role).append("|>\n").append(content).append("\n<|end|>\n")
            }
            append("<|assistant|>\n")
        }
    }

    private companion object {
        const val TAG = "S2S-Llm"

        @Volatile
        private var isInstanceActive = false
        private val initLock = Any()
    }
}
