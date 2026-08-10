package com.s2s.mobile.tools

import com.s2s.mobile.pipeline.ToolCall
import com.s2s.mobile.pipeline.ToolDefinition
import com.s2s.mobile.pipeline.ToolFunction
import com.s2s.mobile.pipeline.ToolResult
import com.s2s.mobile.pipeline.Tools
import java.util.concurrent.ConcurrentHashMap

/**
 * Native tool / function calling. Port of the C++ `tool_registry.h` contract.
 *
 * Small local models follow one flat JSON shape far more reliably than the
 * nested OpenAI schema, so the prompt asks for exactly one form and the parser
 * accepts only that form. JSON is read by hand rather than through `org.json`,
 * which is a stub in JVM unit tests — this way the parsing stays testable
 * off-device.
 */
class ToolRegistry : Tools {

    private val functions = ConcurrentHashMap<String, ToolFunction>()
    private val defs = ConcurrentHashMap<String, ToolDefinition>()

    override val definitions: List<ToolDefinition>
        get() = defs.values.sortedBy { it.name }

    override fun register(definition: ToolDefinition, function: ToolFunction) {
        defs[definition.name] = definition
        functions[definition.name] = function
    }

    override fun unregister(name: String) {
        defs.remove(name)
        functions.remove(name)
    }

    override fun promptSection(): String? {
        if (defs.isEmpty()) return null
        return buildString {
            appendLine("You can call these tools:")
            definitions.forEach { d ->
                append("- ").append(d.name).append(": ").append(d.description)
                if (d.parameters.isNotEmpty()) {
                    append(" (arguments: ")
                    append(d.parameters.entries.joinToString(", ") { "${it.key} - ${it.value}" })
                    append(")")
                }
                appendLine()
            }
            appendLine(
                "To use one, reply with nothing but " +
                    "{\"tool\": \"<name>\", \"arguments\": {\"<key>\": \"<value>\"}}. " +
                    "Otherwise answer normally in speech.",
            )
        }
    }

    override fun parse(text: String): ToolCall? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end <= start) return null

        val body = text.substring(start, end + 1)
        val fields = parseFlatJson(body) ?: return null
        val name = fields["tool"] ?: fields["name"] ?: return null
        if (!defs.containsKey(name)) return null
        return ToolCall(name, parseArguments(body))
    }

    override fun execute(call: ToolCall): ToolResult {
        val fn = functions[call.name]
            ?: return ToolResult(call.name, "No such tool: ${call.name}", isError = true)
        return try {
            ToolResult(call.name, fn(call.arguments))
        } catch (e: Throwable) {
            ToolResult(call.name, e.message ?: e.javaClass.simpleName, isError = true)
        }
    }

    /**
     * Reads top-level `"key": "value"` pairs, stepping over nested objects.
     * Numbers and booleans come back as their literal text, which is all a tool
     * implementation needs.
     */
    internal fun parseFlatJson(json: String): Map<String, String>? {
        val out = linkedMapOf<String, String>()
        var i = 0
        var depth = 0
        while (i < json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    if (depth != 1) {
                        i = skipString(json, i)
                        continue
                    }
                    val keyEnd = endOfString(json, i) ?: return null
                    val key = json.substring(i + 1, keyEnd)

                    var j = keyEnd + 1
                    while (j < json.length && json[j].isWhitespace()) j++
                    if (j >= json.length || json[j] != ':') {
                        i = keyEnd + 1
                        continue
                    }
                    j++
                    while (j < json.length && json[j].isWhitespace()) j++
                    if (j >= json.length) return null

                    // Nested value: let the outer loop walk into it.
                    if (json[j] == '{' || json[j] == '[') {
                        i = j
                        continue
                    }
                    if (json[j] == '"') {
                        val valueEnd = endOfString(json, j) ?: return null
                        out[key] = unescape(json.substring(j + 1, valueEnd))
                        i = valueEnd + 1
                    } else {
                        var k = j
                        while (k < json.length && json[k] != ',' && json[k] != '}') k++
                        out[key] = json.substring(j, k).trim()
                        i = k
                    }
                    continue
                }
            }
            i++
        }
        return out.ifEmpty { null }
    }

    /** Reads the pairs inside the nested arguments object. */
    internal fun parseArguments(json: String): Map<String, String> {
        val marker = ARGUMENTS.find(json) ?: return emptyMap()
        val open = marker.range.last
        var depth = 0
        var i = open
        while (i < json.length) {
            when (json[i]) {
                '"' -> {
                    i = skipString(json, i)
                    continue
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return parseFlatJson(json.substring(open, i + 1)) ?: emptyMap()
                }
            }
            i++
        }
        return emptyMap()
    }

    private fun endOfString(s: String, quote: Int): Int? {
        var i = quote + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return null
    }

    private fun skipString(s: String, quote: Int): Int = (endOfString(s, quote) ?: (s.length - 1)) + 1

    private fun unescape(s: String): String = s
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private companion object {
        val ARGUMENTS = Regex("\"(arguments|args|parameters)\"\\s*:\\s*\\{")
    }
}
