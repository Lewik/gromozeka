package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.isValidMemoryEntityId

internal class MemoryEntityRefValidator(
    private val stageName: String,
    allowedEntityIds: Set<MemoryEntity.Id>,
) {
    private val allowedEntityIds = allowedEntityIds.toSet()

    fun required(rawValue: String?, fieldPath: String): MemoryEntity.Id =
        optional(rawValue, fieldPath)
            ?: throw invalidReference(fieldPath, rawValue, "missing required entity id")

    fun optional(rawValue: String?, fieldPath: String): MemoryEntity.Id? {
        val value = rawValue
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val normalized = value.lowercase()
        if (normalized in NULL_ENTITY_REFERENCE_MARKERS) {
            return null
        }
        if (!value.isValidMemoryEntityId()) {
            throw invalidReference(fieldPath, value, "malformed entity id")
        }

        val id = MemoryEntity.Id(value)
        if (id !in allowedEntityIds) {
            throw invalidReference(fieldPath, value, "unknown entity id")
        }
        return id
    }

    fun optionalList(rawValues: List<String>, fieldPath: String): List<MemoryEntity.Id> =
        rawValues.mapIndexedNotNull { index, rawValue ->
            optional(rawValue, "$fieldPath[$index]")
        }.distinct()

    private fun invalidReference(
        fieldPath: String,
        rawValue: String?,
        reason: String,
    ): IllegalArgumentException =
        IllegalArgumentException(
            "$stageName returned invalid entity reference at $fieldPath: " +
                "$reason value=${rawValue?.trim()?.ifBlank { "<blank>" } ?: "null"}. " +
                "Allowed entity ids: ${allowedEntityIds.renderAllowedEntityIds()}"
        )
}

private val NULL_ENTITY_REFERENCE_MARKERS = setOf(
    "null",
    "none",
    "uuid-or-null",
    "resolved-entity-id-or-null",
    "entity-id-or-null",
)

private fun Set<MemoryEntity.Id>.renderAllowedEntityIds(): String =
    if (isEmpty()) {
        "none"
    } else {
        joinToString(", ") { it.value }.limitForMemoryPrompt(2_000)
    }
