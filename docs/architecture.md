# Speech-to-Speech Mobile SDK Architecture

This document describes the architectural flow, component relationships, turn state machine, and multi-threaded execution model of the SDK.

---

## 1. System Pipeline Architecture

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

---

## 2. Turn Lifecycle State Machine

The engine transitions between 5 core states defined in `S2SState`:

```text
       ┌──────────┐
       │   IDLE   │
       └────┬─────┘
            │ start()
            ▼
     ┌──────────────┐      VAD speech detected
     │  LISTENING   │ ─────────────────────────┐
     └──────────────┘                          │
            ▲                                  ▼
            │ Turn complete              ┌───────────┐
            └─────────────────────────── │ THINKING  │
                        ▲                └─────┬─────┘
                        │                      │ First LLM token
                        │                      ▼
                        │                ┌───────────┐
                        └─────────────── │  SPEAKING │
                          Turn complete  └───────────┘
                                               │
                                               │ Barge-in interruption
                                               ▼
                                         ┌───────────┐
                                         │ INTERRUPTED│ ──▶ Returns to LISTENING
                                         └───────────┘
```

### State Definitions (`S2SState`)
- **`IDLE`**: Initial state before `start()` or after `stop()`.
- **`LISTENING`**: Microphone active, VAD and streaming ASR parsing user audio.
- **`THINKING`**: User speech ended; LLM generating initial response tokens (TTFT window).
- **`SPEAKING`**: Audio synthesis and speaker playback active.
- **`INTERRUPTED`**: User spoke while assistant was speaking; active synthesis and playback immediately canceled.

---

## 3. Concurrency & Thread Budgeting

The SDK uses isolated single-thread executors for serial tasks to prevent UI thread blocking and eliminate race conditions:

- **`S2S-Llm` Thread**: Dedicated single-thread worker for GGUF LLM token generation (`llama.cpp`).
- **`S2S-Tts` Thread**: Dedicated single-thread worker for ONNX text-to-speech chunk synthesis.
- **Audio Thread**: High-priority real-time audio thread driving 16 kHz PCM microphone capture and speaker track playback.
- **`Dispatchers.IO`**: Offloads model disk size calculation and directory scanning APIs (`diskUsage`, `getInstalledModels`).

---

## 4. Safety & Exception Resilience

- **THINKING Guard**: LLM generation execution is wrapped in exception handlers (`try-catch`). On model failure or empty output, state automatically resets to `LISTENING` to prevent stuck engine states.
- **Tool Recursion Cap**: Tool execution recursion is capped at `MAX_TOOL_RECURSION_DEPTH = 3` to prevent infinite tool calling loops.
- **Thread-Safe Memory Purging**: `trimMemory()` synchronizes KV cache purges with active LLM stream workers, deferring purges mid-turn to prevent session corruption.
