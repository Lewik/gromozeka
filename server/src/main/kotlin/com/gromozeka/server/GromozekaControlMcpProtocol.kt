package com.gromozeka.server

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.ProjectAccessDeniedException
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val controlMcpJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    classDiscriminator = "type"
}

private val controlMcpLog = KLoggers.logger("GromozekaControlMcp")

internal data class ControlMcpTool(
    val definition: Tool,
    val accessPolicy: ControlMcpAccessPolicy,
    val execute: suspend ControlMcpCallContext.(JsonObject) -> JsonObject,
)

internal enum class ControlMcpAccessPolicy {
    AUTHENTICATED,
    SERVER_OWNER,
}

internal data class ControlMcpCallContext(
    val user: User,
) {
    fun requireServerOwner() {
        if (user.role != User.Role.OWNER) {
            throw ControlMcpToolException("forbidden", "Server owner permission is required")
        }
    }
}

internal class ControlMcpToolException(
    val code: String,
    override val message: String,
) : IllegalArgumentException(message)

internal object ControlMcpSchemas {
    val empty = ToolSchema()

    val result = objectSchema(
        properties = mapOf(
            "success" to boolean("Whether the operation completed successfully."),
            "result" to objectValue("Structured operation result."),
            "error" to objectValue("Structured error details when success is false."),
        ),
        required = listOf("success"),
    )

    fun objectSchema(
        properties: Map<String, JsonElement>,
        required: List<String> = emptyList(),
    ): ToolSchema = ToolSchema(
        properties = JsonObject(properties),
        required = required.takeIf(List<String>::isNotEmpty),
    )

    fun string(description: String, enum: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("description", description)
            if (enum.isNotEmpty()) {
                put("enum", JsonArray(enum.map(::JsonPrimitive)))
            }
        }

    fun boolean(description: String): JsonObject =
        buildJsonObject {
            put("type", "boolean")
            put("description", description)
        }

    fun integer(description: String, minimum: Int? = null): JsonObject =
        buildJsonObject {
            put("type", "integer")
            put("description", description)
            minimum?.let { put("minimum", it) }
        }

    fun number(description: String): JsonObject =
        buildJsonObject {
            put("type", "number")
            put("description", description)
        }

    fun objectValue(description: String): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("description", description)
        }

    fun stringArray(description: String): JsonObject =
        buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject { put("type", "string") })
        }

    fun objectArray(description: String): JsonObject =
        buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject { put("type", "object") })
        }
}

internal fun controlMcpTool(
    name: String,
    description: String,
    inputSchema: ToolSchema = ControlMcpSchemas.empty,
    readOnly: Boolean,
    destructive: Boolean = false,
    idempotent: Boolean = false,
    accessPolicy: ControlMcpAccessPolicy = ControlMcpAccessPolicy.AUTHENTICATED,
    execute: suspend ControlMcpCallContext.(JsonObject) -> JsonObject,
): ControlMcpTool = ControlMcpTool(
    definition = Tool(
        name = name,
        description = description,
        inputSchema = inputSchema,
        outputSchema = ControlMcpSchemas.result,
        annotations = ToolAnnotations(
            readOnlyHint = readOnly,
            destructiveHint = destructive,
            idempotentHint = idempotent,
            openWorldHint = false,
        ),
    ),
    accessPolicy = accessPolicy,
    execute = execute,
)

internal suspend fun ControlMcpTool.invoke(
    context: ControlMcpCallContext,
    arguments: JsonObject,
): CallToolResult {
    val structured = invokeStructured(context, arguments)
    val isError = structured["success"] != JsonPrimitive(true)
    return CallToolResult(
        content = listOf(TextContent(controlMcpJson.encodeToString(JsonObject.serializer(), structured))),
        structuredContent = structured,
        isError = isError,
    )
}

internal suspend fun ControlMcpTool.invokeStructured(
    context: ControlMcpCallContext,
    arguments: JsonObject,
): JsonObject =
    try {
        if (accessPolicy == ControlMcpAccessPolicy.SERVER_OWNER) {
            context.requireServerOwner()
        }
        val result = execute(context, arguments)
        buildJsonObject {
            put("success", true)
            put("result", result)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        if (error !is ControlMcpToolException &&
            error !is IllegalArgumentException &&
            error !is IllegalStateException
        ) {
            controlMcpLog.warn(error) {
                "Control MCP tool failed unexpectedly: name=${definition.name} error=${error.message}"
            }
        }
        error.toControlMcpFailure()
    }

private fun Throwable.toControlMcpFailure(): JsonObject {
    val (code, safeMessage) = when (this) {
        is ControlMcpToolException -> code to message
        is ProjectAccessDeniedException -> "forbidden" to message.orEmpty()
        is IllegalArgumentException -> "invalid_argument" to (message ?: "Invalid argument")
        is IllegalStateException -> "invalid_state" to (message ?: "Invalid state")
        else -> "internal_error" to "Control operation failed"
    }
    return buildJsonObject {
        put("success", false)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", safeMessage)
            }
        )
    }
}

internal fun JsonObject.requiredString(name: String): String =
    (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: throw ControlMcpToolException("invalid_argument", "'$name' must be a non-empty string")

internal fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw ControlMcpToolException("invalid_argument", "'$name' must be a string")
    require(primitive.isString) { "'$name' must be a string" }
    return primitive.content.trim().takeIf(String::isNotEmpty)
}

internal fun JsonObject.requiredLong(name: String): Long =
    (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.content
        ?.toLongOrNull()
        ?: throw ControlMcpToolException("invalid_argument", "'$name' must be an integer")

internal fun JsonObject.optionalBoolean(name: String, default: Boolean): Boolean {
    val value = this[name] ?: return default
    return (value as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.content
        ?.toBooleanStrictOrNull()
        ?: throw ControlMcpToolException("invalid_argument", "'$name' must be a boolean")
}

internal fun JsonObject.requiredObject(name: String): JsonObject =
    this[name] as? JsonObject
        ?: throw ControlMcpToolException("invalid_argument", "'$name' must be an object")

internal fun JsonObject.requiredStringList(name: String): List<String> {
    val values = this[name] as? JsonArray
        ?: throw ControlMcpToolException("invalid_argument", "'$name' must be an array")
    return values.mapIndexed { index, value ->
        (value as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw ControlMcpToolException(
                "invalid_argument",
                "'$name[$index]' must be a non-empty string",
            )
    }
}

internal fun JsonObject.optionalStringList(name: String): List<String> =
    if (name in this) requiredStringList(name) else emptyList()

internal fun notFound(entity: String, id: String): Nothing =
    throw ControlMcpToolException("not_found", "$entity not found: $id")
