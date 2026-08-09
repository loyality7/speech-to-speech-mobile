#include "s2s/stt/whisper_feature_extractor.h"
#include <cmath>
#include <algorithm>
#include <complex>

namespace s2s {

static constexpr float PI = 3.14159265358979323846f;

WhisperFeatureExtractor::WhisperFeatureExtractor() {
    initHannWindow();
    initMelFilters();
}

WhisperFeatureExtractor::~WhisperFeatureExtractor() = default;

void WhisperFeatureExtractor::initHannWindow() {
    hannWindow_.resize(N_FFT);
    for (int i = 0; i < N_FFT; ++i) {
        hannWindow_[i] = 0.5f * (1.0f - std::cos(2.0f * PI * i / N_FFT));
    }
}

float WhisperFeatureExtractor::hzToMel(float hz) {
    return 2595.0f * std::log10(1.0f + hz / 700.0f);
}

float WhisperFeatureExtractor::melToHz(float mel) {
    return 700.0f * (std::pow(10.0f, mel / 2595.0f) - 1.0f);
}

void WhisperFeatureExtractor::initMelFilters() {
    melFilters_.resize(N_MELS, std::vector<float>(N_FFT / 2 + 1, 0.0f));

    float minMel = hzToMel(0.0f);
    float maxMel = hzToMel(8000.0f);
    std::vector<float> melPoints(N_MELS + 2);
    for (size_t i = 0; i < melPoints.size(); ++i) {
        melPoints[i] = minMel + i * (maxMel - minMel) / (N_MELS + 1);
    }

    std::vector<float> hzPoints(N_MELS + 2);
    for (size_t i = 0; i < hzPoints.size(); ++i) {
        hzPoints[i] = melToHz(melPoints[i]);
    }

    std::vector<int> binPoints(N_MELS + 2);
    for (size_t i = 0; i < binPoints.size(); ++i) {
        binPoints[i] = static_cast<int>(std::floor((N_FFT + 1) * hzPoints[i] / SAMPLE_RATE));
    }

    for (int m = 0; m < N_MELS; ++m) {
        for (int k = binPoints[m]; k < binPoints[m + 1]; ++k) {
            if (k < static_cast<int>(melFilters_[m].size())) {
                melFilters_[m][k] = static_cast<float>(k - binPoints[m]) / (binPoints[m + 1] - binPoints[m]);
            }
        }
        for (int k = binPoints[m + 1]; k < binPoints[m + 2]; ++k) {
            if (k < static_cast<int>(melFilters_[m].size())) {
                melFilters_[m][k] = static_cast<float>(binPoints[m + 2] - k) / (binPoints[m + 2] - binPoints[m + 1]);
            }
        }
    }
}

std::vector<float> WhisperFeatureExtractor::extractFeatures(const float* samples, size_t numSamples) {
    if (!samples || numSamples == 0) return {};

    size_t numFrames = std::min(static_cast<size_t>(MAX_FRAMES), (numSamples > static_cast<size_t>(N_FFT)) ? (numSamples - N_FFT) / HOP_LENGTH + 1 : 1);
    std::vector<float> melSpectrogram(N_MELS * numFrames, 0.0f);

    std::vector<std::complex<float>> fftBuffer(N_FFT);
    std::vector<float> powerSpectrum(N_FFT / 2 + 1, 0.0f);

    for (size_t f = 0; f < numFrames; ++f) {
        size_t startSample = f * HOP_LENGTH;
        
        // 1. Windowed frame
        for (int i = 0; i < N_FFT; ++i) {
            float s = (startSample + i < numSamples) ? samples[startSample + i] : 0.0f;
            fftBuffer[i] = std::complex<float>(s * hannWindow_[i], 0.0f);
        }

        // 2. Discrete Fourier Transform (DFT) for N_FFT / 2 + 1 positive bins
        for (int k = 0; k <= N_FFT / 2; ++k) {
            std::complex<float> sum(0.0f, 0.0f);
            for (int n = 0; n < N_FFT; ++n) {
                float angle = -2.0f * PI * k * n / N_FFT;
                sum += fftBuffer[n] * std::complex<float>(std::cos(angle), std::sin(angle));
            }
            powerSpectrum[k] = (sum.real() * sum.real() + sum.imag() * sum.imag());
        }

        // 3. Mel Filterbank Multiplication & Log-Compression
        for (int m = 0; m < N_MELS; ++m) {
            float melEnergy = 0.0f;
            for (int k = 0; k <= N_FFT / 2; ++k) {
                melEnergy += melFilters_[m][k] * powerSpectrum[k];
            }
            float logMel = std::log10(std::max(melEnergy, 1e-5f));
            // Whisper normalization: (log_mel + 4.0) / 4.0
            melSpectrogram[m * numFrames + f] = (logMel + 4.0f) / 4.0f;
        }
    }

    return melSpectrogram;
}

} // namespace s2s
