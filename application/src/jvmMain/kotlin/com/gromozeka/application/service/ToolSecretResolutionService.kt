package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.NamedSecret
import com.gromozeka.domain.model.User
import com.gromozeka.domain.slot.SLOT_ENVIRONMENT_KEYS
import com.gromozeka.domain.tool.filesystem.GRZ_EXECUTE_COMMAND_TOOL_NAME
import com.gromozeka.domain.tool.filesystem.GRZ_MONITOR_COMMAND_TOOL_NAME
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.springframework.stereotype.Service

@Service
class ToolSecretResolutionService(
    private val namedSecretService: NamedSecretApplicationService,
) {
    suspend fun resolve(
        userId: User.Id?,
        toolCalls: List<Conversation.Message.ContentItem.ToolCall>,
    ): Map<String, Map<String, String>> {
        val referencesByCallId = toolCalls.associate { call ->
            call.id.value to collectNames(call)
        }.filterValues(Set<String>::isNotEmpty)
        if (referencesByCallId.isEmpty()) return emptyMap()
        val owner = requireNotNull(userId) { "Named secrets require an authenticated user" }
        val resolved = namedSecretService.resolve(owner, referencesByCallId.values.flatten().toSet())
        return referencesByCallId.mapValues { (_, names) -> resolved.filterKeys(names::contains) }
    }

    private fun collectNames(call: Conversation.Message.ContentItem.ToolCall): Set<String> {
        // Visual markup/state are public UI data. Only its private handler launcher resolves secrets.
        if (call.call.name == "grz_visual") return emptySet()
        if (call.call.name == GRZ_EXECUTE_COMMAND_TOOL_NAME) {
            val input = call.call.input.jsonObject
            return CommandSecretEnvironment.secretNames(input.command(), input.secretEnvironmentReferences()).values.toSet()
        }
        if (call.call.name == GRZ_MONITOR_COMMAND_TOOL_NAME) {
            requireLiteralFilter(call.call.input.jsonObject)
        }
        return collectExactNames(call.call.input)
    }

    private fun collectExactNames(element: JsonElement): Set<String> = buildSet {
        when (element) {
            is JsonArray -> element.forEach { addAll(collectExactNames(it)) }
            is JsonObject -> element.values.forEach { addAll(collectExactNames(it)) }
            is JsonPrimitive -> if (element.isString) {
                NamedSecret.nameFromReference(element.content)?.let(::add)
            }
        }
    }
}

/** References are public; resolved values are supplied only to the process environment. */
object CommandSecretEnvironment {
    private val namePattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val reservedNames = SLOT_ENVIRONMENT_KEYS + setOf("GRZ_SDK_HOME", "GRZ_MCP_CONTEXT") +
        com.gromozeka.domain.service.CommandScriptTokens.environmentKeys

    fun secretNames(command: String, references: Map<String, String>): Map<String, String> {
        require(!NamedSecret.REFERENCE_PATTERN.containsMatchIn(command)) {
            "Named secret references are not allowed in command. Use secret_env, for example {\"GH_TOKEN\":\"secret://github-pat\"}, and let the program read its environment directly."
        }
        val names = mutableSetOf<String>()
        return references.mapValues { (name, reference) ->
            require(namePattern.matches(name)) { "secret_env keys must be valid environment variable names" }
            val normalized = name.uppercase()
            require(names.add(normalized)) { "secret_env keys must be distinct ignoring case for Windows compatibility" }
            require(normalized !in reservedNames && !normalized.startsWith("GROMOZEKA_")) {
                "secret_env cannot override Runtime-owned environment variables"
            }
            requireNotNull(NamedSecret.nameFromReference(reference)) {
                "secret_env values must be exact secret://name references, not literal credentials"
            }
        }
    }

    fun resolve(command: String, references: Map<String, String>, values: Map<String, String>): Map<String, String> =
        secretNames(command, references).mapValues { (_, secretName) ->
            val value = values[secretName] ?: error("Named secret was not resolved: $secretName")
            require('\u0000' !in value) { "Named secret contains NUL and cannot be passed through an environment variable" }
            value
        }
}

data class PreparedToolArguments(
    val arguments: String,
    val secretEnvironment: Map<String, String> = emptyMap(),
)

class SecretArgumentSubstitutor {
    private val json = Json

    fun prepare(toolName: String, arguments: String, values: Map<String, String>): PreparedToolArguments {
        if (toolName == "grz_visual") return PreparedToolArguments(arguments)
        if (toolName == GRZ_EXECUTE_COMMAND_TOOL_NAME) {
            val input = json.parseToJsonElement(arguments).jsonObject
            val environment = CommandSecretEnvironment.resolve(input.command(), input.secretEnvironmentReferences(), values)
            return PreparedToolArguments(JsonObject(input - "secret_env").toString(), environment)
        }
        if (toolName == GRZ_MONITOR_COMMAND_TOOL_NAME) {
            requireLiteralFilter(json.parseToJsonElement(arguments).jsonObject)
        }
        return PreparedToolArguments(substitute(arguments, values))
    }

    fun substitute(arguments: String, values: Map<String, String>): String {
        if (values.isEmpty()) return arguments
        return substitute(json.parseToJsonElement(arguments), values).toString()
    }

    private fun substitute(element: JsonElement, values: Map<String, String>): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { substitute(it, values) })
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> substitute(value, values) })
        is JsonPrimitive -> if (element.isString) {
            NamedSecret.nameFromReference(element.content)
                ?.let { name -> values[name]?.let(::JsonPrimitive) }
                ?: element
        } else {
            element
        }
    }
}

private fun JsonObject.command(): String = (get("command") as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: error("command must be a string")

private fun JsonObject.secretEnvironmentReferences(): Map<String, String> {
    val value = get("secret_env") ?: return emptyMap()
    val references = value as? JsonObject ?: error("secret_env must be an object")
    return references.mapValues { (_, reference) ->
        (reference as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("secret_env values must be exact secret://name references")
    }
}

private fun requireLiteralFilter(input: JsonObject) {
    val filter = (input["filter_command"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
    require(!NamedSecret.REFERENCE_PATTERN.containsMatchIn(filter)) {
        "Named secret references are not supported in filter_command. Run authenticated work with grz_execute_command and secret_env."
    }
}
