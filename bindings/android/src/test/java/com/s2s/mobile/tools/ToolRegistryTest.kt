package com.s2s.mobile.tools

import com.s2s.mobile.pipeline.ToolCall
import com.s2s.mobile.pipeline.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    @Test
    fun testRegisterAndExecuteTool() {
        val registry = ToolRegistry()
        val def = ToolDefinition(
            name = "get_weather",
            description = "Gets weather for location",
            parameters = mapOf("location" to "City name"),
        )
        registry.register(def) { args ->
            "Weather in ${args["location"]} is sunny"
        }

        val parsed = registry.parse("{\"tool\": \"get_weather\", \"arguments\": {\"location\": \"NYC\"}}")
        assertNotNull(parsed)
        assertEquals("get_weather", parsed?.name)
        assertEquals("NYC", parsed?.arguments?.get("location"))

        val result = registry.execute(ToolCall("get_weather", mapOf("location" to "NYC")))
        assertFalse(result.isError)
        assertEquals("Weather in NYC is sunny", result.output)
    }

    @Test
    fun testExecuteUnknownTool() {
        val registry = ToolRegistry()
        val result = registry.execute(ToolCall("unknown_action", emptyMap()))
        assertTrue(result.isError)
        assertEquals("No such tool: unknown_action", result.output)
    }

    @Test
    fun testParseFlatJson() {
        val registry = ToolRegistry()
        val json = "{\"name\": \"test_tool\", \"value\": \"123\"}"
        val parsed = registry.parseFlatJson(json)
        assertNotNull(parsed)
        assertEquals("test_tool", parsed?.get("name"))
        assertEquals("123", parsed?.get("value"))
    }
}
