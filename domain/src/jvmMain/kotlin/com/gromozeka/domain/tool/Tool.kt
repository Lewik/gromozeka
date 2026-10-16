package com.gromozeka.domain.tool

/**
 * JVM authoring interface for a tool with a typed request.
 *
 * TypedToolCallbackAdapter generates the input schema from [requestType] and
 * exposes the implementation as the same [AiToolCallback] used by direct beans,
 * contributors and MCP adapters. DefaultAiToolProvider aggregates those callbacks.
 * This interface does not register an MCP server or choose an execution target.
 *
 * An implementation may return a String, a JSON-serializable value, [AiToolResult],
 * a list of content items, or a complete [AiToolCallResult]. Use the complete outcome
 * to return structured content or an explicit operation failure (`isError = true`).
 * Exceptions are handled by the execution boundary; cancellation must propagate.
 */
interface Tool<TRequest, TResponse> {
    /** Logical name; the distributed catalog allocates the model-facing contract name. */
    val name: String

    /** Human-readable description, including the purpose and relevant argument semantics. */
    val description: String

    val source: String
        get() = "gromozeka"

    val metadata: AiToolMetadata

    /** Optional JSON output schema and descriptive hints, independent of the MCP SDK. */
    val outputSchema: String?
        get() = null

    val annotations: AiToolAnnotations?
        get() = null

    val available: Boolean
        get() = true

    /** Kotlin request data class used for decoding and input-schema generation. */
    val requestType: Class<TRequest>

    /** Validate arguments, authorize the operation and delegate to its domain service. */
    fun execute(request: TRequest, context: ToolExecutionContext?): TResponse
}
