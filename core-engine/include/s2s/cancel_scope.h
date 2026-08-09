#pragma once

#include "s2s/types.h"
#include <atomic>
#include <functional>

namespace s2s {

class CancelScope {
public:
    CancelScope();

    // Increments generation ID, invalidating all in-flight generation tasks
    GenerationId cancelCurrentAndIncrement();
    GenerationId cancel(); // Alias for cancelCurrentAndIncrement

    // Returns current generation ID
    GenerationId currentGeneration() const;
    GenerationId getGeneration() const; // Alias for currentGeneration

    // Checks if a generation ID is stale (superseded by a newer turn)
    bool isStale(GenerationId id) const;

    // Track active speaking state to gate microphone echo
    void setSpeaking(bool speaking);
    bool isSpeaking() const;

    // Optional cancellation listener
    void setOnCancelListener(std::function<void(GenerationId)> listener);

private:
    std::atomic<GenerationId> currentGen_{1};
    std::atomic<bool> isSpeaking_{false};
    std::function<void(GenerationId)> onCancel_;
};

} // namespace s2s
