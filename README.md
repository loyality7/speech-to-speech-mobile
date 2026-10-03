# Speech-to-Speech Mobile

[![Android](https://img.shields.io/badge/Android-SDK_26%2B-green)](#requirements) [![Kotlin](https://img.shields.io/badge/Kotlin-JVM_17-blue)](#building) [![sherpa-onnx](https://img.shields.io/badge/sherpa--onnx-v1.13.5-orange)](#runtimes) [![Licence](https://img.shields.io/badge/Licence-GPL_3.0-blue)](#licence)

A fully on-device speech-to-speech engine for Android. Nothing leaves the phone once the models are downloaded.

```
mic ─▶ Silero/TEN VAD ─▶ ASR (sherpa-onnx) ─▶ LLM (llama.cpp) ─▶ chunker ─▶ neural TTS (sherpa-onnx) ─▶ speaker
                  ╰── barge-in: user talks over the assistant → instant cutoff ──╯
```

The SDK is a Kotlin Android library (`com.s2s.mobile`). There is no C++ of our own — the compute is already native inside sherpa-onnx and llama.cpp. Every pipeline stage is an interface, so any of them can be swapped by passing a different implementation to the `S2SEngine` constructor.

---

## Repository layout

```
bindings/android/          the SDK — an Android library (AAR), publishable via JitPack
  pipeline/                stage INTERFACES: AudioInput/Output, VoiceActivityDetector,
                           SpeechRecognizer, LanguageModel, SpeechSynthesizer,
                           TextChunker, ContextEngine, TextNormalizer
  config/                  per-stage config: S2SConfig, AudioConfig, VadConfig,
                           SttConfig, TtsConfig, TurnConfig, ModelPaths
  audio/                   MicrophoneInput, SpeakerOutput, AudioFocusController,
                           VoiceSessionService (foreground service for background capture)
  vad/                     SileroVad, TenVad
  stt/                     SherpaStreamingRecognizer, OfflineVadRecognizer
  tts/                     SherpaSynthesizer, AudioRestorer (HD upsampling, opt-in)
  text/                    SentenceChunker, SpeakableText
  model/                   ModelRegistry, ModelDownloader, HuggingFaceDownloader,
                           ModelDownloads (foreground-service download), ModelSpec, S2SModels
  internal/                TurnGuard, TurnAggregator, BargeInGate
  S2SEngine.kt             orchestration, state machine, turn lifecycle
  S2SEvent.kt              S2SState, S2SEvent, TurnMetrics
  S2SStages.kt             factory for individual stages from a ModelSpec

examples/
  android-demo/            full demo app: model spinners, background download,
                           Hugging Face repository browser, and standalone TTS/STT testers
```

## How it works

`S2SEngine` is the single entry point. It owns the microphone, the pipeline, and the speaker:

1. **A `LanguageModel`** — streaming text generation (on-device llama.cpp, remote API, or any custom engine).
2. **A `ContextEngine`** — conversation memory (in-memory, SQLite, or custom store).

Both are interfaces in `pipeline/`.

```kotlin
val engine = S2SEngine(
    context = this,
    config = ModelConfigFactory.create(modelsDir, vad, stt, tts, llm),
    languageModel = LlamaLanguageModel(LlamaConfig(), config.models.llmModel),
    history = SqliteContextEngine(this, sessionId, "You are a voice assistant."),
)

engine.initialize().getOrThrow()   // loads models, call off the main thread
engine.start()                     // opens the mic, starts listening

lifecycleScope.launch {
    engine.events.collect { event ->
        when (event) {
            is S2SEvent.UserTranscript -> updateUserBubble(event.text, event.isFinal)
            is S2SEvent.AssistantDelta -> appendAssistantText(event.text)
            is S2SEvent.AssistantDone  -> commitAssistantBubble(event.text)
            is S2SEvent.Metrics        -> logLatency(event.metrics)
            is S2SEvent.BargeIn        -> showInterruptIndicator()
            is S2SEvent.StateChanged   -> updateStateUI(event.state)
            is S2SEvent.Error          -> showError(event.message)
            else -> Unit
        }
    }
}
```

`RECORD_AUDIO` must be granted before `start()`.

### State machine

```
IDLE → LISTENING → USER_TURN_ACTIVE → TURN_PENDING_CONFIRMATION → THINKING → SPEAKING → LISTENING
                                                                               ↑ barge-in ↓
```

- **TurnAggregator** collects acoustic segments across pauses, so "set a reminder … for tomorrow … to call the dentist" dispatches as one turn, not three.
- **TurnGuard** is one atomic counter. One increment invalidates recognition + generation + synthesis + playback together.
- **BargeInGate** requires N consecutive voiced frames plus a grace window after playback starts (echo canceller convergence time).

### Threading

Two dedicated worker threads: `S2S-Llm` and `S2S-Tts`. Sentence two is generated while sentence one is still being spoken.

## Runtimes

| Runtime | Version | Role | Licence |
|---------|---------|------|---------|
| sherpa-onnx (AAR, JitPack) | 1.13.5 | VAD + ASR + TTS | Apache-2.0 |
| ONNX Runtime | bundled in sherpa | tensor execution | MIT |
| llama.cpp | 0.3.3 | on-device LLM generation | Apache-2.0 |

No NDK work of our own. The sherpa AAR is resolved from JitPack (`settings.gradle.kts`). Consumers need `maven { url = uri("https://jitpack.io") }`.

## Supported models

Models are downloaded at runtime via a `ModelRegistry` driven by `models_registry.json`. The registry is replaceable — call `ModelRegistry.useRegistry(yourJson)` before first access. The demo app also supports browsing and downloading arbitrary models from Hugging Face.

### VAD

| Model | Backend | Status |
|-------|---------|--------|
| Silero VAD v5 | `SileroVad` | default |
| TEN VAD | `TenVad` | wired, opt-in |

### STT

| Model | Mode | Backend | Status |
|-------|------|---------|--------|
| Moonshine base-en int8 | offline | `OfflineVadRecognizer` | default |
| Moonshine tiny-en int8 | offline | `OfflineVadRecognizer` | supported |
| Parakeet-TDT 0.6b v3 | offline | `OfflineVadRecognizer` | wired |
| Streaming Zipformer | streaming | `SherpaStreamingRecognizer` | wired |
| Paraformer / Zipformer2-CTC | streaming | `SherpaStreamingRecognizer` | wired |
| Whisper | offline | `OfflineVadRecognizer` | wired |

Streaming recognisers emit live partials; offline ones decode the whole utterance after the VAD endpoint — better accuracy, +200–650 ms in the response path.

### TTS

| Model | Backend | Status |
|-------|---------|--------|
| Piper VITS en_US-lessac-medium | `VITS` | default |
| Kokoro-82M int8 | `KOKORO` | wired |
| Kitten TTS | `KITTEN` | wired |
| Matcha-TTS | `MATCHA` | wired |
| Pocket-TTS | `POCKET` | wired |

### LLM

Any instruct-tuned GGUF via llama.cpp (e.g. Qwen 2.5 0.5B / 1.5B, SmolLM2, Llama 3.2 1B).

## Building

```bash
./gradlew :bindings:android:assembleRelease          # the SDK AAR
./gradlew :examples:android-demo:assembleDebug        # the demo app
adb install -r examples/android-demo/build/outputs/apk/debug/android-demo-debug.apk
```

The demo downloads models on first run. To side-load instead, push them to `Android/data/com.s2s.demo/files/models/`.

## Tests

```bash
./gradlew :bindings:android:testDebugUnitTest
```

12 test files covering: `SentenceChunker`, `TurnGuard`, `TurnAggregator`, `BargeInGate`, `NormalizationHeuristic`, `ModelDownloader`, `HuggingFaceDownloader`, `EngineIntegration`, `ExternalTurnHandler`, `SingleShotGeneration`, `MemoryTrim`, `SherpaVad`.

## Requirements

- Android 8.0+ (API 26)
- JDK 17
- arm64-v8a (the demo filters to this ABI; add others in `build.gradle.kts` if needed)

## Licence

> [!IMPORTANT]
> **GPL-3.0 due to espeak-ng.**
> `espeak-ng` (GPL-3.0) is compiled into `libsherpa-onnx-jni.so`. Both Kokoro and Piper bundles ship `espeak-ng-data/` for phonemisation. This affects any redistribution, regardless of which TTS model is selected.
>
> When upstream `sherpa-onnx` decouples `espeak-ng`, this project will transition to Apache License 2.0.

See [LICENSE](LICENSE) and [NOTICE](NOTICE). **Model licensing**: models are downloaded at runtime and carry their own licences.
