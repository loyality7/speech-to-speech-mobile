# API Overview

This section lists the key public classes, interfaces, events, and lifecycle methods provided by the `speech-to-speech-mobile` SDK.

---

## Key Package Hierarchy (`com.s2s.mobile`)

```text
com.s2s.mobile
├── S2SEngine                  // Primary SDK facade and session orchestrator
├── S2SState                   // Enum representing engine turn states
├── S2SEvent                   // Sealed class representing pipeline events
├── config/
│   ├── S2SConfig              // Master configuration container
│   ├── ModelPaths             // Local model paths specification
│   ├── VadConfig / LlmConfig  // Per-stage configuration
│   └── ModelConfigFactory     // Factory helper for dynamic model configs
├── pipeline/
│   ├── AudioInput / AudioOutput // Audio capture & playback abstractions
│   ├── SpeechRecognizer       // ASR interface
│   ├── LanguageModel          // LLM interface
│   └── SpeechSynthesizer      // TTS interface
├── model/
│   ├── ModelDownloader        // Streaming model fetcher & extractor
│   └── ModelDownloadService   // Android Foreground Service for model setup
└── tools/
    ├── ToolRegistry           // Function calling registry
    └── ToolDefinition         // Tool specification schema
```

---

## Core Classes & Interfaces Quick Index

- **`S2SEngine`**: Main entrance facade. Instantiates and manages all pipeline workers. Annotated with `@JvmOverloads` for Kotlin and Java interop.
- **`S2SEvent`**: Sealed hierarchy emitted via `engine.events`:
  - `StateChanged(next: S2SState)`
  - `UserTranscript(text: String)`
  - `AssistantDelta(text: String)`
  - `BargeIn(turn: Int)`
  - `Metrics(latency: TurnMetrics)`
  - `Error(message: String)`
- **`ToolRegistry`**: Tool registration engine. Call `engine.registerTool(definition) { args -> result }`.
