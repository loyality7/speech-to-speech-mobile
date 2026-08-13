package com.s2s.mobile.model

import android.content.Context
import java.io.File

/**
 * Where the SDK keeps downloaded models.
 *
 * The downloader writes here and [com.s2s.mobile.config.ModelConfigFactory]
 * resolves every model path against it, so the location has to be identical on
 * both sides. It lives here rather than in each app because an app that picks a
 * different directory gets a silent failure: models download successfully and
 * the engine then reports them missing.
 *
 * External files dir, not internal: the default stack is roughly 800 MB, which
 * is more than most devices want to carry in app-private internal storage. It
 * is still app-scoped, so it needs no storage permission and is removed on
 * uninstall.
 */
object S2SModels {

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    /** Convenience for the common case of "give me a downloader wired to the right place". */
    fun downloader(context: Context): ModelDownloader = ModelDownloader(dir(context))
}
