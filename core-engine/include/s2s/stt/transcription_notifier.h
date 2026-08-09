#pragma once

#include "s2s/types.h"
#include <functional>
#include <string>

namespace s2s {
namespace stt {

/**
 * @brief Live transcription notifier for real-time speech visualization.
 * Synchronized with Python speech_to_speech/STT/transcription_notifier.py.
 */
class TranscriptionNotifier {
public:
    using Callback = std::function<void(const std::string& text, bool isFinal)>;

    explicit TranscriptionNotifier(Callback cb = nullptr)
        : callback_(std::move(cb))
    {}

    void setCallback(Callback cb) {
        callback_ = std::move(cb);
    }

    void notifyPartial(const std::string& partialText) {
        if (callback_ && !partialText.empty()) {
            callback_(partialText, false);
        }
    }

    void notifyFinal(const std::string& finalText) {
        if (callback_ && !finalText.empty()) {
            callback_(finalText, true);
        }
    }

private:
    Callback callback_;
};

} // namespace stt
} // namespace s2s
