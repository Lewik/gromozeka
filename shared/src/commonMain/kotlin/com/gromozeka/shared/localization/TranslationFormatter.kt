package com.gromozeka.shared.localization

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

object TranslationFormatter {
    private val namedPattern = Regex("\\{([A-Za-z][A-Za-z0-9_]*)\\}")
    private val positionalPattern = Regex("%%|%(?:(\\d+)\\$)?(?:\\.(\\d+))?([sdf])")

    fun named(template: String, arguments: Map<String, Any?>, isolateArguments: Boolean = false): String {
        val required = namedPattern.findAll(template).map { it.groupValues[1] }.toSet()
        require(arguments.keys == required) { "Translation arguments differ: expected $required, got ${arguments.keys}" }
        return namedPattern.replace(template) { match ->
            display(arguments.getValue(match.groupValues[1]).toString(), isolateArguments)
        }
    }

    fun positional(template: String, arguments: List<Any?>, isolateArguments: Boolean = false): String {
        var nextIndex = 0
        val used = mutableSetOf<Int>()
        val result = positionalPattern.replace(template) { match ->
            if (match.value == "%%") return@replace "%"
            val index = position(match.groupValues[1])?.minus(1) ?: nextIndex++
            val precision = precision(match.groupValues[2])
            require(index in arguments.indices) { "Missing translation argument ${index + 1}" }
            used += index
            val value = arguments[index]
            val formatted = when (match.groupValues[3]) {
                "s" -> value.toString()
                "d" -> {
                    require(value is Byte || value is Short || value is Int || value is Long) {
                        "Translation argument ${index + 1} must be an integer"
                    }
                    value.toString()
                }
                "f" -> {
                    require(value is Number) { "Translation argument ${index + 1} must be numeric" }
                    decimal(value.toDouble(), precision ?: 6)
                }
                else -> error("Unsupported translation argument")
            }
            display(formatted, isolateArguments)
        }
        require(used == arguments.indices.toSet()) { "Unused translation arguments" }
        return result
    }

    fun signature(template: String): ArgumentSignature {
        val named = namedPattern.findAll(template).map { it.groupValues[1] }.groupingBy { it }.eachCount()
        val positional = mutableMapOf<Int, PositionalArgument>()
        var nextIndex = 1
        positionalPattern.findAll(template).filter { it.value != "%%" }.forEach { match ->
            val index = position(match.groupValues[1]) ?: nextIndex++
            val argument = PositionalArgument(match.groupValues[3], precision(match.groupValues[2]))
            val previous = positional[index]
            require(previous == null || previous.type == argument.type && previous.precision == argument.precision) {
                "Inconsistent translation argument $index"
            }
            positional[index] = argument.copy(occurrences = (previous?.occurrences ?: 0) + 1)
        }
        return ArgumentSignature(named, positional)
    }

    private fun display(value: String, isolate: Boolean): String =
        if (isolate && value.isNotEmpty()) "\u2068$value\u2069" else value

    private fun position(value: String): Int? {
        if (value.isEmpty()) return null
        return requireNotNull(value.toIntOrNull()?.takeIf { it > 0 }) {
            "Translation argument position must be between 1 and ${Int.MAX_VALUE}"
        }
    }

    private fun precision(value: String): Int? {
        if (value.isEmpty()) return null
        return requireNotNull(value.toIntOrNull()?.takeIf { it in 0..9 }) {
            "Translation argument precision must be between 0 and 9"
        }
    }

    private fun decimal(value: Double, precision: Int): String {
        require(value.isFinite() && precision in 0..9) { "Invalid decimal translation argument" }
        val scale = 10.0.pow(precision)
        val scaled = round(abs(value) * scale)
        require(scaled < Long.MAX_VALUE.toDouble()) { "Decimal translation argument is too large" }
        val integer = floor(scaled / scale).toLong()
        val fraction = (scaled.toLong() % scale.toLong()).toString().padStart(precision, '0')
        return (if (value < 0) "-" else "") + integer + if (precision == 0) "" else ".$fraction"
    }
}

data class ArgumentSignature(val named: Map<String, Int>, val positional: Map<Int, PositionalArgument>)

data class PositionalArgument(val type: String, val precision: Int?, val occurrences: Int = 1)

class TranslationCatalog(val content: TranslationPackage) {
    fun template(key: String): String = when (val message = content.messages.getValue(key)) {
        is TranslationMessage.Text -> message.value
        is TranslationMessage.Plural -> error("Use plural() for translation $key")
    }

    fun text(key: String, vararg arguments: Pair<String, Any?>): String =
        TranslationFormatter.named(template(key), uniqueArguments(arguments), content.direction == TranslationDirection.RTL)

    fun format(key: String, vararg arguments: Any?): String =
        TranslationFormatter.positional(template(key), arguments.toList(), content.direction == TranslationDirection.RTL)

    fun plural(key: String, count: Long, vararg arguments: Pair<String, Any?>): String {
        val message = content.messages.getValue(key) as? TranslationMessage.Plural
            ?: error("Translation $key has no plural forms")
        val category = CardinalPluralRules.select(content.locale, count)
        val template = message.forms.getValue(category)
        val values = uniqueArguments(arguments)
        require("count" !in values) { "Plural count is supplied separately" }
        val withCount = if ("count" in TranslationFormatter.signature(template).named) values + ("count" to count) else values
        return TranslationFormatter.named(template, withCount, content.direction == TranslationDirection.RTL)
    }

    private fun uniqueArguments(arguments: Array<out Pair<String, Any?>>): Map<String, Any?> =
        arguments.toMap().also { require(it.size == arguments.size) { "Duplicate translation argument" } }
}
