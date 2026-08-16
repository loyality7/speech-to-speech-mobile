# Usage & Tool Calling Guide

This guide covers advanced usage topics including tool calling registration, barge-in handling, and state serialization.

---

## 1. Registering On-Device Tools

Register custom Kotlin function handlers using `ToolDefinition`:

```kotlin
val weatherTool = ToolDefinition(
    name = "get_weather",
    description = "Returns current temperature for a city",
    parameters = mapOf("city" to "string")
)

engine.registerTool(weatherTool) { args ->
    val city = args["city"] ?: "unknown"
    "The current weather in $city is 22°C and sunny."
}
```

Tool calling recursion is automatically capped at `MAX_TOOL_RECURSION_DEPTH = 3` to prevent infinite LLM tool loops.

---

## 2. Handling Memory Pressure

In your host Activity or Service, delegate memory trim callbacks to `S2SEngine`:

```kotlin
override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    engine.onTrimMemory(level)
}
```

If memory trimming is requested during active LLM generation, purging is deferred automatically to the end of the generation turn.

---

## 3. Conversation State Persistence

Save and restore chat context across Android process deaths:

```kotlin
// Save conversation state to JSON
val jsonState: String = engine.saveConversationState()

// Restore conversation state from JSON
engine.restoreConversationState(jsonState)
```
