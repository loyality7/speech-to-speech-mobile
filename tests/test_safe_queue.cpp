#include "s2s/safe_queue.h"
#include <iostream>
#include <thread>
#include <vector>
#include <cassert>

namespace s2s {
namespace test {

bool testSafeQueueConcurrency() {
    std::cout << "[TEST] Running testSafeQueueConcurrency..." << std::endl;

    SafeQueue<int> queue(1000);
    assert(queue.empty());
    assert(queue.size() == 0);

    const int itemCount = 100;
    std::vector<int> receivedItems;
    std::mutex mtx;

    // Producer thread
    std::thread producer([&]() {
        for (int i = 1; i <= itemCount; ++i) {
            queue.push(i);
            std::this_thread::sleep_for(std::chrono::microseconds(100));
        }
    });

    // Consumer thread
    std::thread consumer([&]() {
        for (int i = 1; i <= itemCount; ++i) {
            auto val = queue.pop(500);
            assert(val.has_value());
            std::lock_guard<std::mutex> lock(mtx);
            receivedItems.push_back(*val);
        }
    });

    producer.join();
    consumer.join();

    assert(receivedItems.size() == itemCount);
    for (int i = 0; i < itemCount; ++i) {
        assert(receivedItems[i] == i + 1);
    }

    std::cout << "  -> testSafeQueueConcurrency PASSED! (" << itemCount << " items streamed across threads)" << std::endl;
    return true;
}

} // namespace test
} // namespace s2s
