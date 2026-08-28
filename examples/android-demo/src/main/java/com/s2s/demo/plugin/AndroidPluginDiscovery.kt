package com.s2s.demo.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import com.s2s.host.core.DiscoveredPlugin
import com.s2s.host.core.PluginAvailability
import com.s2s.host.core.PluginConfigField
import com.s2s.host.core.PluginDescriptor
import com.s2s.host.core.PluginDiscovery
import com.s2s.host.core.PluginEntryPoint
import com.s2s.host.core.PluginSource
import com.s2s.host.core.PluginType
import java.security.MessageDigest

/**
 * Finds Jarvis plugins among the Android packages installed on the device.
 *
 * Reads manifest metadata and signing certificates through [PackageManager]
 * only — it never binds the service, never starts the app, and never loads
 * a class from it. Metadata inspection must not execute plugin code, and on
 * Android this is how you honor that: a malicious package can lie in its
 * manifest, but it cannot run anything during discovery.
 *
 * Lives in the Android app layer, not s2s-host: [PluginDiscovery] is the
 * platform-agnostic seam, and everything Android-specific (PackageManager,
 * signatures, intent actions) stays on this side of it.
 */
class AndroidPluginDiscovery(private val context: Context) : PluginDiscovery {

    /**
     * Every plugin on the device, of any capability type.
     *
     * One generic action, and the capability comes from metadata. The
     * alternative — an action per capability type — meant a new type
     * required editing this file AND the host manifest's `<queries>`, which
     * is exactly the hardcoding a plugin platform exists to avoid. Now a
     * plugin declaring a type this build has never heard of is still found
     * and listed; it simply has no adapter to use it with.
     *
     * The legacy per-type actions are still queried so plugins built
     * against the earlier contract keep working — an installed plugin must
     * not stop being discoverable because the host changed its convention.
     */
    override fun discover(): List<DiscoveredPlugin> {
        val pm = context.packageManager
        return (listOf(PLUGIN_ACTION) + LEGACY_ACTIONS)
            .flatMap { action -> queryServices(pm, action) }
            .distinctBy { it.descriptor.pluginId }
    }

    private fun queryServices(pm: PackageManager, action: String): List<DiscoveredPlugin> {
        val intent = Intent(action)
        val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }

        return services.mapNotNull { resolve ->
            runCatching { toDiscovered(pm, resolve.serviceInfo) }
                .onFailure { Log.w(TAG, "Ignoring malformed plugin package ${resolve.serviceInfo?.packageName}", it) }
                .getOrNull()
        }
    }

    private fun toDiscovered(pm: PackageManager, service: ServiceInfo): DiscoveredPlugin? {
        val meta = service.metaData ?: return null
        val pluginId = meta.getString(META_ID)?.takeIf { it.isNotBlank() } ?: return null
        val version = meta.getString(META_VERSION)?.takeIf { it.isNotBlank() } ?: return null
        val typeName = meta.getString(META_TYPE)?.takeIf { it.isNotBlank() } ?: return null

        // Any declared type is accepted, including one this build has never
        // heard of. Previously an unknown type was dropped here, which meant
        // a plugin for a newer host was invisible rather than visible-and-
        // unsupported — the user could not even see that it was installed.
        // Whether the host can USE it is decided later, by whether an
        // adapter exists.
        val type = PluginType.of(typeName)

        val descriptor = PluginDescriptor(
            pluginId = pluginId,
            type = type,
            displayName = meta.getString(META_NAME) ?: pluginId,
            version = version,
            minHostApiVersion = meta.getInt(META_MIN_API, 1),
            source = PluginSource.EXTERNAL,
            availability = PluginAvailability.EXTERNAL_SERVICE,
            requiredPermissions = meta.getString(META_PERMISSIONS)
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            entryPoint = PluginEntryPoint.boundService(service.packageName, service.name),
            configSchema = parseConfigSchema(meta.getString(META_CONFIG_SCHEMA)),
            description = meta.getString(META_DESCRIPTION).orEmpty(),
        )

        return DiscoveredPlugin(
            descriptor = descriptor,
            identity = signingIdentity(pm, service.packageName) ?: return null,
            sourceLabel = "Installed app: ${service.packageName}",
        )
    }

    /** Format: `key|Label|TYPE|required|help` per field, `;`-separated. Deliberately not JSON — manifest metadata is a flat string, and a parse failure here must degrade to "no config form", never crash discovery. */
    private fun parseConfigSchema(raw: String?): List<PluginConfigField> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size < 2) return@mapNotNull null
            runCatching {
                PluginConfigField(
                    key = parts[0].trim(),
                    label = parts[1].trim(),
                    type = parts.getOrNull(2)?.trim()?.uppercase()
                        ?.let { t -> runCatching { PluginConfigField.Type.valueOf(t) }.getOrNull() }
                        ?: PluginConfigField.Type.TEXT,
                    required = parts.getOrNull(3)?.trim()?.toBooleanStrictOrNull() ?: true,
                    help = parts.getOrNull(4)?.trim().orEmpty(),
                )
            }.getOrNull()
        }
    }

    /**
     * SHA-256 of the package's signing certificate — the identity Jarvis
     * pins at install time. If a package with the same name later appears
     * signed by someone else, [com.s2s.host.core.PluginManager] refuses to
     * re-register it rather than trusting the name alone.
     */
    private fun signingIdentity(pm: PackageManager, packageName: String): String? = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } ?: return null

        val digest = MessageDigest.getInstance("SHA-256")
        signatures.joinToString(":") { sig ->
            digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    private companion object {
        const val TAG = "PluginDiscovery"

        /**
         * The one action every s2s plugin declares, whatever it provides.
         *
         * Capability type lives in metadata instead, so a new type needs no
         * change here and no change to the host manifest. Android's package
         * visibility requires the host to name actions at build time (there
         * is no wildcard), so exactly one generic action is the furthest
         * this can be de-hardcoded — and it is enough.
         */
        const val PLUGIN_ACTION = "com.s2s.plugin.action.PLUGIN"

        /** Earlier per-capability actions, still queried so already-installed plugins keep working. */
        val LEGACY_ACTIONS = listOf(
            "com.s2s.plugin.action.TOOL_PLUGIN",
            "com.s2s.plugin.action.TEXT_NORMALIZER_PLUGIN",
        )
        const val META_ID = "com.s2s.plugin.id"
        const val META_NAME = "com.s2s.plugin.displayName"
        const val META_DESCRIPTION = "com.s2s.plugin.description"
        const val META_VERSION = "com.s2s.plugin.version"
        const val META_TYPE = "com.s2s.plugin.type"
        const val META_MIN_API = "com.s2s.plugin.minHostApiVersion"
        const val META_PERMISSIONS = "com.s2s.plugin.requiredPermissions"
        const val META_CONFIG_SCHEMA = "com.s2s.plugin.configSchema"
    }
}
