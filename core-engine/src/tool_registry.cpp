#include "s2s/tool_registry.h"
#include <sstream>

namespace s2s {

ToolRegistry::ToolRegistry() {}

void ToolRegistry::registerTool(const ToolDefinition& def, ToolFunction func) {
    std::lock_guard<std::mutex> lock(mutex_);
    definitions_[def.name] = def;
    functions_[def.name] = std::move(func);
}

bool ToolRegistry::hasTool(const std::string& name) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return definitions_.find(name) != definitions_.end();
}

std::string ToolRegistry::executeTool(const std::string& name, const std::unordered_map<std::string, std::string>& args) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = functions_.find(name);
    if (it != functions_.end()) {
        return it->second(args);
    }
    return "Error: Tool '" + name + "' not found.";
}

std::string ToolRegistry::generateToolPrompt() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (definitions_.empty()) return "";

    std::ostringstream oss;
    oss << "\nAvailable Mobile Tools:\n";
    for (const auto& [name, def] : definitions_) {
        oss << "- " << name << ": " << def.description << "\n";
    }
    return oss.str();
}

} // namespace s2s
