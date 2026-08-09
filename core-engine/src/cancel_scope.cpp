#include "s2s/cancel_scope.h"

namespace s2s {

CancelScope::CancelScope() : currentGen_(1) {}

GenerationId CancelScope::cancelCurrentAndIncrement() {
    GenerationId newGen = ++currentGen_;
    if (onCancel_) {
        onCancel_(newGen);
    }
    return newGen;
}

GenerationId CancelScope::cancel() {
    return cancelCurrentAndIncrement();
}

GenerationId CancelScope::currentGeneration() const {
    return currentGen_.load();
}

GenerationId CancelScope::getGeneration() const {
    return currentGeneration();
}

bool CancelScope::isStale(GenerationId id) const {
    return id != currentGen_.load();
}

void CancelScope::setSpeaking(bool speaking) {
    isSpeaking_.store(speaking);
}

bool CancelScope::isSpeaking() const {
    return isSpeaking_.load();
}

void CancelScope::setOnCancelListener(std::function<void(GenerationId)> listener) {
    onCancel_ = std::move(listener);
}

} // namespace s2s
