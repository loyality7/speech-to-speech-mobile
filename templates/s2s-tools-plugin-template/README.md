# S2S Tools Plugin Template

A dedicated template for building a `Tools` plugin against
`speech-to-speech-mobile` — the pattern the future `s2s-tools` repo (wrapping
`fetch` and `webdroid`) will follow. For the generic single-capability
template (LLM, Context, or a simpler tool set), see `../s2s-plugin-template`.

## The real tool execution flow

This is what actually happens inside `S2SEngine` — the template's examples
are built to match this exactly, not a conceptual approximation of it:

```text
User turn
   ↓
S2SEngine.generate()
   ↓
LanguageModel.generate() — model replies with a flat-JSON tool call
   ↓
Tools.parse(text) → ToolCall?
   ↓
S2SEngine.runTool() builds a ToolContext(sessionId, turnId, callId)
   ↓
Tools.execute(call, context) → ToolResult
   ↓
ToolRegistry looks up the registered ToolFunction and calls
ToolFunction.invoke(context, arguments) → String
   ↓
ToolResult fed back into conversation history
   ↓
LanguageModel.generate() again, now with the tool result in context
```

`ToolContext` is engine-generated — `sessionId` is stable for the life of one
`S2SEngine` (or restored, if the host passed one in), `turnId` comes from the
turn counter, `callId` increments per tool invocation. A tool never invents
any of these itself.

## What's in this template

```text
s2s-tools-plugin-template/
├── README.md
├── settings.gradle.kts        — JitPack wiring + local includeBuild (see below)
├── build.gradle.kts
├── gradle.properties
└── tools/
    ├── build.gradle.kts        — depends on speech-to-speech-mobile
    └── src/
        ├── main/java/com/example/s2stools/
        │   ├── CalculatorTool.kt   — stateless example
        │   └── BrowserTool.kt      — stateful example, session-isolated
        └── test/java/com/example/s2stools/
            └── BrowserToolTest.kt  — proves session isolation
```

### CalculatorTool — stateless

A pure function of its arguments. Ignores the `ToolContext` it receives
entirely — most tools should look like this. Registered with
`CalculatorTool.registerOn(tools)`.

### BrowserTool — stateful, session-isolated

Stands in for a real webdroid-backed browsing tool: `browse_open` →
`browse_click` → `browse_read`, where each call after `open` must act on the
same page `open` created — but only within the *same conversation*. It keys
its internal page map on `ToolContext.sessionId`, never on anything the tool
invents, so two concurrent conversations never see each other's open page.

`BrowserToolTest.kt` proves this against the real `ToolRegistry` and
`ToolContext` from core — not a mock — with three tests: two sessions with
different open pages stay isolated, calling `browse_click` before
`browse_open` fails cleanly instead of touching another session's state, and
`BrowserTool.closeSession(sessionId)` actually frees that session's state.

## Building your own tools plugin (the fetch/webdroid case)

The real `s2s-tools` repo wraps two existing, S2S-unaware libraries:

```text
fetch / webdroid  (generic, no knowledge of S2S)
        ↓
s2s-tools adapter  (this template, copied and renamed)
        ↓
Tools contract  (ToolDefinition / ToolFunction / ToolContext / ToolResult)
        ↓
S2SEngine
```

1. Copy this directory to a new repo, e.g. `s2s-tools`.
2. In `tools/build.gradle.kts`, add the generic library as a dependency:
   ```kotlin
   implementation("com.github.loyality7:fetch:vX.Y.Z")
   implementation("com.github.loyality7:webdroid:vX.Y.Z")
   ```
   Never the other direction — `fetch`/`webdroid` must never depend on this
   plugin or on `speech-to-speech-mobile`.
3. Write one adapter object per tool (or tool family), following `BrowserTool.kt`'s
   shape for anything stateful: key state on `context.sessionId`, nothing else.
4. For a slow tool (a real page fetch, a WebView render), `ToolFunction.invoke`
   is still synchronous per core's current contract — block the worker thread
   `S2SEngine` already runs tool calls on rather than spawning your own; core
   already isolates this off the main/audio thread. Bridge a coroutine API
   (like webdroid's) with `runBlocking` inside your adapter if needed.
5. Provide one registration entry point, e.g. `WebTools.registerAll(tools, context)`,
   so a host installs everything with one call — or expose per-tool
   `registerOn(tools)` functions (as this template does) so a host can
   cherry-pick.
6. Test against the real `ToolRegistry` and hand-built `ToolContext` instances,
   exactly like `BrowserToolTest.kt` — no need to mock core.
7. Publish through JitPack (see the generic template's README — identical steps).
8. Host adds the dependency and registers what it wants:
   ```kotlin
   val tools = ToolRegistry()
   CalculatorTool.registerOn(tools)   // cherry-pick
   BrowserTool.registerOn(tools)      // or install everything
   val engine = S2SEngine(context, config, languageModel = languageModel, tools = tools)
   ```

## Verifying this template actually compiles, right now

Same situation as the generic template: no published core tag exists yet for
the current `Tools`/`ToolContext` shape, so `settings.gradle.kts` substitutes
the JitPack coordinate for the live `:bindings:android` module via
`includeBuild("../../")`:

```bash
# from the speech-to-speech-mobile repo root
./gradlew --project-dir templates/s2s-tools-plugin-template :tools:compileDebugKotlin
./gradlew --project-dir templates/s2s-tools-plugin-template :tools:testDebugUnitTest
```

Delete the `includeBuild` block once copied into a real, separately-hosted
repo — it has no `../../` to point at and depends on a published tag instead.

## Testing strategy

Both example tools test on the JVM with plain JUnit against the real
`ToolRegistry` — no Android instrumentation, no mocking of core. A tool that
touches a real Android API (an actual `WebView`, a real HTTP client) needs
instrumented tests in its own repo for that part; the session-isolation logic
itself should still be testable the way `BrowserToolTest.kt` does it, since
that logic never needs a device.
