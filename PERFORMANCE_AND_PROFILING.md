# Speech-to-Speech Mobile SDK: Performance & Thermal Profiling Guide

This document outlines memory footprint, CPU threading optimization, battery consumption, and thermal behavior benchmarks for sustained multi-minute voice sessions using the `speech-to-speech-mobile` SDK.

---

## 1. Resource & Memory Footprint

The SDK is optimized for resource-constrained ARM64 Android devices (3 GB - 8 GB RAM):

| Resource | Idle Engine State | Active Speech Session |
| :--- | :--- | :--- |
| **RAM (Resident)** | ~90 MB | ~550 MB (with 0.5B GGUF + Kokoro TTS) |
| **Model Storage** | ~609 MB (on disk) | — |
| **CPU Utilization** | < 1% | ~28% (across 4 cores during generation) |

---

## 2. Thermal & Threading Optimization

Engine CPU threads are budgeted across pipeline stages to prevent thermal throttling on ARM64 big.LITTLE SOC architectures (e.g., Snapdragon, MediaTek, Dimensity):

- **LLM Thread Cap (`threads = 4`)**: Allocating 4 threads for `llama.cpp` inference provides optimal Time-To-First-Token (TTFT) without maxing out thermal capacity. Allocating >4 threads increases CPU die temperature by ~40% with negligible TTFT improvement.
- **ASR & TTS Inference**: Uses single-threaded ONNX runtime graph execution for streaming Zipformer ASR and Kokoro TTS to keep thermal dissipation low.
- **Thermal Growth Profile**: Measured on physical hardware (Xiaomi Redmi, Android 14) over a sustained 15-minute continuous voice conversation:
  - Initial Battery Temp: **31.2°C**
  - Temp after 15 minutes continuous speech: **34.4°C** (Delta: **+3.2°C**, well below thermal throttling thresholds of 42°C).

---

## 3. Battery Profiling Benchmarks

Battery consumption was profiled using `dumpsys batterystats` during sustained multi-minute voice sessions:

| Benchmark | Value |
| :--- | :--- |
| **Active Discharge Rate** | ~9.5% to 11.8% per hour of continuous voice interaction. |
| **Power Draw (Active Turn)** | ~0.92 W |
| **Power Draw (Idle Listening)** | ~0.14 W (Microphone VAD polling) |
| **Audio Track Buffer Optimization** | Native 16 kHz 16-bit PCM streaming buffers allow CPU core sleep between TTS chunk synthesis intervals. |

---

## 4. Optimization Recommendations for Developers

1. **Enable Memory Trimming**: Delegate `onTrimMemory(level)` from your host Activity/Service to `S2SEngine.onTrimMemory(level)` to automatically release non-essential KV cache buffers under OS memory pressure.
2. **Audio Focus Handling**: Respect `AudioFocusLost` events to release microphone capture when phone calls or alarms interrupt the app.
3. **Background Service Management**: Use `VoiceSessionService` as a Foreground Service with notification updates during long background voice calls.
