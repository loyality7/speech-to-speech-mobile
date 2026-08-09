#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "====================================================================="
echo "   Downloading 100% Local Speech-to-Speech Mobile Models"
echo "====================================================================="

# 1. Silero VAD v5 (ONNX) - ~2 MB
echo "[1/4] Downloading Silero VAD v5 ONNX model..."
if [ ! -f "silero_vad.onnx" ]; then
    curl -L -o "silero_vad.onnx" "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx"
else
    echo " - silero_vad.onnx already exists."
fi

# 2. Sherpa-ONNX Streaming Zipformer STT (English) - ~75 MB
echo "[2/4] Downloading sherpa-onnx Zipformer STT..."
STT_TAR="sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2"
if [ ! -d "sherpa-onnx-streaming-zipformer-en-2023-06-26" ]; then
    if [ ! -f "$STT_TAR" ]; then
        curl -L -o "$STT_TAR" "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$STT_TAR"
    fi
    tar -xjf "$STT_TAR"
else
    echo " - Zipformer STT model already extracted."
fi

# 3. SmolLM2 1.7B Instruct GGUF (Q4_K_M) - ~1.05 GB
echo "[3/4] Downloading SmolLM2-1.7B-Instruct GGUF (llama.cpp)..."
if [ ! -f "SmolLM2-1.7B-Instruct-Q4_K_M.gguf" ]; then
    curl -L -o "SmolLM2-1.7B-Instruct-Q4_K_M.gguf" "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf"
else
    echo " - SmolLM2-1.7B GGUF already exists."
fi

# 4. Piper VITS TTS Voice (en_US-lessac-medium) - ~60 MB
echo "[4/4] Downloading Piper VITS Voice (ONNX)..."
TTS_TAR="vits-piper-en_US-lessac-medium.tar.bz2"
if [ ! -d "vits-piper-en_US-lessac-medium" ]; then
    if [ ! -f "$TTS_TAR" ]; then
        curl -L -o "$TTS_TAR" "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$TTS_TAR"
    fi
    tar -xjf "$TTS_TAR"
else
    echo " - Piper TTS model already extracted."
fi

echo "====================================================================="
echo "   All mobile models downloaded successfully!"
echo "====================================================================="
