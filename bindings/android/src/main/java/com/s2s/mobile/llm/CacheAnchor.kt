package com.s2s.mobile.llm

import com.s2s.mobile.pipeline.ChatMessage

/**
 * Finds where the KV cache ends in the current conversation.
 *
 * Pulled out of [LlamaLanguageModel] so it can be tested: the rest of that class
 * needs a loaded llama.cpp, and this one decision is what silently cost seconds
 * per turn when it got it wrong. A mismatch is not an error — it just quietly
 * re-prefills the whole conversation, forever.
 */
internal object CacheAnchor {

    /**
     * Index of the last message the cache already holds, or -1 if it cannot be
     * found and the prompt must be rebuilt from scratch.
     *
     * Compares trimmed. The tail is whatever the model generated, and the engine
     * commits that text through paths that do not agree on whitespace — a
     * completed reply is stored verbatim, a barge-in stores `partialReply.trim()`
     * — so an exact comparison fails on a trailing newline the model emitted.
     */
    fun indexIn(messages: List<ChatMessage>, tail: String?): Int {
        val needle = tail?.trim()?.takeIf { it.isNotEmpty() } ?: return -1
        return messages.indexOfLast { it.content.trim() == needle }
    }
}
