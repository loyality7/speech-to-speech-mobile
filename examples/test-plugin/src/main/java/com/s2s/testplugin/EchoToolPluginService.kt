package com.s2s.testplugin

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.s2s.plugin.api.IS2SToolPlugin
import org.json.JSONArray
import org.json.JSONObject

/**
 * The whole plugin. Runs in its own process, in its own APK, under its own
 * uid — Jarvis never loads a line of this code into itself, it binds to this
 * service and exchanges strings.
 *
 * Note what is NOT here: no dependency on s2s-host, s2s-agent, or
 * speech-to-speech-mobile. A third-party developer could write this file
 * knowing only the AIDL interface and the manifest metadata keys.
 */
class EchoToolPluginService : Service() {

    private val binder = object : IS2SToolPlugin.Stub() {

        override fun apiVersion(): Int = 1

        override fun toolDefinitionsJson(): String = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("name", "echo")
                    put("description", "Repeats the given text back exactly.")
                    put("parameters", JSONObject().apply { put("text", "the text to repeat") })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "greet")
                    put("description", "Greets someone by name.")
                    put("parameters", JSONObject().apply { put("name", "who to greet") })
                },
            )
        }.toString()

        override fun execute(toolName: String?, argumentsJson: String?): String {
            val args = runCatching { JSONObject(argumentsJson ?: "{}") }.getOrElse { JSONObject() }
            return when (toolName) {
                "echo" -> result(args.optString("text", ""))
                "greet" -> {
                    // Reads its own configured greeting, which Jarvis passed
                    // down from the generic config form it rendered off this
                    // plugin's declared schema — proving configuration flows
                    // end to end without the host knowing what "greeting"
                    // means.
                    val greeting = args.optString("__config_greeting", "").ifBlank { "Hello" }
                    result("$greeting, ${args.optString("name", "there")}!")
                }
                else -> error("Unknown tool: $toolName")
            }
        }

        private fun result(output: String) = JSONObject().apply {
            put("output", output)
            put("isError", false)
        }.toString()

        private fun error(message: String) = JSONObject().apply {
            put("output", message)
            put("isError", true)
        }.toString()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
