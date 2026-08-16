# Public Classes Reference

This document provides detailed API specifications for primary public classes in the SDK.

---

## 1. `S2SEngine`

`package com.s2s.mobile`

Main orchestrator facade for the 100% on-device Speech-to-Speech pipeline.

```kotlin
@JvmOverloads
class S2SEngine(
    val context: Context,
    val config: S2SConfig,
    recognizer: SpeechRecognizer? = null,
    llm: LanguageModel? = null,
    synthesizer: SpeechSynthesizer? = null,
    input: AudioInput? = null,
    output: AudioOutput? = null
)
```

### Key Methods

- **`initialize(): Result<Unit>`**: Initializes all pipeline models (ASR, LLM, TTS, VAD). Must be called off the UI main thread.
- **`start()`**: Starts microphone listening and begins turn processing. Requires `RECORD_AUDIO` permission.
- **`stop()`**: Stops microphone capture and resets state to `IDLE`.
- **`release()`**: Releases native C++ resources (llama.cpp context, ONNX sessions) and shuts down background worker threads.
- **`registerTool(definition: ToolDefinition, handler: ToolHandler)`**: Registers an on-device function calling tool.
- **`onTrimMemory(level: Int)`**: Trims non-essential KV cache buffers under OS memory pressure.
- **`saveConversationState(): String`**: Serializes active chat history to JSON string.
- **`restoreConversationState(json: String)`**: Restores conversation turns from JSON string.
- **`diskUsage(): Long`**: Returns total model directory storage size in bytes (runs on `Dispatchers.IO`).
- **`getInstalledModels(): List<File>`**: Returns list of installed model files on disk (runs on `Dispatchers.IO`).

---

## 2. `ModelDownloader`

`package com.s2s.mobile.model`

Downloader utility for fetching and extracting model archives from remote URLs with SHA-256 integrity verification.

```kotlin
class ModelDownloader(private val context: Context)
```

### Key Methods

- **`downloadModel(url: String, targetFile: File, sha256: String, progress: (Float) -> Unit): Result<File>`**: Downloads file with stream progress updates and SHA-256 validation.
- **`extractTarGz(archive: File, targetDir: File, progress: (Float) -> Unit): Result<File>`**: Extracts compressed model bundles while reporting byte extraction progress.

---

## 3. `VoiceSessionService`

`package com.s2s.mobile`

Foreground Service helper for managing continuous background voice sessions with Android 14 `FOREGROUND_SERVICE_MICROPHONE` compliance and ongoing notification updates.

---

## 4. `ChatHistory`

`package com.s2s.mobile.llm`

Thread-safe multi-turn chat history buffer with rolling compaction and JSON serialization support.
