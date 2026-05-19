package com.gromozeka.domain.model.memory

private val validEntityIdPattern = Regex("^([A-Za-z0-9_.:\\-]*[:\\-])?entity[:\\-][A-Za-z0-9][A-Za-z0-9_.:\\-]*$")
private val invalidEntityIdLiterals = setOf(
    "user",
    "assistant",
    "agent",
    "person",
    "project",
    "document",
    "file",
    "unknown",
    "none",
    "null",
    "uuid",
    "uuid-or-null",
    "resolved-entity-id",
    "resolved-entity-id-or-null",
)

fun MemoryUpdateBatch.requireValidEntityIds(): MemoryUpdateBatch {
    entities.forEachIndexed { index, entity ->
        entity.id.requireValidEntityId("entities[$index].id")
    }
    claims.forEachIndexed { index, claim ->
        claim.subjectEntityId.requireValidEntityId("claims[$index].subjectEntityId")
        claim.objectEntityId?.requireValidEntityId("claims[$index].objectEntityId")
    }
    notes.forEachIndexed { index, note ->
        note.anchorEntityId?.requireValidEntityId("notes[$index].anchorEntityId")
        note.entityRefs.forEachIndexed { refIndex, ref ->
            ref.entityId.requireValidEntityId("notes[$index].entityRefs[$refIndex].entityId")
        }
    }
    tasks.forEachIndexed { index, task ->
        task.ownerEntityId?.requireValidEntityId("tasks[$index].ownerEntityId")
        task.assigneeEntityId?.requireValidEntityId("tasks[$index].assigneeEntityId")
        task.relatedEntityIds.forEachIndexed { refIndex, entityId ->
            entityId.requireValidEntityId("tasks[$index].relatedEntityIds[$refIndex]")
        }
    }
    profiles.forEachIndexed { index, profile ->
        profile.ownerEntityId.requireValidEntityId("profiles[$index].ownerEntityId")
    }
    episodes.forEachIndexed { index, episode ->
        episode.ownerEntityId?.requireValidEntityId("episodes[$index].ownerEntityId")
    }
    return this
}

fun MemoryEntity.Id.requireValidEntityId(fieldPath: String): MemoryEntity.Id {
    require(value.isValidMemoryEntityId()) {
        "Invalid MemoryEntity.Id at $fieldPath: '$value'. Use a resolved canonical entity id, not a raw label such as 'user'."
    }
    return this
}

fun String.isValidMemoryEntityId(): Boolean {
    val value = trim()
    if (value.isBlank() || value != this) return false
    if (value.lowercase() in invalidEntityIdLiterals) return false
    return validEntityIdPattern.matches(value)
}
