#pragma once

#include <string>
#include <vector>
#include <functional>
#include <unordered_map>
#include <mutex>

namespace s2s {

struct ToolParameter {
    std::string name;
    std::string type; // "string", "number", "boolean"
    std::string description;
    bool required = true;
};

struct ToolDefinition {
    std::string name;
    std::string description;
    std::vector<ToolParameter> parameters;
};

using ToolFunction = std::function<std::string(const std::unordered_map<std::string, std::string>& args)>;

/**
 * @brief Tool & Function Call registry for on-device actions.
 * Synchronized with Python speech_to_speech/LLM/tool_call/.
 */
class ToolRegistry {
public:
    ToolRegistry();

    void registerTool(const ToolDefinition& def, ToolFunction func);
    bool hasTool(const std::string& name) const;
    std::string executeTool(const std::string& name, const std::unordered_map<std::string, std::string>& args);

    // Generates tool calling system prompt instructions for LLM
    std::string generateToolPrompt() const;

private:
    std::unordered_map<std::string, ToolDefinition> definitions_;
    std::unordered_map<std::string, ToolFunction> functions_;
    mutable std::mutex mutex_;
};

} // namespace s2s
