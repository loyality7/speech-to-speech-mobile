package com.example.s2stools

import com.s2s.mobile.pipeline.ToolContext
import com.s2s.mobile.pipeline.ToolDefinition
import com.s2s.mobile.pipeline.ToolFunction
import com.s2s.mobile.pipeline.ToolParameter
import com.s2s.mobile.pipeline.Tools

/**
 * Reference stateless tool: pure function of its arguments, ignores [ToolContext]
 * entirely. Most tools should look like this — reach for [BrowserTool]'s pattern
 * only when a tool genuinely needs to remember something between calls.
 */
object CalculatorTool {
    val definition = ToolDefinition(
        name = "calculate",
        description = "Evaluates a simple arithmetic expression: + - * / and parentheses.",
        schema = mapOf(
            "expression" to ToolParameter(description = "e.g. \"12 * (3 + 4)\"", type = "string"),
        ),
    )

    val function = ToolFunction { _: ToolContext, arguments ->
        val expression = arguments["expression"] ?: return@ToolFunction "Missing 'expression' argument"
        runCatching { evaluate(expression) }
            .fold(
                onSuccess = { it.toString() },
                onFailure = { "Could not evaluate '$expression': ${it.message}" },
            )
    }

    /** Registers this tool on any [Tools] implementation — usually `ToolRegistry`. */
    fun registerOn(tools: Tools) = tools.register(definition, function)

    /**
     * Minimal recursive-descent evaluator — swap for a real math library in a
     * production tool; this exists only to keep the template dependency-free.
     * One instance per call so `tokens`/`pos` never leak across concurrent
     * `evaluate` calls on different threads.
     */
    private class Evaluator(expression: String) {
        private val tokens = Regex("\\d+\\.?\\d*|[+\\-*/()]").findAll(expression).map { it.value }.toList()
        private var pos = 0

        private fun peek() = tokens.getOrNull(pos)
        private fun next() = tokens[pos++]

        fun parseExpr(): Double {
            var value = parseTerm()
            while (peek() == "+" || peek() == "-") {
                value = if (next() == "+") value + parseTerm() else value - parseTerm()
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (peek() == "*" || peek() == "/") {
                value = if (next() == "*") value * parseFactor() else value / parseFactor()
            }
            return value
        }

        private fun parseFactor(): Double {
            val token = next()
            if (token == "(") {
                val value = parseExpr()
                next() // ")"
                return value
            }
            return token.toDouble()
        }
    }

    private fun evaluate(expression: String): Double = Evaluator(expression).parseExpr()
}
