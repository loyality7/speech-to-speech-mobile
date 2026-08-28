package com.s2s.demo.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.s2s.mobile.pipeline.ToolCall
import com.s2s.mobile.pipeline.ToolContext
import com.s2s.mobile.pipeline.ToolDefinition
import com.s2s.mobile.pipeline.ToolFunction
import com.s2s.mobile.pipeline.ToolResult
import com.s2s.mobile.pipeline.Tools
import com.s2s.plugin.api.IS2SToolPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Makes an externally-installed plugin APK look like an ordinary [Tools]
 * implementation.
 *
 * This is the whole trick that keeps the rest of Jarvis unchanged:
 * `PluginProvider<Tools>` already promised the host doesn't care *how* a
 * capability is produced, so an IPC-backed adapter satisfies
 * [com.s2s.host.core.HostComposer] exactly like the in-process
 * `ToolRegistry` does. Nothing in s2s-agent, s2s-host or
 * speech-to-speech-mobile needed to learn what a bound service is.
 *
 * Failure policy: every IPC call can fail (plugin uninstalled mid-session,
 * process killed, binder died). A failure returns an error [ToolResult] —
 * never throws into the agent loop, and never takes Jarvis down with the
 * plugin.
 *
 * @param config values the host stored for this plugin, forwarded to the
 *   plugin on each call prefixed with `__config_`. The host still doesn't
 *   interpret them; it just carries them across the process boundary.
 */
class BoundServiceTools(
    private val context: Context,
    private val packageName: String,
    private val serviceClass: String,
    private val config: Map<String, String> = emptyMap(),
    private val hostApiVersion: Int = 1,
) : Tools {

    @Volatile private var service: IS2SToolPlugin? = null
    @Volatile private var cachedDefinitions: List<ToolDefinition>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IS2SToolPlugin.Stub.asInterface(binder)
            connectedLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The plugin process died or was updated. Drop the stale binder
            // so the next call re-binds instead of throwing DeadObjectException.
            service = null
            cachedDefinitions = null
        }
    }

    @Volatile private var connectedLatch: CountDownLatch? = null
    @Volatile private var bindRequested = false

    /** Binds and waits, briefly. Returns null if the plugin can't be reached — the caller degrades, it does not crash. */
    private fun connect(): IS2SToolPlugin? {
        service?.let { return it }

        synchronized(this) {
            service?.let { return it }
            val latch = CountDownLatch(1)
            connectedLatch = latch

            if (!bindRequested) {
                val intent = Intent().setComponent(ComponentName(packageName, serviceClass))
                val ok = runCatching {
                    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                }.getOrDefault(false)
                if (!ok) {
                    Log.w(TAG, "Could not bind plugin service $packageName/$serviceClass")
                    return null
                }
                bindRequested = true
            }

            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val bound = service ?: return null

            // Refuse a plugin built for a newer host than we are. Checked
            // here as well as at install time because the package can be
            // updated underneath us between sessions.
            val pluginApi = runCatching { bound.apiVersion() }.getOrDefault(Int.MAX_VALUE)
            if (pluginApi > hostApiVersion) {
                Log.w(TAG, "Plugin $packageName targets host API $pluginApi, this host is $hostApiVersion — refusing")
                return null
            }
            return bound
        }
    }

    override val definitions: List<ToolDefinition>
        get() {
            cachedDefinitions?.let { return it }
            val plugin = connect() ?: return emptyList()
            val parsed = runCatching {
                val array = JSONArray(plugin.toolDefinitionsJson())
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    val params = obj.optJSONObject("parameters")
                    ToolDefinition(
                        name = obj.getString("name"),
                        description = obj.optString("description"),
                        parameters = params?.keys()?.asSequence()?.associateWith { params.getString(it) } ?: emptyMap(),
                    )
                }
            }.getOrElse {
                Log.w(TAG, "Plugin $packageName returned malformed tool definitions", it)
                emptyList()
            }
            cachedDefinitions = parsed
            return parsed
        }

    override fun execute(call: ToolCall, context: ToolContext): ToolResult {
        val plugin = connect()
            ?: return ToolResult(call.name, "Plugin $packageName is not available", isError = true)

        return runCatching {
            val args = JSONObject().apply {
                call.arguments.forEach { (k, v) -> put(k, v) }
                // Host-stored settings ride along, namespaced so they can't
                // collide with a real argument the model supplied.
                config.forEach { (k, v) -> put("__config_$k", v) }
            }
            val reply = JSONObject(plugin.execute(call.name, args.toString()))
            ToolResult(call.name, reply.optString("output"), isError = reply.optBoolean("isError", false))
        }.getOrElse {
            Log.w(TAG, "Plugin $packageName failed executing ${call.name}", it)
            ToolResult(call.name, "Plugin call failed: ${it.message ?: it.javaClass.simpleName}", isError = true)
        }
    }

    /**
     * [Tools.parse] stays entirely host-side. A plugin supplies capabilities,
     * not the wire format the model speaks — letting an installed third-party
     * package redefine how tool calls are parsed would hand it control over
     * every other plugin's dispatch.
     */
    override fun parse(text: String): ToolCall? = null

    override fun promptSection(): String? {
        val defs = definitions
        if (defs.isEmpty()) return null
        return buildString {
            defs.forEach { d -> append("- ").append(d.name).append(": ").append(d.description).appendLine() }
        }
    }

    override fun register(definition: ToolDefinition, function: ToolFunction) =
        throw UnsupportedOperationException("A remote plugin's tools are defined by the plugin, not registered by the host")

    override fun unregister(name: String) =
        throw UnsupportedOperationException("A remote plugin's tools are defined by the plugin, not registered by the host")

    /** Releases the binding. The host calls this when the runtime stops so a plugin process isn't kept alive by a dead Jarvis session. */
    fun release() {
        if (!bindRequested) return
        runCatching { context.unbindService(connection) }
            .onFailure { Log.w(TAG, "unbind failed", it) }
        bindRequested = false
        service = null
        cachedDefinitions = null
    }

    private companion object {
        const val TAG = "BoundServiceTools"
        const val BIND_TIMEOUT_MS = 3_000L
    }
}
