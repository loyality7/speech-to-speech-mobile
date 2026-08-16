# Pipeline Interfaces Reference

Every stage of the engine sits behind an interface, so any of them can be
replaced by passing a different implementation to the `S2SEngine` constructor.

All signatures below are copied from the source in
`bindings/android/src/main/java/com/s2s/mobile/pipeline/`. If they ever disagree,
the source is right.

Two conventions run through all of them:

- `initialize()` returns `Result<Unit>`, not a boolean. A failure carries the
  cause, because these wrap native calls where the reason matters — a missing
  model file and a refused model look identical through a boolean.
- Audio is `FloatArray` mono at the configured sample rate, one frame at a time.

---

## 1. `AudioInput`

Microphone capture. Default: `MicrophoneInput`.

```kotlin
interface AudioInput {
    val sampleRate: Int
    val frameSize: Int

    /** Begins capture. Returns false if the device or permission is unavailable. */
    fun start(onFrame: (FloatArray) -> Unit): Boolean

    fun stop()
}
```

`frameSize` must match the VAD's window — Silero expects 512 samples at 16 kHz,
TEN expects 256. `ModelConfigFactory` derives it from the selected backend, and
`S2SEngine.initialize()` refuses a config where the two disagree.

---

## 2. `SpeechRecognizer`

Speech to text. Default depends on the backend: `SherpaStreamingRecognizer` for
streaming models, `OfflineVadRecognizer` for Moonshine and Whisper.

```kotlin
interface SpeechRecognizer {
    fun initialize(): Result<Unit>

    /** Feeds one frame of user audio. */
    fun accept(frame: FloatArray): Transcript

    /** Discards partial state and starts a fresh utterance. */
    fun reset()

    fun release()
}
```

`accept` returns a `Transcript`, which is one of `Transcript.Partial`,
`Transcript.Final` or `Transcript.Nothing` — the recogniser decides when an
utterance has ended, rather than the caller polling for it.

> These objects are owned by the audio thread. Do not call `reset()` or
> `release()` from another thread; sherpa's stream objects are not thread-safe
> and freeing one mid-decode aborts the process.

---

## 3. `VoiceActivityDetector`

Used for barge-in, and for utterance segmentation with offline recognisers.

```kotlin
interface VoiceActivityDetector {
    /** Samples per call to accept(). Silero v5 is trained on 512 at 16 kHz. */
    val frameSize: Int

    fun initialize(): Result<Unit>

    /** Feeds one frame. Returns true while speech is present. */
    fun accept(frame: FloatArray): Boolean

    fun reset()

    fun release()
}
```

---

## 4. `LanguageModel`

Reply generation. Default: `LlamaLanguageModel` over llama.cpp.

```kotlin
interface LanguageModel {
    fun initialize(): Result<Unit>

    fun generate(messages: List<ChatMessage>, sink: TokenSink)

    fun cancel()

    /** The conversation was rewritten; any cached state no longer matches. */
    fun resetContext()

    /** Optional: trim non-essential KV buffers under memory pressure. */
    fun trimMemory() {}

    fun release()
}

interface TokenSink {
    fun onToken(text: String)
    fun onComplete()
    fun onError(message: String, cause: Throwable? = null)
}
```

`generate` blocks until the reply finishes, is cancelled or fails — drive it from
a worker thread. `cancel` must return promptly from another thread, because
barge-in latency depends on it.

---

## 5. `TextChunker`

Splits the token stream into speakable pieces so synthesis can start before the
sentence is finished. Default: `SentenceChunker`.

```kotlin
interface TextChunker {
    /** Feeds a token. Returns any sentences that just became complete. */
    fun accept(token: String): List<String>

    /** Returns whatever is left when generation ends. */
    fun flush(): String?

    fun reset()
}
```

---

## 6. `SpeechSynthesizer`

Text to speech. Default: `SherpaSynthesizer`, covering VITS/Piper, Kokoro,
Matcha, Kitten and Pocket.

```kotlin
interface SpeechSynthesizer {
    /** Model output rate. Only valid after a successful initialize(). */
    val sampleRate: Int

    /** Voices the loaded bundle exposes. Single-voice models return one entry. */
    val voices: List<Voice>

    fun initialize(): Result<Unit>

    /**
     * Synthesises text, handing each chunk to onChunk.
     * keepGoing is polled per chunk; returning false aborts immediately.
     */
    fun synthesize(
        text: String,
        keepGoing: () -> Boolean,
        onChunk: (FloatArray) -> Unit,
    )

    /** Switches voice for subsequent calls. Ignored by single-voice models. */
    fun selectVoice(voiceId: Int)

    fun release()
}
```

`keepGoing` is what makes barge-in feel immediate: it is checked per chunk inside
the native loop, so an interruption stops synthesis mid-word rather than after
the current sentence.

---

## 7. `AudioOutput`

Playback. Default: `SpeakerOutput`.

```kotlin
interface AudioOutput {
    val sampleRate: Int

    fun start()

    fun write(samples: FloatArray)

    /** True while audio is queued or still playing out of the device buffer. */
    fun hasPending(): Boolean

    fun flush()

    fun release()

    /** Invoked once the queue empties and the hardware has played everything out. */
    var onDrained: (() -> Unit)?
}
```

`flush()` drops queued *and* in-flight audio, which is what makes an interruption
stop immediately rather than after the current phrase.
