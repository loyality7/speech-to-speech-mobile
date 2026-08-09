#include "s2s/llm/tool_registry.h"
#include <iostream>
#include <cassert>

namespace s2s {
namespace test {

bool testToolRegistrationAndExecution() {
    std::cout << "[TEST] Running testToolRegistrationAndExecution..." << std::endl;

    ToolRegistry registry;

    ToolDefinition weatherTool;
    weatherTool.name = "get_weather";
    weatherTool.description = "Get current weather for a city";
    weatherTool.parameters = {
        {"city", "string", "City name e.g. London", true},
        {"unit", "string", "Temperature unit: c or f", false}
    };

    registry.registerTool(weatherTool, [](const std::unordered_map<std::string, std::string>& args) -> std::string {
        auto it = args.find("city");
        std::string city = (it != args.end()) ? it->second : "Unknown";
        return "{\"city\":\"" + city + "\", \"temp\": 22, \"condition\": \"Sunny\"}";
    });

    assert(registry.hasTool("get_weather"));
    assert(!registry.hasTool("invalid_tool"));

    std::unordered_map<std::string, std::string> callArgs = { {"city", "Paris"} };
    std::string result = registry.executeTool("get_weather", callArgs);
    assert(result.find("Paris") != std::string::npos);
    assert(result.find("Sunny") != std::string::npos);

    std::string prompt = registry.generateToolPrompt();
    assert(prompt.find("get_weather") != std::string::npos);
    assert(prompt.find("Get current weather for a city") != std::string::npos);

    std::cout << "  -> testToolRegistrationAndExecution PASSED!" << std::endl;
    return true;
}

} // namespace test
} // namespace s2s
