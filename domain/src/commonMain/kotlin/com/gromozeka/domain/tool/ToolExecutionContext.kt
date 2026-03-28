package com.gromozeka.domain.tool

/**
 * Framework-agnostic execution context for AI tools.
 */
data class ToolExecutionContext(
    private val values: Map<String, Any?> = emptyMap()
) {
    fun get(key: String): Any? = values[key]

    fun getString(key: String): String? = values[key] as? String

    fun getContext(): Map<String, Any?> = values

    fun asMap(): Map<String, Any?> = values
}
