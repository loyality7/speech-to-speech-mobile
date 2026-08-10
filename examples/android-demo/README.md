# SpeechToSpeech Mobile — Android Demo App

This is a fully functional **Jetpack Compose** demo application that demonstrates the entire on-device speech-to-speech pipeline using **Llamatik** for LLM inference and **whisper.cpp** for speech-to-text.

## Features

- **Pulsing Voice Orb** — animated state indicator (Idle → Listening → Thinking → Speaking)
- **Model Download Manager** — stream GGUF and ONNX models directly from HuggingFace with progress UI
- **On-Device LLM Chat** — streaming token generation via Llamatik (llama.cpp) with chat templates
- **Whisper STT** — on-device speech transcription via WhisperBridge
- **Hardware AEC** — Android `AcousticEchoCanceler` + `NoiseSuppressor` for echo-free full-duplex
- **Barge-In** — tap the orb while speaking to instantly interrupt and resume listening
- **Metrics HUD** — real-time latency display (VAD, STT, TTFT, TTS, barge-in count)
- **Text Input** — manual text testing of LLM responses without microphone

## Architecture

```
MainActivity
  └── S2SScreen (Compose UI)
        ├── PulsingVoiceOrb
        ├── TranscriptView
        ├── MetricsHUD
        ├── ModelDownloadSheet (BottomSheet)
        └── Text Input Bar
              │
        S2SViewModel (orchestrator)
        ├── ModelDownloadManager (OkHttp streaming)
        ├── LlamaBridge (Llamatik GGUF LLM)
        ├── WhisperBridge (Llamatik STT)
        ├── S2SEngine (C++ JNI core)
        └── AudioPlaybackManager (AudioTrack)
```

## Models

| Model | Size | Use |
| :--- | :--- | :--- |
| Silero VAD v5 | ~2 MB | Voice activity detection |
| Whisper-Tiny GGML | ~78 MB | Speech-to-text |
| Qwen 2.5 0.5B Q4 | ~398 MB | Fast on-device LLM |
| SmolLM2 1.7B Q4 | ~1.05 GB | Balanced on-device LLM |
| Piper VITS en_US | ~64 MB | Text-to-speech |

## Build

```bash
# From the repo root
./gradlew :examples:android-demo:assembleDebug
```
