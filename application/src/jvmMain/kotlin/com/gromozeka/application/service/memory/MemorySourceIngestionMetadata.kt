package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemorySource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val documentOrigins = setOf(
    "provided_document",
    "provided_document_section",
    "pasted_document",
    "pasted_document_section",
)

private val forceModes = setOf(
    "force",
    "forced",
    "force_write",
    "force_memory",
    "force_remember",
)

internal fun MemorySource.isDocumentIngestSource(): Boolean {
    val metadata = contentPayloadObject() ?: return contentText.startsWith("Document source:")
    val origin = metadata.stringValue("memoryToolOrigin")
    return metadata.stringValue("sourceKind") == "document" ||
        origin in documentOrigins ||
        contentText.startsWith("Document source:")
}

internal fun MemorySource.isForcedMemoryWriteSource(): Boolean {
    val metadata = contentPayloadObject() ?: return false
    val mode = metadata.stringValue("mode")?.lowercase()
    return metadata.booleanValue("forceMemoryWrite") ||
        mode in forceModes
}

internal fun MemorySource.withForceMemoryWrite(): MemorySource {
    val origin = contentPayloadObject()?.stringValue("memoryToolOrigin")
    return withContentPayloadFields(
        buildJsonObject {
            put("forceMemoryWrite", true)
            if (origin.isNullOrBlank()) {
                put("memoryToolOrigin", "forced_tool_target")
            }
        }
    )
}

internal fun MemorySource.renderIngestionMetadataForPrompt(): String? {
    val metadata = contentPayloadObject() ?: return null
    val fields = listOfNotNull(
        metadata.stringValue("sourceKind")?.let { "source_kind=$it" },
        metadata.stringValue("memoryToolOrigin")?.let { "origin=$it" },
        metadata.stringValue("inputKind")?.let { "input_kind=$it" },
        metadata.stringValue("documentType")?.let { "document_type=$it" },
        metadata.stringValue("sourceRef")?.let { "source_ref=$it" },
        metadata.stringValue("title")?.let { "title=$it" },
        metadata.stringValue("heading")?.let { "heading=$it" },
        metadata.stringValue("importedAt")?.let { "imported_at=$it" },
        metadata.booleanValue("userConsentConfirmed").takeIf { it }?.let { "user_consent_confirmed=true" },
        metadata.booleanValue("forceMemoryWrite").takeIf { it }?.let { "force_memory_write=true" },
        metadata.stringValue("mode")?.let { "mode=$it" },
    )
    if (fields.isEmpty()) return null
    return "Source metadata: ${fields.joinToString("; ")}"
}

internal fun MemorySource.defaultIngestSearchText(): String =
    buildString {
        contentPayloadObject()?.let { metadata ->
            listOfNotNull(
                metadata.stringValue("title"),
                metadata.stringValue("sourceRef"),
                metadata.stringValue("heading"),
                metadata.stringValue("documentType"),
            ).forEach { appendLine(it) }
        }
        appendLine(contentText)
    }
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(4_000)

internal fun MemorySource.withContentPayloadFields(fields: JsonObject): MemorySource {
    val current = contentPayloadObject()?.toMap().orEmpty()
    val merged = JsonObject(current + fields.toMap())
    return when (this) {
        is MemorySource.ChatTurn -> copy(contentPayload = merged)
        is MemorySource.ToolOutput -> copy(contentPayload = merged)
        is MemorySource.ImportedNote -> copy(contentPayload = merged)
        is MemorySource.ExternalRecord -> copy(contentPayload = merged)
    }
}

private fun MemorySource.contentPayloadObject(): JsonObject? =
    contentPayload as? JsonObject

private fun JsonObject.stringValue(key: String): String? =
    this[key]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun JsonObject.booleanValue(key: String): Boolean =
    this[key]?.booleanOrNullCompat() ?: false

private fun JsonElement.booleanOrNullCompat(): Boolean? =
    runCatching { jsonPrimitive.booleanOrNull }.getOrNull()
