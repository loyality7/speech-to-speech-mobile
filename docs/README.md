# Speech-to-Speech Mobile SDK Documentation

Welcome to the official developer documentation portal for the `speech-to-speech-mobile` Android SDK — a 100% on-device, low-latency conversational Speech-to-Speech (S2S) engine.

---

## Complete Documentation Directory

### 🚀 Getting Started & Setup
- **[Getting Started Guide](getting-started.md)**: Setup prerequisites, permissions, and initial Activity setup.
- **[Installation Guide](installation.md)**: JitPack dependency setup, Gradle configurations, and Android permissions.
- **[Quickstart Guide](quickstart.md)**: Complete runnable Kotlin code example.

### 🏗️ Architecture & Configuration
- **[SDK Architecture](architecture.md)**: System pipeline diagram, `S2SState` turn state machine, thread budgeting (`S2S-Llm`, `S2S-Tts`), and safety guards.
- **[Configuration Reference](configuration.md)**: Complete parameter reference for `S2SConfig`, `ModelPaths`, `VadConfig`, `LlmConfig`, `TtsConfig`, and `AudioConfig`.
- **[Usage & Tool Calling](usage.md)**: Registering on-device tools (`ToolDefinition`), memory trimming (`onTrimMemory`), and conversation state serialization.

### 📚 API Specifications
- **[API Overview](api/overview.md)**: Package structure and `S2SEvent` sealed event hierarchy.
- **[Public Classes Reference](api/classes.md)**: Complete specifications for `S2SEngine`, `ModelDownloader`, `VoiceSessionService`, `ChatHistory`, and `Utf8StreamDecoder`.
- **[Pipeline Interfaces](api/interfaces.md)**: Modular stage abstractions (`AudioInput`, `SpeechRecognizer`, `LanguageModel`, `SpeechSynthesizer`, `AudioOutput`).

### 🔬 Advanced Guides & Performance
- **[Advanced Performance & Benchmarks](advanced/performance.md)**: Memory footprint, thread budgeting, active battery draw, and thermal growth profile.
- **[Troubleshooting & Diagnostics](advanced/troubleshooting.md)**: Solutions for common runtime errors, VAD window mismatches, and permissions.
- **[Performance & Profiling Guide](../PERFORMANCE_AND_PROFILING.md)**: Deep thermal profiling curves and battery discharge rates.
- **[Privacy & Permissions Guide](../PRIVACY_AND_PERMISSIONS.md)**: On-device privacy guarantees, Play Store Data Safety, and license compliance.
- **[License & Notice Notices](../NOTICE)**: Third-party component licensing audit and `espeak-ng` GPL-3.0 disclosure.

---

## Core System Architecture

```text
Host Application (Activity / Service)
       ↓
    S2SEngine (Facade & Turn Coordinator)
       ↓
 ┌─────────────────────────────────────────────────────────┐
 │ 1. MicrophoneInput  ──▶  Silero/TEN VAD                 │
 │ 2. Voice Activity   ──▶  Sherpa Streaming Zipformer ASR │
 │ 3. Text Transcript  ──▶  llama.cpp GGUF LLM Worker      │
 │ 4. Token Stream     ──▶  SentenceClauseChunker         │
 │ 5. Text Chunks      ──▶  Kokoro / Piper Neural TTS     │
 │ 6. Audio Output     ──▶  SpeakerOutput Track            │
 └─────────────────────────────────────────────────────────┘
```
