package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.NamedSecret
import com.gromozeka.domain.model.User
import com.gromozeka.domain.tool.filesystem.GRZ_EXECUTE_COMMAND_TOOL_NAME
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private fun collectNames(call: Conversation.Message.ContentItem.ToolCall): Set<String> = buildSet {
        addAll(collectExactNames(call.call.input))
        if (call.call.name == GRZ_EXECUTE_COMMAND_TOOL_NAME) {
            call.call.input.jsonObject["command"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.let(NamedSecret::namesInText)
                ?.let(::addAll)
        }
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

data class PreparedToolArguments(
    val arguments: String,
    val secretEnvironment: Map<String, String> = emptyMap(),
)

class SecretArgumentSubstitutor(
    private val environmentNameGenerator: () -> String = {
        "GROMOZEKA_SECRET_${UUID.randomUUID().toString().replace("-", "").uppercase()}"
    },
    private val inheritedEnvironmentNames: () -> Set<String> = { System.getenv().keys },
) {
    private val json = Json

    fun prepare(
        toolName: String,
        arguments: String,
        values: Map<String, String>,
        isWindows: Boolean = System.getProperty("os.name").lowercase().contains("windows"),
    ): PreparedToolArguments {
        if (toolName != GRZ_EXECUTE_COMMAND_TOOL_NAME) {
            return PreparedToolArguments(substitute(arguments, values))
        }
        val input = json.parseToJsonElement(arguments).jsonObject
        val command = input["command"]?.jsonPrimitive?.contentOrNull
            ?: return PreparedToolArguments(substitute(arguments, values))
        val referencedNames = NamedSecret.namesInText(command)
        if (referencedNames.isEmpty()) {
            return PreparedToolArguments(substitute(arguments, values))
        }
        val generatedNames = mutableSetOf<String>()
        val inheritedNames = inheritedEnvironmentNames()
        val environment = linkedMapOf<String, String>()
        val environmentNamesBySecret = linkedMapOf<String, String>()
        referencedNames.sorted().forEach { secretName ->
            val secretValue = values[secretName] ?: error("Named secret was not resolved: $secretName")
            val environmentName = generateEnvironmentName(command, inheritedNames, generatedNames)
            generatedNames += environmentName
            environment[environmentName] = secretValue
            environmentNamesBySecret[secretName] = environmentName
        }
        val rewrittenCommand = NamedSecret.REFERENCE_PATTERN.replace(command) { match ->
            val environmentName = environmentNamesBySecret.getValue(match.groupValues[1])
            if (isWindows) "%$environmentName%" else "\${$environmentName}"
        }
        val rewrittenInput = JsonObject(input + ("command" to JsonPrimitive(rewrittenCommand)))
        return PreparedToolArguments(
            arguments = substitute(rewrittenInput.toString(), values),
            secretEnvironment = environment,
        )
    }

    fun substitute(arguments: String, values: Map<String, String>): String {
        if (values.isEmpty()) return arguments
        return substitute(json.parseToJsonElement(arguments), values).toString()
    }

    private fun generateEnvironmentName(
        command: String,
        inheritedNames: Set<String>,
        generatedNames: Set<String>,
    ): String {
        repeat(MAX_ENVIRONMENT_NAME_ATTEMPTS) {
            val candidate = environmentNameGenerator()
            require(ENVIRONMENT_NAME_PATTERN.matches(candidate)) {
                "Generated secret environment name is invalid"
            }
            if (candidate !in command && candidate !in inheritedNames && candidate !in generatedNames) {
                return candidate
            }
        }
        error("Could not generate a collision-free secret environment name")
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

    private companion object {
        const val MAX_ENVIRONMENT_NAME_ATTEMPTS = 100
        val ENVIRONMENT_NAME_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
