# Advanced Performance & Resource Benchmarks

Performance metrics, thread allocation budgeting, and thermal characteristics of the SDK.

---

## 1. Resource & Footprint Benchmarks

| Metric | Idle State | Active Voice Session |
| :--- | :--- | :--- |
| **RAM (Resident)** | ~90 MB | ~550 MB (with 0.5B GGUF + Kokoro TTS) |
| **Model Storage** | ~609 MB (on disk) | — |
| **CPU Utilization** | < 1% | ~28% (across 4 cores during generation) |

---

## 2. Threading Budget & Thermal Profile

- **LLM Workers**: Thread count is budget-capped to 4 worker threads (`threads = 4`). Allocating >4 threads increases CPU die temperature by ~40% with diminishing return on TTFT.
- **Thermal Growth**: Measured on physical hardware over a 15-minute continuous conversation:
  - Initial Temp: **31.2°C**
  - Final Temp (15 min): **34.4°C** (`+3.2°C` delta, well below thermal throttling ceiling of 42°C).
- **Active Battery Drain**: Measured at **9.5% to 11.8% per hour** (`~0.92 W` active power draw).
