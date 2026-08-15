package com.s2s.mobile.model

/**
 * What a model download is currently doing.
 *
 * Top-level rather than nested inside the service on purpose: callers observe
 * this through [ModelDownloads] and should never need to name — or import — the
 * service that happens to produce it.
 */
sealed class DownloadState {
    object Idle : DownloadState()
    data class Progress(val progress: ModelProgress) : DownloadState()
    object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}
