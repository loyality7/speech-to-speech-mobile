# Speech-to-Speech Mobile

100% on-device speech-to-speech for Android. No cloud, no sockets — once the
models are on disk the whole conversation runs locally, in airplane mode.

```
mic ─▶ Silero VAD ─▶ streaming ASR ─▶ llama.cpp ─▶ chunker ─▶ neural TTS ─▶ speaker
```

A Kotlin port of the pipeline design in [huggingface/speech-to-speech][hf],
built on runtimes that are already native: sherpa-onnx (ONNX Runtime) for
VAD/ASR/TTS, and llama.cpp for generation.

[hf]: https://github.com/huggingface/speech-to-speech

## Layout

```
bindings/android/          the SDK — an Android library, publishable as an AAR
  pipeline/                stage interfaces: AudioInput/Output, SpeechRecognizer,
                           LanguageModel, SpeechSynthesizer, TextChunker, Tools
  config/                  per-stage configuration
  audio/  vad/  stt/       implementations, one package per stage
  llm/    tts/  text/
  tools/  internal/
examples/android-demo/     minimal harness: one button, a transcript
```

Every stage sits behind an interface, so swapping Kokoro for Piper or the
streaming recogniser for an offline one is a constructor argument:

```kotlin
val engine = S2SEngine(
    context = this,
    config = S2SConfig(models = ModelPaths(...)),
    synthesizer = MyOwnSynthesizer(),   // optional override
)
engine.initialize().getOrThrow()        // slow, call off the main thread
engine.start()

lifecycleScope.launch {
    engine.events.collect { event ->
        when (event) {
            is S2SEvent.UserTranscript -> ...
            is S2SEvent.AssistantDelta -> ...
            is S2SEvent.Metrics -> ...
            else -> Unit
        }
    }
}
```

`RECORD_AUDIO` must be granted before `start()`.

## Backends

| Stage | Implementation | Alternatives available |
|-------|----------------|------------------------|
| VAD   | Silero VAD v5 (ONNX) | — |
| STT   | Streaming Zipformer transducer | Zipformer2-CTC, Paraformer, NeMo-CTC |
| LLM   | llama.cpp via Llamatik, any GGUF | — |
| TTS   | Kokoro-82M, 24 kHz | VITS/Piper, Matcha, Kitten, Pocket |

Kokoro and Pocket match the `kokoro` and `pocket` backends in the Python
pipeline; Paraformer matches `paraformer`.

## Building

```bash
./gradlew :examples:android-demo:assembleDebug
adb install -r examples/android-demo/build/outputs/apk/debug/android-demo-debug.apk
```

sherpa-onnx resolves from JitPack, which `settings.gradle.kts` declares. Anyone
consuming this SDK needs that repository too:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}
```

The demo downloads its models on first run into
`Android/data/com.s2s.demo/files/models/`. To side-load them instead:

```
models/silero_vad.onnx      Silero VAD v5
models/stt/                 extracted sherpa streaming ASR bundle
models/tts/                 extracted sherpa TTS bundle
models/model.gguf           any instruct-tuned GGUF
```

## Latency

Measured per turn from the end of the user's speech and logged as
`turn latency: first token Xms, first audio Yms`.

Recognition runs *while* the user is speaking, so the transcript is already
decoded when the endpointer fires — there is no transcription wait in the
response path. Generation is chunked at clause boundaries so the first phrase is
synthesised while the rest is still being written.

## Tests

```bash
./gradlew :bindings:android:testDebugUnitTest
```

## Licence & Compliance

This project is published under the [Apache License 2.0](LICENSE). 

For full privacy guarantees, Android runtime permissions, Play Store Data Safety guidelines, and open-source license compliance audit details, see **[PRIVACY_AND_PERMISSIONS.md](PRIVACY_AND_PERMISSIONS.md)**.

- **License Audit Note**: The SDK runtime core and neural ONNX inference engines (`sherpa-onnx`, `llama.cpp`) operate under permissive **Apache 2.0 and MIT** open-source licenses. Standalone GPL-3.0 binaries (such as legacy `espeak-ng`) are excluded from runtime dependencies.
- **Model Licensing**: Models are downloaded dynamically at runtime and carry their respective open-source model licenses. See [NOTICE](NOTICE).
