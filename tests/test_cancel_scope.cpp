#include "s2s/cancel_scope.h"
#include <iostream>
#include <cassert>

namespace s2s {
namespace test {

bool testCancelScopeBargeIn() {
    std::cout << "[TEST] Running testCancelScopeBargeIn..." << std::endl;

    CancelScope scope;
    assert(scope.getGeneration() == 1);
    assert(!scope.isSpeaking());

    // Bump generation for turn 1 -> 2
    uint32_t gen2 = scope.cancelCurrentAndIncrement();
    assert(gen2 == 2);
    assert(scope.getGeneration() == 2);
    assert(!scope.isStale(2));
    assert(scope.isStale(1)); // Generation 1 is now stale

    // Set engine speaking
    scope.setSpeaking(true);
    assert(scope.isSpeaking());

    // User interrupts (Barge-in)! Bump generation to 3
    uint32_t gen3 = scope.cancel();
    assert(gen3 == 3);
    assert(!scope.isStale(3));
    assert(scope.isStale(gen2)); // Generation 2 is cancelled/stale!

    std::cout << "  -> testCancelScopeBargeIn PASSED!" << std::endl;
    return true;
}

} // namespace test
} // namespace s2s
