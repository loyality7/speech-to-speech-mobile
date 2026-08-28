package com.example.s2splugin

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.s2s.plugin.api.IS2SToolPlugin
import org.json.JSONArray
import org.json.JSONObject

/**
 * A complete s2s plugin. Replace the two tools with your own.
 *
 * Note the dependencies: `android.*`, `org.json`, and the copied AIDL
 * interface. Nothing from `speech-to-speech-mobile`, `s2s-host`,
 * `s2s-agent` or `s2s-tools`. That is deliberate — if a plugin needed those
 * artifacts, it would be coupled to the host's internals and could not be
 * built or versioned independently.
 *
 * Threading: [execute] is called on a binder thread from another process.
 * Do not touch UI, do not assume the main looper, and keep it reasonably
 * fast — the host is waiting on it inside an agent turn, and a slow tool
 * shows up to the user as the assistant hanging.
 */
class ExampleToolPluginService : Service() {

    private val binder = object : IS2SToolPlugin.Stub() {

        /** Host API this plugin targets. The host refuses anything newer than itself. */
        override fun apiVersion(): Int = 1

        /**
         * Metadata only — no side effects, no network, no work. The host calls
         * this to build the tool list it shows the model, and may call it
         * more than once.
         */
        override fun toolDefinitionsJson(): String = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("name", "reverse_text")
                    put("description", "Reverses the characters of the given text.")
                    put("parameters", JSONObject().apply { put("text", "the text to reverse") })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "word_count")
                    put("description", "Counts the words in the given text.")
                    put("parameters", JSONObject().apply { put("text", "the text to count words in") })
                },
            )
        }.toString()

        /**
         * Runs one tool.
         *
         * [argumentsJson] is a flat JSON object of the arguments the model
         * supplied, plus this plugin's own stored settings prefixed
         * `__config_` (a `greeting` setting arrives as `__config_greeting`).
         * The prefix exists so a setting can never be shadowed by a model
         * argument of the same name.
         *
         * Always returns a JSON object. Never throws across the binder: a
         * failure is `{"isError": true}` with something the model can read,
         * because an exception here surfaces to the user as an unexplained
         * failure rather than a recoverable tool error.
         */
        override fun execute(toolName: String?, argumentsJson: String?): String {
            val args = runCatching { JSONObject(argumentsJson ?: "{}") }.getOrElse { JSONObject() }

            return when (toolName) {
                "reverse_text" -> ok(args.optString("text").reversed())

                "word_count" -> {
                    val words = args.optString("text").trim()
                        .split(Regex("\\s+"))
                        .filter { it.isNotEmpty() }
                    ok("${words.size}")
                }

                // An unknown tool is a host/plugin version mismatch, not a
                // crash: report it so the agent can recover or tell the user.
                else -> failed("This plugin has no tool named '$toolName'")
            }
        }

        private fun ok(output: String) = JSONObject().apply {
            put("output", output)
            put("isError", false)
        }.toString()

        private fun failed(message: String) = JSONObject().apply {
            put("output", message)
            put("isError", true)
        }.toString()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
