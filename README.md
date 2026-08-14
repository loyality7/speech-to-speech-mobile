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

## Licence

This project is licensed under the [Apache License 2.0](LICENSE).

**Before you ship an app built on this SDK, read [NOTICE](NOTICE).** The
Apache-2.0 licence covers the source in this repository, but the native library
this project links against — `libsherpa-onnx-jni.so` — has espeak-ng compiled
into it, and espeak-ng is GPL-3.0. Distributing that binary means distributing
GPL-3.0 code, regardless of which text-to-speech model you select, because they
all live in the same library.

Tracked in [#27](https://github.com/loyality7/speech-to-speech-mobile/issues/27),
with the evidence and the available options.

Models are not distributed with the SDK; they are downloaded at runtime and each
carries its own licence. See [NOTICE](NOTICE).
