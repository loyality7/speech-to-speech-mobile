# S2S Plugin Template

A generic starting point for building an installable plugin against
`speech-to-speech-mobile`'s core contracts (`LanguageModel`, `ContextEngine`,
`Tools`, and whatever future contracts core adds). Copy this whole directory
to start a new plugin repo — do not use it as-is inside `speech-to-speech-mobile`.

This template ships one example capability (a trivial `ContextEngine`), to
show the pattern. For a `Tools`-specific template with a stateful example, see
`../s2s-tools-plugin-template`.

## Verifying this template actually compiles, right now

`speech-to-speech-mobile` has not published a tag with the current
`LanguageModel`/`ContextEngine`/`Tools` contracts yet, so
`com.github.loyality7:speech-to-speech-mobile:main-SNAPSHOT` in
`plugin/build.gradle.kts` is not resolvable through JitPack today. Rather than
ship a template that only *looks* correct, `settings.gradle.kts` substitutes
that coordinate for the live `:bindings:android` module via `includeBuild("../../")`
— so this template compiles and tests against the actual current core source
whenever it's run from inside the `speech-to-speech-mobile` checkout:

```bash
# from the speech-to-speech-mobile repo root
./gradlew --project-dir templates/s2s-plugin-template :plugin:compileDebugKotlin
./gradlew --project-dir templates/s2s-plugin-template :plugin:testDebugUnitTest
```

**This `includeBuild` is local-development scaffolding, not part of the
pattern to keep.** A real plugin lives in its own separate repo with no
`../../` to point at — it depends on a published JitPack tag from the start.
Delete the `includeBuild` block once core has a tag your plugin can target.

## What a plugin is

```text
CORE    = CONTRACT + ORCHESTRATION      (speech-to-speech-mobile)
PLUGIN  = IMPLEMENTATION                (this template, once copied and renamed)
HOST    = COMPOSITION                   (the app that depends on both)
```

A plugin:

1. depends on `speech-to-speech-mobile` for the contract it implements
2. implements one (usually) core interface — `LanguageModel`, `ContextEngine`, or `Tools`
3. owns its own configuration type — never adds fields to `S2SConfig`
4. exposes a plain constructor or factory — no magic discovery
5. never requires a change to `speech-to-speech-mobile` to exist
6. is independently publishable through JitPack

Adding a new LLM provider, memory backend, or tool bundle should never require
touching core. If it does, the boundary is wrong — see core's own
`pipeline/*.kt` for what the stable contracts actually are.

## Installation vs activation

```text
dependency installed (Gradle/JitPack)
        ↓
plugin available on the classpath
        ↓
host constructs and configures it
        ↓
host injects it into S2SEngine
        ↓
plugin is active
```

Being on the classpath does **not** make a plugin active. Nothing in core
scans for or auto-discovers plugins — the host always explicitly constructs
one and passes it to `S2SEngine`'s constructor. This is deliberate: no
service-locator, no reflection, no surprise about which implementation is
running.

## What's in this template

```text
s2s-plugin-template/
├── README.md                      — this file
├── settings.gradle.kts            — JitPack repo wiring, matches core's own
├── build.gradle.kts                — root plugin block
└── plugin/
    ├── build.gradle.kts            — depends on speech-to-speech-mobile
    └── src/
        ├── main/java/com/example/s2splugin/
        │   ├── S2SPlugin.kt            — identity/metadata interface (id, version, capabilities)
        │   └── ExampleContextPlugin.kt — example ContextEngine implementation + its own config
        └── test/java/com/example/s2splugin/
            └── ExampleContextPluginTest.kt
```

`S2SPlugin` is intentionally tiny — identity, version, declared capabilities.
It is **not** where `generate()`/`executeTool()`/`remember()` live; those stay
on the actual core interface (`LanguageModel`, `Tools`, `ContextEngine`) your
plugin implements. One plugin, one core interface, is the common case — don't
reach for multi-capability plugins unless you actually need one.

## Building your own plugin from this template

Worked example: "I want to create `MyContextPlugin`."

1. Copy this directory to a new repo, e.g. `my-context-plugin`.
2. In `settings.gradle.kts`, rename `rootProject.name`.
3. In `plugin/build.gradle.kts`:
   - rename `android.namespace`
   - set `groupId`/`artifactId` to your GitHub username/repo name
   - keep the `speech-to-speech-mobile` dependency; pin it to a real tag once one exists
4. Rename `ExampleContextPlugin.kt` → `MyContextPlugin.kt`, implement `ContextEngine` (or `LanguageModel`, or `Tools`) for real.
5. Add `MyContextConfig` — your own config data class. Never add fields to core's `S2SConfig`.
6. Keep (or write your own) `S2SPlugin` identity: `id = "my-context-plugin"`, `version`, `capabilities = listOf("ContextEngine")`.
7. Write tests against your implementation directly — no Android instrumentation needed for a pure-Kotlin capability.
8. Push to GitHub, tag a release (e.g. `v0.1.0`). JitPack builds it on first resolution — no separate publish step.
9. Host app adds:
   ```kotlin
   dependencies {
       implementation("com.github.YOUR_USERNAME:speech-to-speech-mobile:1.0.3")
       implementation("com.github.YOUR_USERNAME:my-context-plugin:v0.1.0")
   }
   ```
10. Host constructs and injects it — see below.

## Host composition example

```kotlin
val languageModel: LanguageModel = MyLanguageModelPlugin(MyLanguageModelConfig(...))
val contextEngine: ContextEngine = MyContextPlugin(MyContextConfig(systemPrompt = "..."))
val tools: Tools = MyToolsPlugin.registerAll(context)   // see the tools template

val engine = S2SEngine(
    context = androidContext,
    config = S2SConfig(models = ModelPaths(...)),
    languageModel = languageModel,
    history = contextEngine,   // ContextEngine substitutes for the default ChatHistory
    tools = tools,
)
```

The host chooses every implementation. `S2SEngine` never constructs a
concrete `LanguageModel`, `ContextEngine`, or `Tools` on its own — see
`S2SEngine`'s constructor in core: those parameters have no default pointing
at a concrete class from this plugin or any other.

## Testing strategy

- Pure-Kotlin capability implementations (most `ContextEngine`/`LanguageModel`
  logic) test on the JVM with plain JUnit — no Android dependency, no
  instrumentation, no emulator. See `ExampleContextPluginTest.kt`.
- Anything touching a real Android API (WebView, native JNI, hardware) needs
  instrumented tests in the plugin's own repo — that's the plugin's
  responsibility, not core's.
- Core proves *its* side of the contract with fakes (`FakeLanguageModel`,
  `FakeContextEngine`) in `speech-to-speech-mobile`'s own test suite — a
  plugin does not need to duplicate that, only prove its own implementation
  is internally correct.

## Publishing through JitPack

Same mechanism `speech-to-speech-mobile` itself uses — no separate
infrastructure:

1. Push commits to GitHub.
2. Tag a release (`git tag v0.1.0 && git push --tags`), or let a host resolve
   `main-SNAPSHOT` during development.
3. JitPack builds on first resolution by any consumer — nothing to run manually.
4. Consumers add `maven { url = uri("https://jitpack.io") }` to their
   `dependencyResolutionManagement` (already present in any project that
   depends on `speech-to-speech-mobile`, since core itself resolves through it).
