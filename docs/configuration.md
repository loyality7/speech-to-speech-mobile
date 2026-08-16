# Speech-to-Speech Mobile SDK Configuration

The SDK behavior is fully configurable via the `S2SConfig` data class hierarchy.

---

## 1. Top-Level Configuration (`S2SConfig`)

```kotlin
data class S2SConfig(
    val models: ModelPaths,
    val audio: AudioConfig = AudioConfig(),
    val vad: VadConfig = VadConfig(),
    val stt: SttConfig = SttConfig(),
    val llm: LlmConfig = LlmConfig(),
    val tts: TtsConfig = TtsConfig()
)
```

---

## 2. Component Configuration Data Classes

### `ModelPaths`
Specifies local filesystem paths for model weights:

```kotlin
data class ModelPaths(
    val vadModel: String,  // Path to silero_vad.onnx or ten_vad.onnx
    val sttDir: String,    // Directory containing sherpa ASR model files
    val llmModel: String,  // Path to instruct GGUF model file
    val ttsDir: String     // Directory containing sherpa TTS model files
)
```

### `VadConfig`
Configures Voice Activity Detection backend and speech thresholds:

```kotlin
data class VadConfig(
    val backend: VadBackend = VadBackend.SILERO, // SILERO or TEN
    val threshold: Float = 0.5f,                 // Speech confidence (0.0 to 1.0)
    val minSpeechDurationMs: Int = 250,          // Min speech duration to start turn
    val minSilenceDurationMs: Int = 500          // Silence duration to end turn
)
```

### `LlmConfig`
Configures `llama.cpp` inference parameters:

```kotlin
data class LlmConfig(
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val contextLength: Int = 2048,
    val numThreads: Int = 4,              // Recommended ARM core thread cap
    val useMmap: Boolean = true,
    val flashAttention: Boolean = true,
    val reuseKvCache: Boolean = true,     // Enables KV session cache reuse
    val toolsEnabled: Boolean = false     // Enables on-device tool calling prompt
)
```

### `TtsConfig`
Configures text-to-speech synthesis engine:

```kotlin
data class TtsConfig(
    val backend: TtsBackend = TtsBackend.KOKORO, // KOKORO or PIPER
    val speed: Float = 1.0f,                    // Playback speed rate
    val sid: Int = 0                            // Speaker ID index for multi-speaker models
)
```

### `AudioConfig`
Configures PCM sample rates and frame buffer sizes:

```kotlin
data class AudioConfig(
    val sampleRate: Int = 16000,    // 16 kHz PCM standard
    val frameSize: Int = 512        // Sample frame size (auto-derived by VAD backend)
)
```
