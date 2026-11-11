package com.gromozeka.shared.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** RFC 6901 string-form pointer. Missing values are distinct from an explicit JSON null. */
class JsonPointer(val value: String) {
    private val tokens: List<String>

    init {
        require(value.isEmpty() || value.startsWith('/')) { "JSON Pointer must be empty or begin with /" }
        tokens = if (value.isEmpty()) emptyList() else value.substring(1).split('/').map { token ->
            buildString {
                var i = 0
                while (i < token.length) {
                    val char = token[i++]
                    if (char != '~') append(char) else {
                        require(i < token.length) { "Incomplete JSON Pointer escape" }
                        append(when (token[i++]) {
                            '0' -> '~'
                            '1' -> '/'
                            else -> throw IllegalArgumentException("Invalid JSON Pointer escape")
                        })
                    }
                }
            }
        }
    }

    fun resolve(document: JsonElement): JsonElement? {
        var current = document
        for (token in tokens) {
            current = when (val node = current) {
                is JsonObject -> node[token]
                is JsonArray -> {
                    if (!token.matches(Regex("0|[1-9][0-9]*"))) return null
                    token.toIntOrNull()?.let(node::getOrNull)
                }
                else -> null
            } ?: return null
        }
        return current
    }
}
