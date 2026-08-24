package com.s2s.mobile.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

class ChatHistoryTest {

    @Test
    fun testBasicMessageFlow() {
        val history = ChatHistory("System initial", keepTurns = 6)
        history.addUser("Hello assistant")
        history.addAssistant("Hello user")

        val messages = history.messages()
        assertEquals(3, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals("System initial", messages[0].content)
        assertEquals("user", messages[1].role)
        assertEquals("Hello assistant", messages[1].content)
        assertEquals("assistant", messages[2].role)
        assertEquals("Hello user", messages[2].content)
    }

    @Test
    fun testReplaceLastUser() {
        val history = ChatHistory("System prompt")
        history.addUser("Hello I am thinking...")
        history.replaceLastUser("Hello I am thinking about weather.")

        val messages = history.messages()
        assertEquals(2, messages.size)
        assertEquals("Hello I am thinking about weather.", messages[1].content)
    }

    @Test
    fun testToolResultFormatting() {
        val history = ChatHistory("System prompt")
        history.addToolResult("get_weather", "Sunny 25C")

        val messages = history.messages()
        assertEquals(2, messages.size)
        assertEquals("user", messages[1].role)
        assertEquals("[tool get_weather returned] Sunny 25C", messages[1].content)
    }

    @Test
    fun testCompactionRollingWindow() {
        // keepTurns = 2 -> max turns before compaction = 4
        val history = ChatHistory("System prompt", keepTurns = 2, compact = true)
        
        for (i in 1..5) {
            history.addUser("User message $i")
            history.addAssistant("Assistant response $i")
        }

        val messages = history.messages()
        // Should contain System prompt + System summary + 4 retained turn messages (2 turns)
        assertTrue(messages.any { it.role == "system" && it.content.contains("Earlier in this conversation:") })
        assertEquals(6, messages.size) // 1 system, 1 summary system, 4 verbatim messages
    }

    @Test
    fun testCompactionNeverStrandsLeadingAssistant() {
        // Real usage appends one message at a time (addUser, then later
        // addAssistant), not in pairs — so overflow can be triggered mid-pair.
        // Trimming an odd count off the front then stranded the matching
        // assistant message as the new head. llama.cpp tolerates a
        // system/assistant/user/... prompt silently; some stricter chat templates
        // hard-reject a conversation that doesn't start with user.
        val history = ChatHistory("System prompt", keepTurns = 2, compact = true)
        repeat(5) { i ->
            history.addUser("User message $i")
            history.messages().filterNot { it.role == "system" }
                .firstOrNull()?.let { assertEquals("user", it.role) }

            history.addAssistant("Assistant response $i")
            history.messages().filterNot { it.role == "system" }
                .firstOrNull()?.let { assertEquals("user", it.role) }
        }
    }

    @Test
    fun testClear() {
        val history = ChatHistory("System prompt")
        history.addUser("Hello")
        history.addAssistant("Hi")
        history.clear()

        val messages = history.messages()
        assertEquals(1, messages.size)
        assertEquals("System prompt", messages[0].content)
    }

    @Test
    fun testThreadSafety() {
        val history = ChatHistory("System prompt")
        val latch = CountDownLatch(10)

        for (i in 1..10) {
            Thread {
                history.addUser("User $i")
                history.addAssistant("Assistant $i")
                history.messages()
                latch.countDown()
            }.start()
        }

        latch.await()
        assertTrue(history.messages().isNotEmpty())
    }

    @Test
    fun testDropLastUserIfUnanswered() {
        val history = ChatHistory("System prompt")
        history.addUser("First question")
        history.addAssistant("First answer")
        history.addUser("Interrupted question")

        // Before drop: 4 messages (system + 1st QA + interrupted question)
        assertEquals(4, history.messages().size)

        history.dropLastUserIfUnanswered()

        // After drop: 3 messages (system + 1st QA). Interrupted user question removed.
        val messages = history.messages()
        assertEquals(3, messages.size)
        assertEquals("assistant", messages.last().role)
        assertEquals("First answer", messages.last().content)
    }

    @Test
    fun testToJsonAndFromJsonSerialization() {
        val history = ChatHistory("Custom system prompt", keepTurns = 6)
        history.addUser("What is the capital of France?")
        history.addAssistant("The capital of France is Paris.")

        val json = history.toJson()
        assertTrue(json.contains("Custom system prompt"))
        assertTrue(json.contains("capital of France"))

        val restoredHistory = ChatHistory("Initial prompt")
        restoredHistory.fromJson(json)

        val restoredMessages = restoredHistory.messages()
        assertEquals(3, restoredMessages.size)
        assertEquals("Custom system prompt", restoredMessages[0].content)
        assertEquals("user", restoredMessages[1].role)
        assertEquals("What is the capital of France?", restoredMessages[1].content)
        assertEquals("assistant", restoredMessages[2].role)
        assertEquals("The capital of France is Paris.", restoredMessages[2].content)
    }
}
