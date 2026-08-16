# Troubleshooting Guide

Common issues, diagnostic logging, and hardware debugging tips.

---

## Common Issues & Solutions

### 1. `RECORD_AUDIO Permission Denied`
- **Symptom**: `S2SEngine.start()` fails or audio input captures zeroes.
- **Fix**: Grant runtime permission before invoking `engine.start()`.

### 2. `Model not loaded` / `File Not Found`
- **Symptom**: `initialize()` returns failure with missing model paths.
- **Fix**: Verify model files exist in your app target directory using `engine.getInstalledModels()`.

### 3. VAD / Microphone Window Mismatch
- **Symptom**: Audio capture throws window size mismatch exceptions.
- **Fix**: `ModelConfigFactory` automatically derives frame sizes matching your `VadBackend` (Silero = 512, TEN = 256). Avoid overriding `AudioConfig.frameSize` manually.
