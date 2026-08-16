# Pipeline Interfaces Reference

The SDK architecture is stage-modular. Custom pipeline components can be supplied by implementing these public interfaces in `com.s2s.mobile.pipeline`.

---

## 1. `AudioInput`

Abstraction for audio capture (default: `MicrophoneInput`).

```kotlin
interface AudioInput {
    fun start(onAudioFrame: (ShortArray) -> Unit)
    fun stop()
    fun release()
}
```

---

## 2. `SpeechRecognizer`

Abstraction for ASR speech-to-text recognition (default: `SherpaRecognizer`).

```kotlin
interface SpeechRecognizer {
    fun initialize(): Boolean
    fun acceptWaveform(samples: FloatArray)
    fun isReady(): Boolean
    fun decode()
    fun getResult(): String
    fun reset()
    fun release()
}
```

---

## 3. `LanguageModel`

Abstraction for LLM token generation (default: `LlamaLanguageModel`).

```kotlin
interface LanguageModel {
    fun initialize(): Boolean
    fun generate(messages: List<ChatMessage>, sink: TokenSink)
    fun cancel()
    fun resetContext()
    fun trimMemory()
    fun release()
}

interface TokenSink {
    fun onToken(token: String)
    fun onComplete()
    fun onError(message: String)
}
```

---

## 4. `SpeechSynthesizer`

Abstraction for TTS text-to-speech synthesis (default: `SherpaSynthesizer`).

```kotlin
interface SpeechSynthesizer {
    fun initialize(): Boolean
    fun synthesize(text: String, onAudioChunk: (FloatArray, Int) -> Unit): Boolean
    fun release()
}
```

---

## 5. `AudioOutput`

Abstraction for audio playback (default: `SpeakerOutput`).

```kotlin
interface AudioOutput {
    fun write(samples: FloatArray, sampleRate: Int)
    fun stop()
    fun release()
}
```
