package com.s2s.mobile.llm

import android.util.Log
import com.s2s.mobile.pipeline.ChatMessage
import com.s2s.mobile.pipeline.GenerationOverrides
import com.s2s.mobile.pipeline.LanguageModel
import com.s2s.mobile.pipeline.TokenSink
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Streaming generation against a self-hosted OpenAI-compatible chat-completions
 * server (vLLM, llama.cpp-server, Ollama's OpenAI-compat endpoint,
 * text-generation-webui, etc) — not the public OpenAI API.
 *
 * Opt-in, alongside [LlamaLanguageModel]: local-first stays the default. This
 * exists for an app owner who runs their own model server and wants to point
 * at it instead of, or in addition to, an on-device model.
 *
 * No local KV cache: the full message list is sent every turn, same as the
 * chat-completions spec expects, so [resetContext] is a no-op — there is
 * nothing on this side that could go stale.
 */
class RemoteLanguageModel(
    private val config: RemoteLlmConfig,
) : LanguageModel {

    private val baseUrl = config.baseUrl.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming response, no fixed upper bound
        .build()

    @Volatile
    private var currentCall: Call? = null

    override fun initialize(): Result<Unit> = Result.success(Unit)

    override fun generate(messages: List<ChatMessage>, sink: TokenSink, overrides: GenerationOverrides?) {
        val body = buildRequestBody(messages, overrides)
        val requestBuilder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Accept", "text/event-stream")
        config.apiKey?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }

        val call = client.newCall(requestBuilder.build())
        currentCall = call
        runWithRetry(call, sink, attempt = 0)
    }

    private fun runWithRetry(call: Call, sink: TokenSink, attempt: Int) {
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                if (attempt < MAX_RETRIES) {
                    Log.w(TAG, "request failed (attempt ${attempt + 1}/$MAX_RETRIES), retrying", e)
                    val retryCall = client.newCall(call.request())
                    currentCall = retryCall
                    retryWithBackoff(attempt) { runWithRetry(retryCall, sink, attempt + 1) }
                } else {
                    sink.onError(e.message ?: "remote LLM request failed", e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    if (code >= 500 && attempt < MAX_RETRIES) {
                        Log.w(TAG, "server returned $code (attempt ${attempt + 1}/$MAX_RETRIES), retrying")
                        val retryCall = client.newCall(call.request())
                        currentCall = retryCall
                        retryWithBackoff(attempt) { runWithRetry(retryCall, sink, attempt + 1) }
                    } else {
                        sink.onError("remote LLM server returned HTTP $code")
                    }
                    return
                }
                consumeSse(response, sink)
            }
        })
    }

    private fun retryWithBackoff(attempt: Int, retry: () -> Unit) {
        val delayMs = RETRY_BASE_DELAY_MS * (1L shl attempt)
        Thread {
            Thread.sleep(delayMs)
            retry()
        }.start()
    }

    /**
     * Parses `data: {...}\n\n` chat-completions SSE chunks off the response body
     * on whatever thread OkHttp's dispatcher delivered the response on —
     * [TokenSink] callbacks land on that thread, same contract [LlamaLanguageModel]
     * gives its callers.
     */
    private fun consumeSse(response: Response, sink: TokenSink) {
        try {
            response.use {
                val source = it.body?.source() ?: run {
                    sink.onError("remote LLM response had no body")
                    return
                }
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") {
                        sink.onComplete()
                        return
                    }
                    if (payload.isEmpty()) continue

                    val delta = runCatching { extractDelta(payload) }
                        .onFailure { e -> Log.w(TAG, "skipping malformed SSE chunk: ${e.message}") }
                        .getOrNull()
                    if (!delta.isNullOrEmpty()) sink.onToken(delta)
                }
                sink.onComplete()
            }
        } catch (e: IOException) {
            if (currentCall?.isCanceled() != true) {
                sink.onError(e.message ?: "remote LLM stream broke", e)
            }
        }
    }

    internal fun extractDelta(payload: String): String? {
        val choice = JSONObject(payload).optJSONArray("choices")?.optJSONObject(0) ?: return null
        return choice.optJSONObject("delta")?.optString("content")
    }

    internal fun buildRequestBody(messages: List<ChatMessage>, overrides: GenerationOverrides?): JSONObject {
        val effectiveMaxTokens = overrides?.maxTokens ?: config.maxTokens
        val effectiveTemperature = overrides?.temperature ?: config.temperature
        val effectiveTopP = overrides?.topP ?: config.topP
        val effectiveStop = overrides?.stopSequences ?: config.stopSequences

        return JSONObject().apply {
            put("model", config.remoteModelName ?: "default")
            put("stream", true)
            put("max_tokens", effectiveMaxTokens)
            put("temperature", effectiveTemperature)
            put("top_p", effectiveTopP)
            if (effectiveStop.isNotEmpty()) put("stop", JSONArray(effectiveStop))
            put(
                "messages",
                JSONArray(
                    messages.map { m ->
                        JSONObject().put("role", m.role).put("content", m.content)
                    },
                ),
            )
        }
    }

    override fun cancel() {
        currentCall?.cancel()
    }

    // No local session to invalidate — the full message list is re-sent every turn.
    override fun resetContext() {}

    override fun release() {
        currentCall?.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private companion object {
        const val TAG = "S2S-RemoteLlm"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_RETRIES = 2
        const val RETRY_BASE_DELAY_MS = 500L
    }
}
