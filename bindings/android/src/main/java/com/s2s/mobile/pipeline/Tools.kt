package com.s2s.mobile.pipeline

/**
 * A device capability the assistant may invoke — set a timer, toggle the torch,
 * read the battery. Ported from the C++ `tool_registry.h` contract.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    /** Parameter name to human description. Kept flat; voice commands rarely nest. */
    val parameters: Map<String, String> = emptyMap(),
)

/** A parsed request from the model to run a tool. */
data class ToolCall(val name: String, val arguments: Map<String, String>)

/** Outcome of a tool call, fed back to the model as context for its spoken reply. */
data class ToolResult(val name: String, val output: String, val isError: Boolean = false)

/** Implementation of a single tool. Runs on a worker thread; keep it quick. */
fun interface ToolFunction {
    operator fun invoke(arguments: Map<String, String>): String
}

/**
 * Registry of callable tools.
 *
 * The model is told what exists via [promptSection], and its output is scanned
 * by [parse]. Keeping both here means the prompt format and the parser cannot
 * drift apart.
 */
interface Tools {
    val definitions: List<ToolDefinition>

    fun register(definition: ToolDefinition, function: ToolFunction)

    fun unregister(name: String)

    /** System-prompt fragment describing the available tools, or null if none. */
    fun promptSection(): String?

    /** Extracts a tool call from model output, or null if it is plain speech. */
    fun parse(text: String): ToolCall?

    fun execute(call: ToolCall): ToolResult
}
