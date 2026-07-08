package com.gromozeka.server

import com.gromozeka.application.service.memory.MemoryWriteSurface
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object GromozekaMcpMemoryRememberAdapter {
    private val forbiddenFields = setOf(
        "target",
        "target_message_id",
        "user_consent_confirmed",
    )

    fun toInternalToolArguments(arguments: JsonObject): JsonObject {
        val providedForbiddenFields = arguments.keys.intersect(forbiddenFields)
        require(providedForbiddenFields.isEmpty()) {
            "MCP memory_remember accepts only explicit text/file_path/raw_url content. Unsupported fields: ${providedForbiddenFields.sorted()}."
        }

        val text = arguments.nonBlankString("text")
        val filePath = arguments.nonBlankString("file_path")
        val rawUrl = arguments.nonBlankString("raw_url")
        val documentType = arguments.nonBlankString("document_type")
        val explicitInputs = listOfNotNull(
            text?.let { "text" },
            filePath?.let { "file_path" },
            rawUrl?.let { "raw_url" },
        )
        require(explicitInputs.size == 1) {
            "MCP memory_remember requires exactly one of text, file_path, or raw_url."
        }

        val target = if (filePath != null || rawUrl != null || documentType != null) {
            "provided_document"
        } else {
            "provided_text"
        }

        return JsonObject(buildMap {
            putAll(arguments)
            put("target", JsonPrimitive(target))
            put("user_consent_confirmed", JsonPrimitive(true))
        })
    }

    private fun JsonObject.nonBlankString(key: String): String? =
        this[key]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    val writeSurface: MemoryWriteSurface = MemoryWriteSurface.MCP
}
