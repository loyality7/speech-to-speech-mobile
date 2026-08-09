#pragma once

#include "s2s/safe_queue.h"
#include "s2s/cancel_scope.h"
#include <thread>
#include <atomic>
#include <memory>
#include <string>

namespace s2s {

template <typename InT, typename OutT>
class BaseHandler {
public:
    BaseHandler(
        std::shared_ptr<SafeQueue<InT>> queueIn,
        std::shared_ptr<SafeQueue<OutT>> queueOut,
        std::shared_ptr<CancelScope> cancelScope,
        std::string name
    ) : queueIn_(std::move(queueIn)),
        queueOut_(std::move(queueOut)),
        cancelScope_(std::move(cancelScope)),
        name_(std::move(name)),
        running_(false) {}

    BaseHandler(
        std::string name,
        std::shared_ptr<SafeQueue<InT>> queueIn,
        std::shared_ptr<SafeQueue<OutT>> queueOut,
        std::shared_ptr<CancelScope> cancelScope
    ) : queueIn_(std::move(queueIn)),
        queueOut_(std::move(queueOut)),
        cancelScope_(std::move(cancelScope)),
        name_(std::move(name)),
        running_(false) {}

    virtual ~BaseHandler() {
        stop();
    }

    virtual bool initialize() = 0;

    void start() {
        if (running_) return;
        running_ = true;
        workerThread_ = std::thread(&BaseHandler::runLoop, this);
    }

    void stop() {
        if (!running_) return;
        running_ = false;
        if (queueIn_) queueIn_->stop();
        if (workerThread_.joinable()) {
            workerThread_.join();
        }
        cleanup();
    }

    bool isRunning() const { return running_; }
    const std::string& name() const { return name_; }

    virtual void onSessionEnd() {}

protected:
    virtual void process(InT item) = 0;
    virtual void cleanup() {}

    void emitOutput(OutT output) {
        if (queueOut_ && running_) {
            queueOut_->push(std::move(output));
        }
    }

    std::shared_ptr<SafeQueue<InT>> queueIn_;
    std::shared_ptr<SafeQueue<OutT>> queueOut_;
    std::shared_ptr<CancelScope> cancelScope_;
    std::string name_;
    std::atomic<bool> running_;
    std::thread workerThread_;

private:
    void runLoop() {
        while (running_) {
            if (!queueIn_) break;
            auto item = queueIn_->popWithTimeout(50);
            if (item.has_value()) {
                try {
                    process(std::move(item.value()));
                } catch (const std::exception& e) {
                    // Safe error recovery: catch handler exceptions without crashing pipeline thread
                    (void)e;
                } catch (...) {
                    // Unknown exception safety
                }
            }
        }
    }
};

} // namespace s2s
