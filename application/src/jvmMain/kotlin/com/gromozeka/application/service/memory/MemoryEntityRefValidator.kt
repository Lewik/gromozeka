package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import com.gromozeka.domain.model.memory.isValidMemoryEntityId

internal class MemoryEntityRefValidator(
    private val stageName: String,
    allowedEntityIds: Set<MemoryEntity.Id>,
    entityAliases: Map<String, MemoryEntity.Id> = emptyMap(),
) {
    private val allowedEntityIds = allowedEntityIds.toSet()
    private val entityAliases = entityAliases.filterValues { it in this.allowedEntityIds }

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
        entityAliases[normalizedEntityReferenceKey(value)]?.let { return it }
        value.withEntityPrefixOrNull()?.let { prefixedValue ->
            val prefixedId = MemoryEntity.Id(prefixedValue)
            if (prefixedId in allowedEntityIds) {
                return prefixedId
            }
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

internal fun List<MemoryEntityCanonicalizationOp>.toEntityRefAliases(): Map<String, MemoryEntity.Id> {
    val aliases = mutableListOf<Pair<String, MemoryEntity.Id>>()
    forEach { op ->
        val entityId = op.entityId ?: return@forEach
        aliases += op.mention to entityId
        op.aliasText?.let { aliases += it to entityId }
        op.newEntity?.let { entity ->
            aliases += entity.canonicalName to entityId
            if (entity.entityType == MemoryEntity.Type.USER) {
                aliases += "user" to entityId
                aliases += "the user" to entityId
                aliases += "i" to entityId
                aliases += "me" to entityId
                aliases += "myself" to entityId
            }
        }
    }

    return aliases
        .mapNotNull { (alias, entityId) ->
            normalizedEntityReferenceKey(alias)
                .takeIf { it.isNotBlank() }
                ?.let { it to entityId }
        }
        .groupBy({ it.first }, { it.second })
        .mapNotNull { (alias, ids) ->
            ids.distinct().singleOrNull()?.let { alias to it }
        }
        .toMap()
}

private val NULL_ENTITY_REFERENCE_MARKERS = setOf(
    "null",
    "none",
    "uuid-or-null",
    "resolved-entity-id-or-null",
    "entity-id-or-null",
)

private fun normalizedEntityReferenceKey(value: String): String =
    value.trim().lowercase().replace(Regex("\\s+"), " ")

private fun String.withEntityPrefixOrNull(): String? {
    val value = trim()
    if (':' in value || '-' in value) {
        return null
    }
    val prefixed = "entity:$value"
    return prefixed.takeIf { it.isValidMemoryEntityId() }
}

private fun Set<MemoryEntity.Id>.renderAllowedEntityIds(): String =
    if (isEmpty()) {
        "none"
    } else {
        joinToString(", ") { it.value }.limitForMemoryPrompt(2_000)
    }
