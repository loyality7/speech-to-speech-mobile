package com.s2s.plugin.s1

import android.content.Context
import android.util.Log
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * In-process normalization for the plugin's own setup screen.
 *
 * Exists so the "Try it" button exercises the real model through the real
 * protocol rather than reporting on a formatted string. Kept separate from
 * [S1MiniNormalizerService] because the service is bound by the host in a
 * different process; the setup Activity is in this app's main process and
 * would otherwise have no way to load the model at all.
 *
 * Note the consequence, which is a real constraint rather than an
 * oversight: the setup screen and the bound service each load their own
 * copy of S1-mini if both are used at once, because `LlamaBridge` is a
 * per-process singleton. In practice they are not used simultaneously —
 * the screen is for setup, the service for live normalization — and the
 * model is released after a test to keep it that way.
 */
internal object S1MiniLocalTest {

    /**
     * Normalizes [text] with the real model, loading it if needed and
     * releasing it afterwards.
     *
     * Releases deliberately: this is a test path, and leaving a 462 MiB
     * model resident in the setup Activity's process after the user has
     * finished poking at it would compete for RAM with the assistant's own
     * model for no benefit.
     */
    fun normalize(context: Context, text: String): String {
        val model = ModelDownload.target(context.filesDir)
        if (!model.isFile) return "(model not installed)"

        return try {
            loadModel(model)
            val prompt = LlamaBridge.applyChatTemplate(
                listOf(
                    "system" to S1MiniProtocol.SYSTEM_PROMPT,
                    "user" to S1MiniProtocol.userTurn(text, null, null, null),
                ),
                addAssistantPrefix = true,
            ) + THINK_PREFIX

            val decoded = generate(prompt)
            val cleaned = S1MiniProtocol.cleanOutput(decoded)
            if (S1MiniProtocol.isValidOutput(cleaned, text)) {
                cleaned
            } else {
                "(model output rejected by validation; the assistant would use your raw words instead)\nraw model output: $decoded"
            }
        } catch (e: Throwable) {
            Log.w(TAG, "test normalization failed", e)
            "(failed: ${e.message})"
        } finally {
            runCatching { LlamaBridge.shutdown() }
        }
    }

    private fun loadModel(model: File) {
        LlamaBridge.updateGenerateParams(
            temperature = 0f,
            topK = 1,
            topP = 1f,
            maxTokens = 512,
            contextLength = 2048,
            numThreads = 4,
            useMmap = true,
            repeatPenalty = 1.0f,
            flashAttention = false,
            batchSize = 256,
        )
        require(LlamaBridge.initGenerateModel(model.absolutePath)) { "llama.cpp refused ${model.name}" }
    }

    private fun generate(prompt: String): String {
        val out = StringBuilder()
        val done = CountDownLatch(1)
        var failure: String? = null

        LlamaBridge.generateStream(
            prompt = prompt,
            callback = object : GenStream {
                override fun onDelta(text: String) {
                    out.append(text)
                }
                override fun onComplete() = done.countDown()
                override fun onError(message: String) {
                    failure = message
                    done.countDown()
                }
            },
        )
        if (!done.await(20_000, TimeUnit.MILLISECONDS)) {
            runCatching { LlamaBridge.nativeCancelGenerate() }
            throw IllegalStateException("timed out")
        }
        failure?.let { throw IllegalStateException(it) }
        return out.toString()
    }

    /** See [S1MiniNormalizerService] — S1-mini was trained with thinking off; Qwen3's template defaults it on. */
    private const val THINK_PREFIX = "<think>\n\n</think>\n\n"

    private const val TAG = "S1MiniLocalTest"
}
