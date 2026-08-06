package com.gromozeka.domain.tool

import com.gromozeka.shared.utils.sha256
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val AI_TOOL_MODEL_NAME_MAX_LENGTH = 64

@Serializable
data class AiToolContract(
    val fingerprint: String,
    val logicalName: String,
    val modelName: String,
    val variant: Int,
    val descriptor: AiToolDescriptor,
    val createdAt: Instant,
) {
    init {
        require(fingerprint == descriptor.contractFingerprint()) {
            "AI tool contract fingerprint does not match its descriptor"
        }
        require(logicalName == descriptor.definition.name) {
            "AI tool contract logical name does not match its descriptor"
        }
        require(modelName.isNotBlank() && modelName.length <= AI_TOOL_MODEL_NAME_MAX_LENGTH) {
            "AI tool contract model name must contain at most $AI_TOOL_MODEL_NAME_MAX_LENGTH characters"
        }
        require(variant >= 1) { "AI tool contract variant must be positive" }
    }
}

fun AiToolDescriptor.contractFingerprint(): String =
    buildJsonObject {
        putJsonObject("definition") {
            put("name", definition.name)
            put("description", definition.description)
            put("input_schema", canonicalInputSchema(definition.inputSchema))
            put("source", definition.source)
        }
        putJsonObject("metadata") {
            put("return_direct", metadata.returnDirect)
            putJsonArray("required_runtime_capabilities") {
                metadata.requiredRuntimeCapabilities
                    .map { it.name }
                    .sorted()
                    .forEach { add(JsonPrimitive(it)) }
            }
            put("execution_scope", metadata.executionScope.name)
            put("loading_policy", metadata.loadingPolicy.name)
            put("visible_to_memory_pipeline", metadata.visibleToMemoryPipeline)
            put("log_input", metadata.logInput)
        }
    }.toString().sha256()

private fun canonicalInputSchema(inputSchema: String): JsonElement =
    canonicalize(Json.parseToJsonElement(inputSchema))

private fun canonicalize(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { (key, value) -> key to canonicalize(value) }
        )
        is JsonArray -> buildJsonArray {
            element.forEach { add(canonicalize(it)) }
        }
        else -> element
    }
