package com.example.s2splugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleContextPluginTest {

    @Test
    fun `identity is stable`() {
        val plugin = ExampleContextPlugin(ExampleContextConfig())
        assertEquals("example-context-plugin", plugin.id)
        assertEquals(listOf("ContextEngine"), plugin.capabilities)
    }

    @Test
    fun `messages include system prompt and turns`() {
        val plugin = ExampleContextPlugin(ExampleContextConfig(systemPrompt = "be terse"))
        plugin.addUser("hi")
        plugin.addAssistant("hello")

        val messages = plugin.messages()
        assertEquals("system", messages[0].role)
        assertTrue(messages[0].content.contains("be terse"))
        assertEquals(3, messages.size)
    }

    @Test
    fun `oldest turns drop past maxTurns`() {
        val plugin = ExampleContextPlugin(ExampleContextConfig(maxTurns = 2))
        plugin.addUser("first")
        plugin.addAssistant("first reply")
        plugin.addUser("second")

        val turnsOnly = plugin.messages().drop(1) // drop the system message
        assertEquals(2, turnsOnly.size)
        assertEquals("second", turnsOnly.last().content)
    }

    @Test
    fun `round trips through json`() {
        val plugin = ExampleContextPlugin(ExampleContextConfig())
        plugin.addUser("hi")
        plugin.addAssistant("hello")
        val json = plugin.toJson()

        val restored = ExampleContextPlugin(ExampleContextConfig())
        restored.fromJson(json)
        assertEquals(plugin.messages().drop(1), restored.messages().drop(1))
    }
}
