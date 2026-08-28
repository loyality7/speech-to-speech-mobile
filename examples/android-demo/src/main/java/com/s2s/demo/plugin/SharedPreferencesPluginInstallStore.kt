package com.s2s.demo.plugin

import android.content.Context
import com.s2s.host.core.PluginInstallStore
import com.s2s.host.core.PluginInstallation
import org.json.JSONObject

/**
 * Persists which external plugins the user has installed, so an installed
 * plugin is still installed after the process dies.
 *
 * Mirrors the existing `SharedPreferencesPluginConfigStore` rather than
 * introducing a database: this is a handful of small records, and the two
 * stores deliberately stay separate because they have different lifetimes —
 * uninstalling drops the installation record while config may survive for a
 * later reinstall.
 *
 * One JSON object per plugin, keyed by plugin ID. JSON rather than the
 * delimited-string encoding the config store originally used, because a
 * source label or package name can contain almost anything.
 */
class SharedPreferencesPluginInstallStore(context: Context) : PluginInstallStore {

    private val prefs = context.applicationContext.getSharedPreferences("s2s_plugin_installs", Context.MODE_PRIVATE)

    override fun list(): List<PluginInstallation> =
        prefs.all.keys.mapNotNull { get(it) }.sortedBy { it.installedAtMs }

    override fun get(pluginId: String): PluginInstallation? {
        val raw = prefs.getString(pluginId, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PluginInstallation(
                pluginId = json.getString("pluginId"),
                installedVersion = json.getString("installedVersion"),
                sourceLabel = json.optString("sourceLabel"),
                verifiedIdentity = json.getString("verifiedIdentity"),
                installedAtMs = json.optLong("installedAtMs"),
            )
        }.getOrNull()
    }

    override fun put(installation: PluginInstallation) {
        val json = JSONObject().apply {
            put("pluginId", installation.pluginId)
            put("installedVersion", installation.installedVersion)
            put("sourceLabel", installation.sourceLabel)
            put("verifiedIdentity", installation.verifiedIdentity)
            put("installedAtMs", installation.installedAtMs)
        }
        prefs.edit().putString(installation.pluginId, json.toString()).apply()
    }

    override fun remove(pluginId: String) {
        prefs.edit().remove(pluginId).apply()
    }
}
