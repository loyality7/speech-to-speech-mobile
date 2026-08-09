#include "s2s/llm_handler.h"
#include "s2s/llm/llama_engine.h"
#include <iostream>
#include <sstream>
#include <thread>
#include <chrono>
#include <vector>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32.lib")
#else
#include <sys/socket.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <netdb.h>
#endif

namespace s2s {

// Simple JSON string escape helper
static std::string escapeJson(const std::string& input) {
    std::ostringstream ss;
    for (char c : input) {
        switch (c) {
            case '"': ss << "\\\""; break;
            case '\\': ss << "\\\\"; break;
            case '\b': ss << "\\b"; break;
            case '\f': ss << "\\f"; break;
            case '\n': ss << "\\n"; break;
            case '\r': ss << "\\r"; break;
            case '\t': ss << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    // Control chars
                } else {
                    ss << c;
                }
                break;
        }
    }
    return ss.str();
}

LLMHandler::LLMHandler(
    std::shared_ptr<SafeQueue<STTTranscript>> queueIn,
    std::shared_ptr<SafeQueue<LLMToken>> queueOut,
    std::shared_ptr<CancelScope> cancelScope,
    const EngineConfig& config,
    std::shared_ptr<ChatHistory> chatHistory,
    std::shared_ptr<ToolRegistry> toolRegistry
)
    : BaseHandler("LLMHandler", queueIn, queueOut, cancelScope)
    , config_(config)
    , chatHistory_(chatHistory ? chatHistory : std::make_shared<ChatHistory>(config.llm.systemPrompt))
    , toolRegistry_(toolRegistry ? toolRegistry : std::make_shared<ToolRegistry>())
{
#ifdef _WIN32
    WSADATA wsaData;
    WSAStartup(MAKEWORD(2, 2), &wsaData);
#endif
}

LLMHandler::~LLMHandler() {
    stop();
#ifdef _WIN32
    WSACleanup();
#endif
}

bool LLMHandler::initialize() {
    std::string modelName = config_.llm.modelName.empty() ? "minicpm-v4.6:latest" : config_.llm.modelName;
    std::cout << "[LLMHandler] Initialized Real-Time LLM Client with Ollama Model: " 
              << modelName << " (Ollama Endpoint: " << config_.llm.endpoint << ")" << std::endl;
    return true;
}

void LLMHandler::process(STTTranscript transcript) {
    if (cancelScope_ && cancelScope_->isStale(transcript.generationId)) {
        return;
    }

    std::string modelName = config_.llm.modelName.empty() ? "minicpm-v4.6:latest" : config_.llm.modelName;
    std::cout << "[LLMHandler] Streaming prompt to Ollama [" << modelName << "]: \"" 
              << transcript.text << "\" (Gen: " << transcript.generationId << ")" << std::endl;

    // 1. Add to Chat History
    chatHistory_->addUserMessage(transcript.text);

    // 2. Build Chat Messages JSON Array
    std::ostringstream jsonStream;
    jsonStream << "{\"model\":\"" << modelName << "\",\"messages\":[";
    jsonStream << "{\"role\":\"system\",\"content\":\"" 
               << escapeJson(config_.llm.systemPrompt.empty() ? 
                  "You are a direct voice assistant. Reply in one short spoken sentence with NO thinking tags." : 
                  config_.llm.systemPrompt) 
               << "\"}";

    for (const auto& msg : chatHistory_->getMessages()) {
        jsonStream << ",{\"role\":\"" << msg.role << "\",\"content\":\"" << escapeJson(msg.content) << "\"}";
    }
    jsonStream << "],\"stream\":true}";
    std::string jsonBody = jsonStream.str();

    // 3. Connect to local Ollama server at 127.0.0.1:11434 via streaming TCP socket
#ifdef _WIN32
    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        std::cerr << "[LLMHandler] Failed to create socket." << std::endl;
        return;
    }
#else
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        std::cerr << "[LLMHandler] Failed to create socket." << std::endl;
        return;
    }
#endif

    sockaddr_in serverAddr{};
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(11434);
    inet_pton(AF_INET, "127.0.0.1", &serverAddr.sin_addr);

    if (connect(sock, reinterpret_cast<sockaddr*>(&serverAddr), sizeof(serverAddr)) != 0) {
        std::cerr << "[LLMHandler] Cannot connect to Ollama at 127.0.0.1:11434." << std::endl;
#ifdef _WIN32
        closesocket(sock);
#else
        close(sock);
#endif
        return;
    }

    std::string httpRequest = 
        "POST /api/chat HTTP/1.1\r\n"
        "Host: 127.0.0.1:11434\r\n"
        "Content-Type: application/json\r\n"
        "Content-Length: " + std::to_string(jsonBody.length()) + "\r\n"
        "Connection: close\r\n\r\n" + jsonBody;

    send(sock, httpRequest.c_str(), static_cast<int>(httpRequest.length()), 0);

    // Stream response chunks
    char recvBuffer[4096];
    std::string lineBuffer;
    std::string fullResponse;
    bool headersPassed = false;
    bool inThinkingBlock = false;

    while (true) {
        // Atomic Barge-in check on EVERY read!
        if (cancelScope_ && cancelScope_->isStale(transcript.generationId)) {
            std::cout << "[LLMHandler] >>> Interrupted! Closing Ollama socket immediately for Gen " 
                      << transcript.generationId << " <<<" << std::endl;
            break;
        }

        int bytesReceived = recv(sock, recvBuffer, sizeof(recvBuffer) - 1, 0);
        if (bytesReceived <= 0) break;

        recvBuffer[bytesReceived] = '\0';
        lineBuffer.append(recvBuffer, bytesReceived);

        // Skip HTTP headers
        if (!headersPassed) {
            size_t headerEnd = lineBuffer.find("\r\n\r\n");
            if (headerEnd != std::string::npos) {
                headersPassed = true;
                lineBuffer = lineBuffer.substr(headerEnd + 4);
            } else {
                continue;
            }
        }

        // Process line-by-line JSON streams from Ollama
        size_t newlinePos;
        while ((newlinePos = lineBuffer.find('\n')) != std::string::npos) {
            std::string line = lineBuffer.substr(0, newlinePos);
            lineBuffer = lineBuffer.substr(newlinePos + 1);

            // Extract "content":"..." from chat format or "response":"..." from generate format
            size_t tokenPos = line.find("\"content\":\"");
            size_t offset = 11;
            if (tokenPos == std::string::npos) {
                tokenPos = line.find("\"response\":\"");
                offset = 12;
            }

            if (tokenPos != std::string::npos) {
                size_t start = tokenPos + offset;
                std::string tokenText;
                for (size_t i = start; i < line.length(); ++i) {
                    if (line[i] == '\\' && i + 1 < line.length()) {
                        if (line[i+1] == '"') { tokenText += '"'; i++; }
                        else if (line[i+1] == 'n') { tokenText += '\n'; i++; }
                        else if (line[i+1] == 't') { tokenText += '\t'; i++; }
                        else if (line[i+1] == '\\') { tokenText += '\\'; i++; }
                        else { tokenText += line[i]; }
                    } else if (line[i] == '"') {
                        break;
                    } else {
                        tokenText += line[i];
                    }
                }

                if (!tokenText.empty()) {
                    // Check for thinking blocks
                    if (tokenText.find("<think>") != std::string::npos || tokenText.find("\\u003cthink\\u003e") != std::string::npos) {
                        inThinkingBlock = true;
                        continue;
                    }
                    if (tokenText.find("</think>") != std::string::npos || tokenText.find("\\u003c/think\\u003e") != std::string::npos) {
                        inThinkingBlock = false;
                        continue;
                    }

                    if (inThinkingBlock) {
                        continue;
                    }

                    fullResponse += tokenText;
                    std::cout << tokenText << std::flush;

                    LLMToken token;
                    token.text = tokenText;
                    token.generationId = transcript.generationId;
                    token.isFinal = false;

                    queueOut_->push(token);
                }
            }

            // Check if generation completed
            if (line.find("\"done\":true") != std::string::npos) {
                break;
            }
        }
    }

#ifdef _WIN32
    closesocket(sock);
#else
    close(sock);
#endif

    std::cout << "\n";

    // Finalize generation
    if (!cancelScope_ || !cancelScope_->isStale(transcript.generationId)) {
        LLMToken finalToken;
        finalToken.text = "";
        finalToken.generationId = transcript.generationId;
        finalToken.isFinal = true;
        queueOut_->push(finalToken);

        chatHistory_->addAssistantMessage(fullResponse);
        std::cout << "[LLMHandler] Completed full turn response from Ollama." << std::endl;
    }
}

void LLMHandler::cleanup() {
    std::cout << "[LLMHandler] Cleanup completed." << std::endl;
}

} // namespace s2s
