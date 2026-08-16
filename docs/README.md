# Speech-to-Speech Mobile SDK Documentation

Welcome to the documentation for the `speech-to-speech-mobile` Android SDK — a 100% on-device, low-latency conversational Speech-to-Speech (S2S) engine for mobile devices.

---

## Documentation Index

- **[Getting Started](getting-started.md)**: Prerequisites, quick setup, and running your first voice session.
- **[Installation](installation.md)**: Gradle dependency setup, JitPack repository configuration, and required Android permissions.
- **[Quickstart](quickstart.md)**: Complete working Kotlin code example.
- **[Architecture](architecture.md)**: Conceptual model, turn pipeline state machine, and multi-threaded execution design.
- **[Configuration](configuration.md)**: Complete parameter reference for VAD, ASR, LLM, TTS, and Audio settings.
- **[Usage & Tools](usage.md)**: Handling events, state changes, barge-in interruptions, and function calling tools.
- **[API Reference](api/overview.md)**: Public classes, interfaces, configuration data classes, and events.
- **[Advanced & Performance](advanced/performance.md)**: Memory footprint, thread budgeting, thermal growth, and lifecycle memory trimming.
- **[Troubleshooting](advanced/troubleshooting.md)**: Diagnostics, common errors, and hardware debugging.

---

## Key Features & Capabilities

- **100% On-Device & Offline**: Operates in airplane mode without remote server round-trips.
- **Sub-Second Latency**: Streaming Zipformer ASR + GGUF LLM token chunking + Kokoro/Piper ONNX neural synthesis.
- **Instant Barge-In Interruption**: Automatic VAD interruption cancels assistant speech synthesis within 50ms of user speech.
- **Tool Calling Support**: On-device function execution with recursion depth safety caps.
- **Memory Pressure Safety**: Integrated `onTrimMemory` lifecycle hooks and KV session cache purging under RAM pressure.
