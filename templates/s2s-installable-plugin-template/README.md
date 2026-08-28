# S2S Installable Plugin Template

Template for a plugin the user **installs as a separate Android app** and
the host app discovers at runtime — no change to any host source, no rebuild
of the host APK.

This is a different delivery model from the other two templates in this
directory. Pick the right one:

| Template | Plugin is… | Who compiles it in | Adding one requires |
|---|---|---|---|
| `s2s-plugin-template` | a Gradle **library module** | the host app | rebuilding the host |
| `s2s-tools-plugin-template` | a Gradle **library module** of tools | the host app | rebuilding the host |
| **this one** | a standalone **APK** | nobody — it's its own app | installing the APK |

Use this template when the plugin should be installable/updatable/removable
independently of the host. Use the others for first-party capabilities that
genuinely ship inside the host app.

## Why a separate APK and not a downloaded JAR

Android 14 (`targetSdk 34`) enforces W^X: an app cannot load executable code
from storage it can write to. A downloaded `.jar`/`.dex` therefore cannot be
loaded into the host process at all.

Binding to a service in another installed package is the supported
mechanism — and it is the safer one regardless:

- the plugin runs in **its own process**, under **its own uid**, with **its
  own** manifest permissions
- it does **not** inherit the host's permissions (microphone, notifications, …)
- the host can survive the plugin crashing; a crash in in-process plugin code
  would take the host down with it

Nothing in this template loads code into the host. Host and plugin
exchange strings over AIDL.

## What the host needs from you

Exactly three things. Nothing else is shared — this template deliberately
depends on **no** s2s artifact.

### 1. The AIDL contract

Copy `plugin/src/main/aidl/com/s2s/plugin/api/IS2SToolPlugin.aidl`
verbatim, at that exact path/package, and enable AIDL:

```kotlin
android {
    buildFeatures { aidl = true }
}
```

The file must be byte-identical to the host's copy — AIDL matches by
interface descriptor, so a modified copy simply fails to bind.

### 2. Manifest metadata

The host discovers plugins by querying `PackageManager` for services with the
`com.s2s.plugin.action.TOOL_PLUGIN` action, then reads the metadata below.

**Discovery never executes your code** — the host reads manifest entries and
your signing certificate, and binds only after the user installs your
plugin.

```xml
<service android:name=".ExampleToolPluginService" android:exported="true">
    <intent-filter>
        <action android:name="com.s2s.plugin.action.TOOL_PLUGIN" />
    </intent-filter>

    <meta-data android:name="com.s2s.plugin.id"                android:value="com.example.jarvisplugin.example" />
    <meta-data android:name="com.s2s.plugin.displayName"       android:value="Example Tools" />
    <meta-data android:name="com.s2s.plugin.description"       android:value="Shown on the plugin card and the install dialog." />
    <meta-data android:name="com.s2s.plugin.version"           android:value="1.0.0" />
    <meta-data android:name="com.s2s.plugin.type"              android:value="TOOLS" />
    <meta-data android:name="com.s2s.plugin.minHostApiVersion" android:value="1" />
    <meta-data android:name="com.s2s.plugin.requiredPermissions" android:value="" />
    <meta-data android:name="com.s2s.plugin.configSchema"
        android:value="apiKey|API key|SECRET|true|Where to find it" />
</service>
```

| Key | Meaning |
|---|---|
| `id` | Stable, permanent. The host keys install/config/selection on it. Changing it looks like a different plugin. |
| `type` | `TOOLS` today. `LANGUAGE_MODEL`/`CONTEXT_ENGINE`/`SPEECH_TEXT_NORMALIZER` exist in the host's `PluginType` but have **no IPC contract yet** — the host rejects them rather than half-composing. |
| `minHostApiVersion` | The host refuses a plugin that needs a newer host than itself. |
| `requiredPermissions` | Comma-separated Android permissions **the host** must hold. Declaring one does **not** grant it — The host shows it at install and refuses to compose the plugin until it's actually granted. |
| `configSchema` | `key\|Label\|TYPE\|required\|help`, `;`-separated. The host renders a settings form from this; it never learns what a key means. Types: `TEXT`, `SECRET`, `FILE_PATH`, `NUMBER`, `BOOLEAN`, `CHOICE`. |

`SECRET` fields are masked in the UI and kept out of logs/traces. That is a
display guarantee, not encryption at rest.

### 3. The service implementation

Three methods. See `ExampleToolPluginService.kt`.

```kotlin
override fun apiVersion(): Int = 1

/** Metadata only. Must not execute anything. */
override fun toolDefinitionsJson(): String =
    """[{"name":"my_tool","description":"…","parameters":{"arg":"what it is"}}]"""

/** Called on a binder thread — do not assume the main thread. */
override fun execute(toolName: String?, argumentsJson: String?): String =
    """{"output":"…","isError":false}"""
```

Your configured values arrive inside `argumentsJson`, prefixed `__config_`
(so `apiKey` arrives as `__config_apiKey`) and cannot collide with a real
tool argument.

Return `{"isError": true}` for a failure — do not throw across the binder.
The host turns an IPC failure into an error `ToolResult` and keeps running;
throwing just makes the error message worse.

## Install flow the user sees

```text
User installs your plugin APK (Play Store, adb, sideload)
       ↓
Host app → Plugins → "Available to install"        (PackageManager query)
       ↓
Install → dialog shows name, description, version, permissions
       ↓
User confirms                                       (never auto-installed)
       ↓
Host verifies: id/version present, host API compatible,
                 signing certificate recorded
       ↓
INSTALLED  (registered, but deliberately NOT enabled)
       ↓
Enable → Configure → Select
       ↓
Composed into the running host on next start
```

Install and enable are separate on purpose: freshly-installed third-party
code is never silently activated.

## Security you should know about

- **Identity is pinned at install.** The host records your signing
  certificate digest. If a package with the same name later appears signed
  by someone else, the host refuses it and drops the installation rather than
  trusting the package name.
- **Discovery is metadata-only.** Your manifest can claim anything; claiming
  it does not run anything.
- **You get your own permissions, not the host's.** If your plugin needs
  contacts, declare it in your own manifest — and understand the host will not
  compose you until the host also holds anything you list in
  `requiredPermissions`.
- **The host owns tool-call parsing.** `Tools.parse` stays host-side; a plugin
  supplies capabilities, never the wire format the model speaks. Letting an
  installed package redefine parsing would give it control over every other
  plugin's dispatch.
- **No sandbox beyond Android's.** The process/uid boundary is Android's own.
  There is no additional resource limiting or syscall filtering.

## Working example

`examples/test-plugin/` in this repo is a complete, buildable plugin (~70
lines) used to prove the platform end to end. It depends on nothing from any
s2s repo — only the AIDL file — which is exactly the property that shows
the host has no hardcoded knowledge of it.

## Current limitations

- **`TOOLS` only.** LLM, context and speech-normalizer plugins need their
  own AIDL surface (streaming tokens, cancellation) — not designed yet.
- **No dependency resolution.** The descriptor can express a version; there
  is no resolver for "plugin A needs plugin B".
- **No plugin repository.** Discovery is "already installed on this device".
  Browsing/downloading from a catalogue is not built.
- **Config changes apply on next host start**, not live.
