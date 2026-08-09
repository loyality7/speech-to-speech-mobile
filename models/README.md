# Mobile Model Assets

This directory stores the lightweight quantized models required for 100% offline on-device speech-to-speech inference.

## Model Summary

| Component | Model Checkpoint | Size | Format | Source |
|---|---|---|---|---|
| **VAD** | Silero VAD v5 | 2.0 MB | `.onnx` | [Silero VAD](https://github.com/snakers4/silero-vad) |
| **STT** | Streaming Zipformer (English) | ~75 MB | `.onnx` | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) |
| **LLM** | SmolLM2-1.7B-Instruct (Q4_K_M) | 1.05 GB | `.gguf` | [HuggingFaceTB](https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF) |
| **TTS** | Piper VITS (`en_US-lessac-medium`) | ~60 MB | `.onnx` | [Piper TTS](https://github.com/rhasspy/piper) |

## Automated Download

Run the download script corresponding to your operating system:
* **Windows**: `download_models.bat`
* **macOS / Linux**: `bash download_models.sh`
