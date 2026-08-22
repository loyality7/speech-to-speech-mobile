package com.s2s.mobile.model

import android.content.Context
import com.s2s.mobile.config.ModelDownloadConfig
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

    private const val PREFS = "s2s_models"
    private const val KEY_HF_TOKEN = "hf_token"

    fun dir(context: Context, config: ModelDownloadConfig = ModelDownloadConfig()): File =
        File(context.getExternalFilesDir(null), config.modelsDirName).apply { mkdirs() }

    /** Convenience for the common case of "give me a downloader wired to the right place". */
    fun downloader(context: Context, config: ModelDownloadConfig = ModelDownloadConfig()): ModelDownloader =
        ModelDownloader(dir(context, config), huggingFaceToken(context), config)

    /**
     * Hugging Face access token, for repositories gated behind a license
     * acceptance (e.g. Gemma) — a plain anonymous request to those gets a 401
     * regardless of how the download is written, so this has to exist somewhere.
     * Stored per-app, not per-model: one token authorizes whatever the user's HF
     * account has accepted.
     */
    fun huggingFaceToken(context: Context): String? =
        prefs(context).getString(KEY_HF_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setHuggingFaceToken(context: Context, token: String?) {
        prefs(context).edit().apply {
            if (token.isNullOrBlank()) remove(KEY_HF_TOKEN) else putString(KEY_HF_TOKEN, token.trim())
        }.apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
