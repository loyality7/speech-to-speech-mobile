package com.example.s2stools

import com.s2s.mobile.pipeline.ToolContext
import com.s2s.mobile.pipeline.ToolDefinition
import com.s2s.mobile.pipeline.ToolFunction
import com.s2s.mobile.pipeline.ToolParameter
import com.s2s.mobile.pipeline.Tools
import java.util.concurrent.ConcurrentHashMap

/**
 * Reference stateful tool: `open` → `click` → `read`, where each call after
 * `open` must act on the same page `open` created.
 *
 * Stands in for a real webdroid-backed tool. The pattern is what matters here,
 * not the fake page model: key session state on [ToolContext.sessionId], never
 * on anything a tool invents itself. `S2SEngine` generates that ID once per
 * conversation and passes it down through every tool call — see this
 * template's README for why that's core's job, not this plugin's.
 *
 * One [FakePage] per session, held in a map. A real implementation would hold
 * a `webdroid.Page` here instead. Nothing here is torn down automatically —
 * a production tool wired to a real browser session needs its own idle-session
 * eviction; deliberately left out of this template to keep the isolation
 * pattern the only thing on screen.
 */
object BrowserTool {

    /** Stand-in for `dev.webdroid.Page` — enough state to prove isolation, nothing more. */
    private class FakePage(var url: String? = null, var lastClicked: String? = null)

    private val sessions = ConcurrentHashMap<String, FakePage>()

    val openDefinition = ToolDefinition(
        name = "browse_open",
        description = "Opens a page in the browser session.",
        schema = mapOf("url" to ToolParameter(description = "URL to open", type = "string")),
    )

    val clickDefinition = ToolDefinition(
        name = "browse_click",
        description = "Clicks an element on the currently open page.",
        schema = mapOf("selector" to ToolParameter(description = "CSS selector to click", type = "string")),
    )

    val readDefinition = ToolDefinition(
        name = "browse_read",
        description = "Reads the state of the currently open page in this session.",
    )

    private val openFunction = ToolFunction { context: ToolContext, arguments ->
        val url = arguments["url"] ?: return@ToolFunction "Missing 'url' argument"
        sessions[context.sessionId] = FakePage(url = url)
        "opened $url"
    }

    private val clickFunction = ToolFunction { context: ToolContext, arguments ->
        val selector = arguments["selector"] ?: return@ToolFunction "Missing 'selector' argument"
        val page = sessions[context.sessionId]
            ?: return@ToolFunction "No page open in this session — call browse_open first"
        page.lastClicked = selector
        "clicked $selector"
    }

    private val readFunction = ToolFunction { context: ToolContext, _ ->
        val page = sessions[context.sessionId]
            ?: return@ToolFunction "No page open in this session"
        "url=${page.url}, lastClicked=${page.lastClicked ?: "none"}"
    }

    /** Registers all three tools — they must be registered together, they share session state. */
    fun registerOn(tools: Tools) {
        tools.register(openDefinition, openFunction)
        tools.register(clickDefinition, clickFunction)
        tools.register(readDefinition, readFunction)
    }

    /**
     * Frees a session's page. Call when a conversation ends — nothing in the
     * [Tools] contract calls this automatically; the host (or a future
     * session-lifecycle hook on [Tools]) owns cleanup timing.
     */
    fun closeSession(sessionId: String) {
        sessions.remove(sessionId)
    }
}
