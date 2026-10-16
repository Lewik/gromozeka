package com.gromozeka.server

import com.gromozeka.domain.tool.AiToolContextField.*
import com.gromozeka.domain.tool.AiToolContextRequirements

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.tool.AiToolAccessPolicy
import com.gromozeka.domain.tool.AiToolAnnotations
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallResult
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.ToolCancellationSignal
import com.gromozeka.domain.tool.ToolExecutionContext
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

internal val serverToolJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    classDiscriminator = "type"
}

internal data class ServerToolContext(
    val user: User,
    val callingAgentId: AgentDefinition.Id? = null,
    val conversationId: Conversation.Id? = null,
    val cancellationSignal: ToolCancellationSignal = ToolCancellationSignal.None,
) {
    fun requireServerOwner() {
        if (user.role != User.Role.OWNER) {
            throw ServerToolException("forbidden", "Server owner permission is required")
        }
    }
}

internal class ServerToolException(
    val code: String,
    override val message: String,
) : IllegalArgumentException(message)

/** Native callbacks with a JSON result and a trusted, freshly resolved actor. No MCP types. */
@Service
internal class ServerJsonToolFactory(private val users: UserDirectoryService) {
    private val log = KLoggers.logger(this)

    fun create(
        source: String,
        name: String,
        description: String,
        inputSchema: JsonObject = ServerToolSchemas.empty,
        readOnly: Boolean,
        destructive: Boolean = false,
        idempotent: Boolean = false,
        accessPolicy: AiToolAccessPolicy = AiToolAccessPolicy.AUTHENTICATED,
        execute: suspend ServerToolContext.(JsonObject) -> JsonObject,
    ): AiToolCallback = object : AiToolCallback {
        override val definition = AiToolDefinition(
            name = name,
            description = description,
            inputSchema = inputSchema.toString(),
            source = source,
            outputSchema = ServerToolSchemas.result.toString(),
            annotations = AiToolAnnotations(
                readOnlyHint = readOnly,
                destructiveHint = destructive,
                idempotentHint = idempotent,
                openWorldHint = false,
            ),
        )
        override val metadata = AiToolMetadata(
            contextRequirements = AiToolContextRequirements(setOf(USER)),
            executionScope = AiToolExecutionScope.SERVER,
            visibleToMemoryPipeline = false,
            logInput = false,
            accessPolicy = accessPolicy,
        )

        override fun call(toolInput: String, context: ToolExecutionContext?): String =
            runBlocking { callResult(toolInput, context).renderText() }

        override suspend fun callResult(toolInput: String, context: ToolExecutionContext?): AiToolCallResult = coroutineScope {
            val structured = try {
                val actorId = context?.getString(TOOL_CONTEXT_USER_ID)?.takeIf(String::isNotBlank)
                    ?.let(User::Id)
                    ?: throw ServerToolException("unauthenticated", "An authenticated user is required")
                val user = users.findActiveById(actorId)
                    ?: throw ServerToolException("unauthenticated", "An active authenticated user is required")
                val invocation = ServerToolContext(
                    user = user,
                    callingAgentId = context.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID)?.let(AgentDefinition::Id),
                    conversationId = context.getString(TOOL_CONTEXT_CONVERSATION_ID)?.let(Conversation::Id),
                    cancellationSignal = context.cancellationSignal,
                )
                if (metadata.accessPolicy == AiToolAccessPolicy.SERVER_OWNER) invocation.requireServerOwner()
                val arguments = serverToolJson.parseToJsonElement(toolInput) as? JsonObject
                    ?: throw ServerToolException("invalid_argument", "Tool input must be a JSON object")
                val result = execute(invocation, arguments)
                buildJsonObject {
                    put("success", true)
                    put("result", result)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (error !is ServerToolException && error !is IllegalArgumentException && error !is IllegalStateException) {
                    log.warn(error) { "Server tool failed unexpectedly: name=${definition.name}" }
                }
                error.toServerToolFailure()
            }
            AiToolCallResult(
                content = listOf(AiToolResult.Text(structured.toString())),
                structuredContent = structured,
                isError = structured["success"] != JsonPrimitive(true),
            )
        }
    }
}

private fun Throwable.toServerToolFailure(): JsonObject {
    val (code, safeMessage) = when (this) {
        is ServerToolException -> code to message
        is ProjectAccessDeniedException -> "forbidden" to message.orEmpty()
        is IllegalArgumentException -> "invalid_argument" to (message ?: "Invalid argument")
        is IllegalStateException -> "invalid_state" to (message ?: "Invalid state")
        else -> "internal_error" to "Tool operation failed"
    }
    return buildJsonObject {
        put("success", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", safeMessage)
        })
    }
}

internal object ServerToolSchemas {
    val empty = objectSchema(emptyMap())

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
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
    }

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

    fun integer(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject =
        buildJsonObject {
            put("type", "integer")
            put("description", description)
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
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

    fun objectArray(
        description: String,
        properties: Map<String, JsonElement>,
        required: List<String> = emptyList(),
    ): JsonObject =
        buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("properties", JsonObject(properties))
                if (required.isNotEmpty()) {
                    put("required", JsonArray(required.map(::JsonPrimitive)))
                }
            })
        }
}

internal fun JsonObject.requiredString(name: String): String =
    (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: throw ServerToolException("invalid_argument", "'$name' must be a non-empty string")

internal fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw ServerToolException("invalid_argument", "'$name' must be a string")
    require(primitive.isString) { "'$name' must be a string" }
    return primitive.content.trim().takeIf(String::isNotEmpty)
}

internal fun JsonObject.requiredLong(name: String): Long =
    (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.content
        ?.toLongOrNull()
        ?: throw ServerToolException("invalid_argument", "'$name' must be an integer")

internal fun JsonObject.optionalInt(
    name: String,
    default: Int,
    range: IntRange,
): Int {
    val value = this[name] ?: return default
    val parsed = (value as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.content
        ?.toIntOrNull()
        ?: throw ServerToolException("invalid_argument", "'$name' must be an integer")
    if (parsed !in range) {
        throw ServerToolException(
            "invalid_argument",
            "'$name' must be between ${range.first} and ${range.last}",
        )
    }
    return parsed
}

internal fun JsonObject.optionalBoolean(name: String, default: Boolean): Boolean {
    val value = this[name] ?: return default
    return (value as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.content
        ?.toBooleanStrictOrNull()
        ?: throw ServerToolException("invalid_argument", "'$name' must be a boolean")
}

internal fun JsonObject.requiredObject(name: String): JsonObject =
    this[name] as? JsonObject
        ?: throw ServerToolException("invalid_argument", "'$name' must be an object")

internal fun JsonObject.requiredStringList(name: String): List<String> {
    val values = this[name] as? JsonArray
        ?: throw ServerToolException("invalid_argument", "'$name' must be an array")
    return values.mapIndexed { index, value ->
        (value as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw ServerToolException(
                "invalid_argument",
                "'$name[$index]' must be a non-empty string",
            )
    }
}

internal fun JsonObject.optionalStringList(name: String): List<String> =
    if (name in this) requiredStringList(name) else emptyList()

internal fun notFound(entity: String, id: String): Nothing =
    throw ServerToolException("not_found", "$entity not found: $id")
