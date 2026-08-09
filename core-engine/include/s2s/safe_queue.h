#pragma once

#include <queue>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <optional>

namespace s2s {

template <typename T>
class SafeQueue {
public:
    explicit SafeQueue(size_t maxSize = 1000) : maxSize_(maxSize), stopped_(false) {}

    void push(T value) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (stopped_) return;
        
        // If queue exceeds max size, drop oldest item to maintain real-time latency
        if (queue_.size() >= maxSize_) {
            queue_.pop();
        }
        
        queue_.push(std::move(value));
        cond_.notify_one();
    }

    std::optional<T> pop(int timeoutMs = 1000) {
        return popWithTimeout(timeoutMs);
    }

    std::optional<T> popWithTimeout(int timeoutMs) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!cond_.wait_for(lock, std::chrono::milliseconds(timeoutMs), [this]() {
            return !queue_.empty() || stopped_;
        })) {
            return std::nullopt;
        }

        if (stopped_ && queue_.empty()) {
            return std::nullopt;
        }

        if (queue_.empty()) {
            return std::nullopt;
        }

        T item = std::move(queue_.front());
        queue_.pop();
        return item;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        std::queue<T> empty;
        std::swap(queue_, empty);
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        stopped_ = true;
        cond_.notify_all();
    }

    void restart() {
        std::lock_guard<std::mutex> lock(mutex_);
        stopped_ = false;
    }

    size_t size() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return queue_.size();
    }

    bool empty() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return queue_.empty();
    }

private:
    mutable std::mutex mutex_;
    std::condition_variable cond_;
    std::queue<T> queue_;
    size_t maxSize_;
    bool stopped_;
};

} // namespace s2s
