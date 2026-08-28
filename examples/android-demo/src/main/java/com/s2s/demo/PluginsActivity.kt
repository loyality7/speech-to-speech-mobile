package com.s2s.demo

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.s2s.demo.plugin.JarvisRuntimeHolder
import com.s2s.host.core.DiscoveredPlugin
import com.s2s.host.core.PluginConfig
import com.s2s.host.core.PluginConfigField
import com.s2s.host.core.PluginDescriptor
import com.s2s.host.core.PluginInstallException
import com.s2s.host.core.PluginManager
import com.s2s.host.core.PluginRegistry
import com.s2s.host.core.PluginSource
import com.s2s.host.core.PluginState
import com.s2s.host.core.PluginType

/**
 * The Plugins screen: install, enable, configure, select, uninstall.
 *
 * Contains no knowledge of any specific plugin. Every row is rendered from
 * [PluginDescriptor] metadata, and every settings form is generated from
 * [PluginDescriptor.configSchema] — which is why installing a brand-new
 * plugin needs no change to this file. It shows display names ("Echo Test
 * Tools"), never class names.
 */
class PluginsActivity : Activity() {

    private lateinit var container: LinearLayout
    private val manager: PluginManager get() = JarvisRuntimeHolder.get(applicationContext).pluginManager
    private val registry: PluginRegistry get() = JarvisRuntimeHolder.get(applicationContext).registry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        scroll.addView(container)
        setContentView(scroll)
        render()
    }

    override fun onResume() {
        super.onResume()
        // A plugin APK can be installed or removed by the OS while this
        // screen is backgrounded — re-read on every resume rather than
        // trusting a snapshot.
        render()
    }

    private fun render() {
        container.removeAllViews()
        container.addView(heading("Plugins"))
        container.addView(
            body(
                "Jarvis capabilities come from plugins. Bundled plugins ship inside this app; " +
                    "others are separate apps you install, which run in their own sandbox.",
            ),
        )

        val installedRecords = manager.installed().associateBy { it.pluginId }
        val known = registry.list().sortedWith(compareBy({ it.type.id }, { it.displayName }))

        container.addView(sectionHeading("Installed"))
        if (known.isEmpty()) {
            container.addView(body("No plugins registered."))
        } else {
            known.forEach { descriptor ->
                container.addView(pluginCard(descriptor, installedRecords.containsKey(descriptor.pluginId)))
            }
        }

        // Anything discoverable on the device that has NOT been installed yet.
        val available = manager.available().filter { registry.find(it.descriptor.pluginId) == null }
        container.addView(sectionHeading("Available to install"))
        if (available.isEmpty()) {
            container.addView(
                body(
                    "No new plugin apps found on this device.\n\n" +
                        "Install a Jarvis plugin app (an APK that declares the Jarvis plugin service), " +
                        "then return here — it will appear in this list.",
                ),
            )
        } else {
            available.forEach { container.addView(availableCard(it)) }
        }
    }

    // ── Cards ───────────────────────────────────────────────────────────

    private fun pluginCard(descriptor: PluginDescriptor, isExternalInstall: Boolean): View {
        val card = card()
        card.addView(title(descriptor.displayName))
        if (descriptor.description.isNotBlank()) card.addView(body(descriptor.description))

        val state = registry.state(descriptor.pluginId)
        val selected = registry.getSelected(descriptor.type) == descriptor.pluginId
        val composable = registry.canCompose(descriptor.pluginId, grantedPermissions = grantedPermissions(descriptor))

        card.addView(
            meta(
                buildString {
                    append(typeLabel(descriptor.type)).append(" · v").append(descriptor.version)
                    append(" · ").append(sourceLabel(descriptor.source))
                    appendLine()
                    append(statusLine(state, selected, composable, descriptor))
                },
            ),
        )

        if (descriptor.requiredPermissions.isNotEmpty()) {
            val missing = descriptor.requiredPermissions - grantedPermissions(descriptor)
            card.addView(
                meta(
                    "Permissions: " + descriptor.requiredPermissions.joinToString(", ") { permissionLabel(it) } +
                        if (missing.isEmpty()) " (granted)" else " (NOT granted — cannot run)",
                ),
            )
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val enabled = registry.isEnabled(descriptor.pluginId)

        actions.addView(
            actionButton(if (enabled) "Disable" else "Enable") {
                if (enabled) manager.disable(descriptor.pluginId) else manager.enable(descriptor.pluginId)
                toast(if (enabled) "Disabled" else "Enabled — takes effect next time Jarvis starts")
                render()
            },
        )

        if (descriptor.configSchema.isNotEmpty()) {
            actions.addView(actionButton("Configure") { showConfigDialog(descriptor) })
        }

        if (!selected && enabled) {
            actions.addView(
                actionButton("Select") {
                    manager.select(descriptor.pluginId, descriptor.type)
                    toast("Selected for ${typeLabel(descriptor.type)}")
                    render()
                },
            )
        }

        // A plugin may ship its own screen — typically to download or manage
        // whatever assets it owns (a model, credentials). The host offers a
        // way in without knowing what the screen does: it just launches the
        // plugin package's own launcher activity.
        pluginLaunchIntent(descriptor)?.let { intent ->
            actions.addView(actionButton("Open settings") { startActivity(intent) })
        }

        if (isExternalInstall) {
            actions.addView(
                actionButton("Uninstall") {
                    manager.uninstall(descriptor.pluginId)
                        .onSuccess {
                            toast("Removed from Jarvis. The plugin app itself is still installed on the device.")
                            render()
                        }
                        .onFailure { toast(it.message ?: "Could not uninstall") }
                },
            )
        }

        card.addView(actions)
        return card
    }

    private fun availableCard(found: DiscoveredPlugin): View {
        val d = found.descriptor
        val card = card()
        card.addView(title(d.displayName))
        if (d.description.isNotBlank()) card.addView(body(d.description))
        card.addView(meta("${typeLabel(d.type)} · v${d.version}\n${found.sourceLabel}"))

        // A plugin may declare a capability this build has no adapter for —
        // a plugin written for a newer host. Saying so up front is better
        // than letting Install fail: the user can see it is installed and
        // recognised, just not usable yet.
        if (!isSupportedType(d.type)) {
            card.addView(
                meta("Not supported by this version of the app — it provides \"${d.type}\", which this build cannot use yet."),
            )
            return card
        }

        card.addView(
            actionButton("Install") { confirmInstall(found) },
        )
        return card
    }

    /** Whether this build has an adapter that can actually talk to [type]. Discovery accepts any type; using one needs its contract. */
    private fun isSupportedType(type: PluginType): Boolean =
        type == PluginType.TOOLS || type == PluginType.SPEECH_TEXT_NORMALIZER

    /** Install is a consent step: capabilities and permissions are shown BEFORE anything is registered. */
    private fun confirmInstall(found: DiscoveredPlugin) {
        val d = found.descriptor
        val details = buildString {
            appendLine(d.description.ifBlank { "No description provided." })
            appendLine()
            appendLine("Type: ${typeLabel(d.type)}")
            appendLine("Version: ${d.version}")
            appendLine("From: ${found.sourceLabel}")
            appendLine()
            if (d.requiredPermissions.isEmpty()) {
                appendLine("Requests no Android permissions.")
            } else {
                appendLine("Requests these permissions:")
                d.requiredPermissions.forEach { appendLine(" • ${permissionLabel(it)}") }
            }
            appendLine()
            append("Runs in its own app sandbox, not inside Jarvis.")
        }

        AlertDialog.Builder(this)
            .setTitle("Install ${d.displayName}?")
            .setMessage(details)
            .setPositiveButton("Install") { _, _ ->
                val provider = runCatching {
                    JarvisRuntime.providerFor(applicationContext, found, emptyMap())
                }.getOrElse {
                    toast(it.message ?: "Unsupported plugin type")
                    return@setPositiveButton
                }
                manager.install(found, provider)
                    .onSuccess { toast("Installed. Enable it to start using it.") }
                    .onFailure { e ->
                        val failure = (e as? PluginInstallException)?.failure
                        toast("Install failed: ${failure ?: e.message}")
                    }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Generic settings form, generated from the plugin's declared schema. No per-plugin screen exists. */
    private fun showConfigDialog(descriptor: PluginDescriptor) {
        val existing = registry.getConfig(descriptor.pluginId)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val inputs = descriptor.configSchema.associateWith { field ->
            form.addView(
                TextView(this).apply {
                    text = field.label + if (field.required) " *" else ""
                    setTextColor(Color.BLACK)
                    setPadding(0, 12, 0, 0)
                },
            )
            if (field.help.isNotBlank()) form.addView(meta(field.help))
            EditText(this).apply {
                setText(existing[field.key] ?: field.defaultValue.orEmpty())
                inputType = when (field.type) {
                    PluginConfigField.Type.SECRET -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    PluginConfigField.Type.NUMBER -> InputType.TYPE_CLASS_NUMBER
                    else -> InputType.TYPE_CLASS_TEXT
                }
                form.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }

        AlertDialog.Builder(this)
            .setTitle(descriptor.displayName)
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                val values = existing.values.toMutableMap()
                inputs.forEach { (field, input) ->
                    val text = input.text.toString()
                    if (text.isBlank()) values.remove(field.key) else values[field.key] = text
                }
                manager.configure(descriptor.pluginId, PluginConfig(values))
                toast("Saved — takes effect next time Jarvis starts")
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Labels ──────────────────────────────────────────────────────────

    private fun statusLine(state: PluginState, selected: Boolean, composable: Boolean, d: PluginDescriptor): String {
        val parts = mutableListOf<String>()
        parts += when (state) {
            PluginState.DISABLED -> "Disabled"
            PluginState.FAILED -> "Unavailable"
            PluginState.DISCOVERED -> "Not registered"
            PluginState.ENABLED, PluginState.CONFIGURED -> "Enabled"
        }
        if (selected) parts += "Selected"
        if (state != PluginState.DISABLED && !composable) {
            parts += if (d.requiredPermissions.isNotEmpty()) "Needs permission" else "Cannot run"
        }
        if (d.configSchema.any { it.required } && registry.getConfig(d.pluginId).values.isEmpty()) {
            parts += "Needs configuration"
        }
        return parts.joinToString(" · ")
    }

    /**
     * Human label for a capability type.
     *
     * Falls back to a tidied version of the declared id rather than
     * requiring every possible type to be known here — a plugin for a
     * newer host still gets a readable label ("Calendar access") instead of
     * being unlistable.
     */
    private fun typeLabel(type: PluginType) = when (type) {
        PluginType.LANGUAGE_MODEL -> "Language model"
        PluginType.CONTEXT_ENGINE -> "Memory"
        PluginType.TOOLS -> "Tools"
        PluginType.SPEECH_TEXT_NORMALIZER -> "Speech cleanup"
        else -> type.id.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun sourceLabel(source: PluginSource) = when (source) {
        PluginSource.BUILT_IN -> "Built in"
        PluginSource.BUNDLED -> "Bundled with Jarvis"
        PluginSource.EXTERNAL -> "Installed app"
        PluginSource.MCP -> "MCP server"
        PluginSource.USER_CONFIGURED -> "User configured"
    }

    /** Turns an Android permission string into something a person can read, without a hardcoded table per plugin. */
    private fun permissionLabel(permission: String) =
        permission.substringAfterLast('.').lowercase().replace('_', ' ')

    /**
     * The plugin package's own launcher activity, if it has one.
     *
     * Null for a bundled plugin (no separate package) or an external plugin
     * with no UI. Resolved through PackageManager rather than declared in
     * metadata, because an app's launcher intent is something Android
     * already knows — asking the plugin to repeat it would just be another
     * field to get wrong.
     */
    private fun pluginLaunchIntent(descriptor: PluginDescriptor): android.content.Intent? {
        val address = descriptor.entryPoint.address.takeIf { it.isNotBlank() } ?: return null
        val packageName = address.substringBefore('/').takeIf { it.isNotBlank() } ?: return null
        if (packageName == this.packageName) return null
        return runCatching { packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
    }

    private fun grantedPermissions(descriptor: PluginDescriptor): Set<String> =
        descriptor.requiredPermissions
            .filter { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            .toSet()

    // ── Tiny view helpers ───────────────────────────────────────────────

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 20, 24, 20)
        setBackgroundColor(Color.parseColor("#F2F2F2"))
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14 }
        layoutParams = lp
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 22f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.BLACK)
    }

    private fun sectionHeading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.DKGRAY)
        setPadding(0, 28, 0, 0)
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.BLACK)
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.DKGRAY)
        setPadding(0, 4, 0, 0)
    }

    private fun meta(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.GRAY)
        setPadding(0, 6, 0, 0)
    }

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = 2
            marginEnd = 2
            topMargin = 10
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
