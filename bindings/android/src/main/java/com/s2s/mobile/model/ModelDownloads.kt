package com.s2s.mobile.model

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Binds the download service and starts downloads, so callers do not have to.
 *
 * Downloading a model bundle correctly means binding a service, starting it in
 * the foreground, casting a binder, collecting a state flow and unbinding at the
 * right moment. That was ~60 lines duplicated in every screen that touches a
 * model, and getting any of it wrong fails at runtime rather than at compile
 * time. Everything a caller needs is now [state] and [start].
 *
 * Tied to its Context's lifetime: call [close] when the owner is destroyed.
 */
class ModelDownloads(private val context: Context) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<DownloadState>(
        DownloadState.Idle,
    )

    /** Progress, completion and failure of the current download. */
    val state: Flow<DownloadState> = _state.asStateFlow()

    private var service: ModelDownloadService? = null
    private var bound = false

    /** Held when start() is called before the service has finished binding. */
    private var pending: List<ModelSpec>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as ModelDownloadService.LocalBinder).getService()
            service = s
            bound = true
            // The service owns a StateFlow; mirror it so callers keep one stable
            // Flow across bind, unbind and rebind.
            scope.launch { s.downloadState.collect { _state.value = it } }
            pending?.let { specs ->
                pending = null
                start(specs)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    init {
        context.bindService(
            Intent(context, ModelDownloadService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    /**
     * Downloads every spec that is not already present.
     *
     * Safe to call before binding completes — the request is held and replayed.
     * Binding is asynchronous, so a user tapping "download" as soon as a screen
     * opens would otherwise be silently ignored.
     */
    fun start(specs: List<ModelSpec>) {
        val s = service
        if (s == null) {
            pending = specs
            return
        }
        context.startForegroundService(Intent(context, ModelDownloadService::class.java))
        s.startDownload(S2SModels.dir(context), specs)
    }

    fun stop() {
        pending = null
        service?.stopDownload()
    }

    override fun close() {
        pending = null
        scope.cancel()
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
        service = null
    }
}
