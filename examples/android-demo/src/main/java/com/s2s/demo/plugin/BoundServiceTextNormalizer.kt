package com.s2s.demo.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.s2s.mobile.pipeline.TextNormalizationOptions
import com.s2s.mobile.pipeline.TextNormalizer
import com.s2s.plugin.api.IS2STextNormalizerPlugin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Adapts an externally-installed normalizer plugin to the host's generic
 * [TextNormalizer] contract.
 *
 * Same pattern as [BoundServiceTools]: the host composes a capability
 * interface and never learns that a bound service, another APK, or another
 * inference runtime is involved. `S2SEngine` sees only [TextNormalizer].
 *
 * Every failure path returns the raw transcript. That is the contract
 * [TextNormalizer.normalize] demands, and it is the difference between "the
 * assistant heard me imperfectly" and "the assistant ignored me".
 */
class BoundServiceTextNormalizer(
    private val context: Context,
    private val packageName: String,
    private val serviceClass: String,
    private val hostApiVersion: Int = 1,
) : TextNormalizer {

    @Volatile private var service: IS2STextNormalizerPlugin? = null
    @Volatile private var bindRequested = false
    @Volatile private var connectedLatch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IS2STextNormalizerPlugin.Stub.asInterface(binder)
            connectedLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Plugin process died or was updated — drop the stale binder so
            // the next call rebinds instead of throwing DeadObjectException.
            service = null
        }
    }

    /**
     * Binds and asks the plugin to load its model, off the voice path.
     *
     * Called by the host when the runtime starts, so the first real
     * utterance does not pay cold start — a several-hundred-millisecond
     * model load in the middle of someone's first sentence is exactly the
     * experience this exists to avoid.
     */
    fun warmUp(): Boolean {
        val plugin = connect() ?: return false
        return runCatching { plugin.warmUp() }
            .onFailure { Log.w(TAG, "warm-up failed", it) }
            .getOrDefault(false)
    }

    override fun normalize(rawTranscript: String, options: TextNormalizationOptions): String {
        if (rawTranscript.isBlank()) return rawTranscript

        val plugin = connect() ?: return rawTranscript

        return runCatching {
            val result = plugin.normalize(
                rawTranscript,
                options.styling.wire(),
                options.structure.wire(),
                options.context.wire(),
            )
            // Trust nothing across a process boundary: a plugin that
            // returns null or empty must not blank out the user's turn.
            if (result.isNullOrBlank()) rawTranscript else result
        }.getOrElse {
            Log.w(TAG, "normalization call failed — using raw transcript", it)
            rawTranscript
        }
    }

    override fun release() {
        runCatching { service?.releaseModel() }
        if (!bindRequested) return
        runCatching { context.unbindService(connection) }
            .onFailure { Log.w(TAG, "unbind failed", it) }
        bindRequested = false
        service = null
    }

    private fun connect(): IS2STextNormalizerPlugin? {
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
                    Log.w(TAG, "could not bind normalizer service $packageName/$serviceClass")
                    return null
                }
                bindRequested = true
            }

            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val bound = service ?: return null

            val pluginApi = runCatching { bound.apiVersion() }.getOrDefault(Int.MAX_VALUE)
            if (pluginApi > hostApiVersion) {
                Log.w(TAG, "plugin targets host API $pluginApi, this host is $hostApiVersion — refusing")
                return null
            }
            return bound
        }
    }

    /** Option enums to the lowercase wire values the AIDL contract passes as strings. */
    private fun TextNormalizationOptions.Styling.wire() = name.lowercase().replace('_', '-')
    private fun TextNormalizationOptions.Structure.wire() = name.lowercase()
    private fun TextNormalizationOptions.Context.wire() = name.lowercase()

    private companion object {
        const val TAG = "BoundServiceNormalizer"
        const val BIND_TIMEOUT_MS = 3_000L
    }
}
