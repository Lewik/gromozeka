package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

const val MEMORY_WRITE_SURFACE_CONTEXT_KEY = "memoryWriteSurface"

enum class MemoryWriteSurface(val wireName: String) {
    CHAT_TOOL("chat_tool"),
    MCP("mcp"),
    UI("ui"),
    IMPORT("import");

    companion object {
        fun fromContextValue(value: String?): MemoryWriteSurface {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) return CHAT_TOOL
            return entries.firstOrNull { surface ->
                surface.wireName == normalized.lowercase() || surface.name == normalized.uppercase()
            } ?: throw IllegalArgumentException("Unsupported memory write surface: $value")
        }
    }
}

internal enum class MemoryWriteOriginKind(val wireName: String) {
    PROVIDED_TEXT("provided_text"),
    PROVIDED_DOCUMENT("provided_document"),
    PROVIDED_DOCUMENT_SECTION("provided_document_section"),
    PASTED_DOCUMENT("pasted_document"),
    PASTED_DOCUMENT_SECTION("pasted_document_section"),
    FORCED_TOOL_TARGET("forced_tool_target"),
}

internal enum class MemoryWriteSourceKind(val wireName: String) {
    DOCUMENT("document"),
}

internal data class MemoryWriteOrigin(
    val kind: MemoryWriteOriginKind,
    val surface: MemoryWriteSurface,
    val sourceKind: MemoryWriteSourceKind? = null,
    val userConsentConfirmed: Boolean = false,
    val standalone: Boolean? = null,
    val inputKind: MemoryRememberInputKind? = null,
    val documentType: MemoryDocumentType? = null,
    val sourceRef: String? = null,
    val title: String? = null,
    val importedAt: Instant? = null,
    val forceWrite: Boolean = false,
    val mode: String? = null,
    val parentSourceId: MemorySource.Id? = null,
    val parentRunId: MemoryRun.Id? = null,
    val documentHash: String? = null,
    val section: MarkdownDocumentSection? = null,
) {
    fun toMetadataJson() = buildJsonObject {
        put("memoryToolOrigin", kind.wireName)
        put("memoryWriteSurface", surface.wireName)
        sourceKind?.let { put("sourceKind", it.wireName) }
        if (userConsentConfirmed) put("userConsentConfirmed", true)
        standalone?.let { put("standalone", it) }
        inputKind?.let { put("inputKind", it.name) }
        documentType?.let { put("documentType", it.name) }
        sourceRef?.takeIf { it.isNotBlank() }?.let { put("sourceRef", it) }
        title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
        importedAt?.let { put("importedAt", it.toString()) }
        if (forceWrite) put("forceMemoryWrite", true)
        mode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
        parentSourceId?.let { put("parentSourceId", it.value) }
        parentRunId?.let { put("parentRunId", it.value) }
        documentHash?.takeIf { it.isNotBlank() }?.let { put("documentHash", it) }
        section?.let { section ->
            put("sectionIndex", section.index)
            put("heading", section.headingLabel)
            put("startLine", section.startLine)
            put("endLine", section.endLine)
            putJsonArray("headingPath") {
                section.headingPath.forEach { add(JsonPrimitive(it)) }
            }
        }
    }
}
