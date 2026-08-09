@echo off
setlocal enabledelayedexpansion

echo =====================================================================
echo    Downloading 100%% Local Speech-to-Speech Mobile Models
echo =====================================================================

set "MODELS_DIR=%~dp0"
cd /d "%MODELS_DIR%"

REM 1. Silero VAD v5 (ONNX) - ~2 MB
echo [1/4] Downloading Silero VAD v5 ONNX model...
if not exist "silero_vad.onnx" (
    curl -L -o "silero_vad.onnx" "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx"
) else (
    echo  - silero_vad.onnx already exists.
)

REM 2. Sherpa-ONNX Streaming Zipformer STT (English) - ~75 MB
echo [2/4] Downloading sherpa-onnx Zipformer STT...
set "STT_TAR=sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2"
if not exist "sherpa-onnx-streaming-zipformer-en-2023-06-26" (
    if not exist "%STT_TAR%" (
        curl -L -o "%STT_TAR%" "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/%STT_TAR%"
    )
    tar -xjf "%STT_TAR%"
) else (
    echo  - Zipformer STT model already extracted.
)

REM 3. SmolLM2 1.7B Instruct GGUF (Q4_K_M) - ~1.05 GB
echo [3/4] Downloading SmolLM2-1.7B-Instruct GGUF (llama.cpp)...
if not exist "SmolLM2-1.7B-Instruct-Q4_K_M.gguf" (
    curl -L -o "SmolLM2-1.7B-Instruct-Q4_K_M.gguf" "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf"
) else (
    echo  - SmolLM2-1.7B GGUF already exists.
)

REM 4. Piper VITS TTS Voice (en_US-lessac-medium) - ~60 MB
echo [4/4] Downloading Piper VITS Voice (ONNX)...
set "TTS_TAR=vits-piper-en_US-lessac-medium.tar.bz2"
if not exist "vits-piper-en_US-lessac-medium" (
    if not exist "%TTS_TAR%" (
        curl -L -o "%TTS_TAR%" "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/%TTS_TAR%"
    )
    tar -xjf "%TTS_TAR%"
) else (
    echo  - Piper TTS model already extracted.
)

echo =====================================================================
echo    All mobile models downloaded successfully!
echo =====================================================================
pause
