package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp

internal fun MemoryEntity.identityOnlySummary(): String =
    identityOnlySummary(entityType, canonicalName)

internal fun MemoryEntityCanonicalizationOp.NewEntity.identityOnlySummary(): String =
    identityOnlySummary(entityType, canonicalName)

internal fun identityOnlySummary(
    entityType: MemoryEntity.Type,
    canonicalName: String,
): String =
    when (entityType) {
        MemoryEntity.Type.USER -> "The user interacting with this agent."
        MemoryEntity.Type.PERSON -> "Person named $canonicalName."
        MemoryEntity.Type.AGENT -> "Agent named $canonicalName."
        MemoryEntity.Type.ORGANIZATION -> "Organization named $canonicalName."
        MemoryEntity.Type.PROJECT -> "Project named $canonicalName."
        MemoryEntity.Type.REPO -> "Repository named $canonicalName."
        MemoryEntity.Type.FILE -> "File named $canonicalName."
        MemoryEntity.Type.TECHNOLOGY -> "Technology named $canonicalName."
        MemoryEntity.Type.PRODUCT -> "Product named $canonicalName."
        MemoryEntity.Type.LOCATION -> "Location named $canonicalName."
        MemoryEntity.Type.CONCEPT -> "Concept named $canonicalName."
        MemoryEntity.Type.DOCUMENT -> "Document named $canonicalName."
        MemoryEntity.Type.CONVERSATION -> "Conversation named $canonicalName."
        MemoryEntity.Type.SERVICE -> "Service named $canonicalName."
        MemoryEntity.Type.ENVIRONMENT -> "Environment named $canonicalName."
        MemoryEntity.Type.OTHER -> "Entity named $canonicalName."
    }
