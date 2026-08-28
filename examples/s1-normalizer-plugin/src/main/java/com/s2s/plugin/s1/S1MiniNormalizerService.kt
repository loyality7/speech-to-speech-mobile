package com.s2s.plugin.s1

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import com.s2s.plugin.api.IS2STextNormalizerPlugin
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * S1-mini as an installable text-normalizer plugin.
 *
 * ## Why this is a separate app
 *
 * `LlamaBridge` is a Kotlin `object` (one `INSTANCE`, private constructor)
 * whose JNI entry points take no model handle — `initGenerateModel(path)`
 * and `shutdown()` are global, and the only handle-based calls are KV-cache
 * sessions *within* the single loaded model. `initEmbedModel` is a separate
 * slot but only yields embeddings, not generation.
 *
 * So two generate models cannot be resident in one process. The host needs
 * its primary LLM loaded continuously; a normalizer that evicted it per
 * utterance would pay a full model load every turn. Running here, in this
 * app's own process, gives this model its own llama.cpp runtime and leaves
 * the host's untouched.
 *
 * ## Protocol
 *
 * Input format is exactly as documented in the model card (see
 * [S1MiniProtocol]) — system prompt, `[Styling: …] [Structure: …]
 * [Context: …]` control line, thinking disabled, greedy decoding. The
 * model was fine-tuned on that shape.
 *
 * ## Failure policy
 *
 * Never lose the turn. Missing model, load failure, timeout, inference
 * error, contaminated output — every path returns the caller's original
 * transcript. An unpolished transcript is a minor annoyance; a dropped
 * utterance is a broken assistant.
 */
class S1MiniNormalizerService : Service() {

    @Volatile private var modelLoaded = false

    private val binder = object : IS2STextNormalizerPlugin.Stub() {

        override fun apiVersion(): Int = 1

        override fun isModelAvailable(): Boolean = modelFile()?.isFile == true

        override fun warmUp(): Boolean = ensureModelLoaded()

        override fun normalize(
            rawTranscript: String?,
            styling: String?,
            structure: String?,
            context: String?,
        ): String {
            val raw = rawTranscript.orEmpty()
            if (raw.isBlank()) return raw

            if (!ensureModelLoaded()) {
                Log.w(TAG, "model unavailable — returning raw transcript")
                return raw
            }

            return try {
                val prompt = LlamaBridge.applyChatTemplate(
                    listOf(
                        "system" to S1MiniProtocol.SYSTEM_PROMPT,
                        "user" to S1MiniProtocol.userTurn(raw, styling, structure, context),
                    ),
                    addAssistantPrefix = true,
                    // The model card is explicit: S1-mini was trained with
                    // thinking off, and Qwen3's embedded template defaults it
                    // ON. Without this the model emits an empty <think> block
                    // and stops, producing nothing usable.
                ) + THINK_PREFIX

                val decoded = generate(prompt)
                val cleaned = S1MiniProtocol.cleanOutput(decoded)

                if (S1MiniProtocol.isValidOutput(cleaned, raw)) {
                    cleaned
                } else {
                    Log.w(TAG, "output rejected by validation — returning raw transcript")
                    raw
                }
            } catch (e: Throwable) {
                Log.w(TAG, "normalization failed — returning raw transcript", e)
                raw
            }
        }

        override fun releaseModel() {
            if (!modelLoaded) return
            runCatching { LlamaBridge.shutdown() }
                .onFailure { Log.w(TAG, "shutdown failed", it) }
            modelLoaded = false
        }
    }

    /** Blocking single-shot generation. Bounded by a timeout: a stalled model must not hold a voice turn open forever. */
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

        if (!done.await(GENERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            runCatching { LlamaBridge.nativeCancelGenerate() }
            throw IllegalStateException("normalization timed out after ${GENERATION_TIMEOUT_MS}ms")
        }
        failure?.let { throw IllegalStateException(it) }
        return out.toString()
    }

    /** Loads once and keeps the model resident — reloading per transcript would defeat the point of a separate process. */
    private fun ensureModelLoaded(): Boolean {
        if (modelLoaded) return true
        synchronized(this) {
            if (modelLoaded) return true
            val model = modelFile() ?: run {
                Log.w(TAG, "no S1-mini GGUF found in this plugin's model directory")
                return false
            }
            return runCatching {
                LlamaBridge.updateGenerateParams(
                    // Greedy decoding, as the GGUF card requires: normalization
                    // must be deterministic, and sampling makes the same
                    // utterance clean differently between turns.
                    temperature = 0f,
                    topK = 1,
                    topP = 1f,
                    maxTokens = MAX_OUTPUT_TOKENS,
                    contextLength = CONTEXT_LENGTH,
                    numThreads = THREADS,
                    useMmap = true,
                    repeatPenalty = 1.0f,
                    flashAttention = false,
                    batchSize = 256,
                )
                require(LlamaBridge.initGenerateModel(model.absolutePath)) { "llama.cpp refused ${model.name}" }
                modelLoaded = true
                Log.i(TAG, "S1-mini loaded from ${model.name} (${model.length() / 1_048_576} MiB)")
                true
            }.getOrElse {
                Log.e(TAG, "failed to load S1-mini", it)
                false
            }
        }
    }

    /**
     * The GGUF this plugin uses, from its own app-private storage.
     *
     * App-private on purpose: the model belongs to this plugin, not to the
     * host, and keeping it here means uninstalling the plugin reclaims the
     * space without the host having to know it existed.
     */
    private fun modelFile(): File? {
        val dir = File(filesDir, MODEL_DIR)
        if (!dir.isDirectory) return null
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            ?.maxByOrNull { it.length() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        if (modelLoaded) runCatching { LlamaBridge.shutdown() }
    }

    private companion object {
        const val TAG = "S1MiniNormalizer"
        const val MODEL_DIR = "models"

        /**
         * The assistant turn must start with an empty think block — the GGUF
         * card states S1-mini was trained with thinking off while Qwen3's
         * embedded template defaults it on.
         */
        const val THINK_PREFIX = "<think>\n\n</think>\n\n"

        /** Model card recommends ~1000 tokens of input; leave room for the control line and the answer. */
        const val CONTEXT_LENGTH = 2048
        const val MAX_OUTPUT_TOKENS = 512
        const val THREADS = 4

        /** A normalizer that takes longer than this has already ruined the turn; abandon it and use the raw text. */
        const val GENERATION_TIMEOUT_MS = 8_000L
    }
}
