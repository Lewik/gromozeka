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
    MemoryWriteOriginKind.PROVIDED_DOCUMENT.wireName,
    MemoryWriteOriginKind.PROVIDED_DOCUMENT_SECTION.wireName,
    MemoryWriteOriginKind.PASTED_DOCUMENT.wireName,
    MemoryWriteOriginKind.PASTED_DOCUMENT_SECTION.wireName,
)

internal fun MemorySource.isDocumentIngestSource(): Boolean {
    val metadata = contentPayloadObject() ?: return false
    val origin = metadata.stringValue("memoryToolOrigin")
    return metadata.stringValue("sourceKind") == "document" ||
        origin in documentOrigins
}

internal fun MemorySource.isForcedMemoryWriteSource(): Boolean {
    val metadata = contentPayloadObject() ?: return false
    return metadata.booleanValue("forceMemoryWrite")
}

internal fun MemorySource.withForceMemoryWrite(): MemorySource {
    val origin = contentPayloadObject()?.stringValue("memoryToolOrigin")
    return withContentPayloadFields(
        buildJsonObject {
            put("forceMemoryWrite", true)
            if (origin.isNullOrBlank()) {
                put("memoryToolOrigin", MemoryWriteOriginKind.FORCED_TOOL_TARGET.wireName)
            }
        }
    )
}

internal fun MemorySource.renderIngestionMetadataForPrompt(): String? {
    val metadata = contentPayloadObject() ?: return null
    val fields = listOfNotNull(
        metadata.stringValue("sourceKind")?.let { "source_kind=$it" },
        metadata.stringValue("memoryToolOrigin")?.let { "origin=$it" },
        metadata.stringValue("memoryWriteSurface")?.let { "surface=$it" },
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

internal fun MemorySource.documentIngestSearchHints(): List<String> =
    buildList {
        contentPayloadObject()?.let { metadata ->
            addAll(
                listOfNotNull(
                    metadata.stringValue("title"),
                    metadata.stringValue("sourceRef"),
                    metadata.stringValue("heading"),
                    metadata.stringValue("documentType"),
                )
            )
        }
        addAll(
            listOfNotNull(
                contentText.metadataValue("Document title:"),
                contentText.metadataValue("Document source:"),
                contentText.metadataValue("Document section:"),
            )
        )
    }
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(12)

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

private fun String.metadataValue(prefix: String): String? =
    lineSequence()
        .firstOrNull { it.trimStart().startsWith(prefix) }
        ?.substringAfter(prefix)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
