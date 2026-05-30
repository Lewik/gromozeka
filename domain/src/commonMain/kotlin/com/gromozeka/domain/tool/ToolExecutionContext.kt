package com.gromozeka.domain.tool

/**
 * Framework-agnostic execution context for AI tools.
 */
data class ToolExecutionContext(
    private val values: Map<String, Any?> = emptyMap(),
    val cancellationSignal: ToolCancellationSignal = ToolCancellationSignal.None,
) {
    fun get(key: String): Any? = values[key]

    fun getString(key: String): String? = values[key] as? String

    fun getContext(): Map<String, Any?> = values

    fun asMap(): Map<String, Any?> = values

    fun withCancellationSignal(signal: ToolCancellationSignal): ToolExecutionContext =
        copy(cancellationSignal = signal)
}

fun interface ToolCancellationSignal {
    fun throwIfCancellationRequested()

    companion object {
        val None = ToolCancellationSignal {}
    }
}
