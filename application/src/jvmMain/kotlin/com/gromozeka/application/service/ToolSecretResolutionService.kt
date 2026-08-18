package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.NamedSecret
import com.gromozeka.domain.model.User
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
            call.id.value to collectNames(call.call.input)
        }.filterValues(Set<String>::isNotEmpty)
        if (referencesByCallId.isEmpty()) return emptyMap()
        val owner = requireNotNull(userId) { "Named secrets require an authenticated user" }
        val resolved = namedSecretService.resolve(owner, referencesByCallId.values.flatten().toSet())
        return referencesByCallId.mapValues { (_, names) -> resolved.filterKeys(names::contains) }
    }

    private fun collectNames(element: JsonElement): Set<String> = buildSet {
        when (element) {
            is JsonArray -> element.forEach { addAll(collectNames(it)) }
            is JsonObject -> element.values.forEach { addAll(collectNames(it)) }
            is JsonPrimitive -> if (element.isString) {
                NamedSecret.nameFromReference(element.content)?.let(::add)
            }
        }
    }
}

class SecretArgumentSubstitutor {
    private val json = Json

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
