package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryEntity

internal fun MemoryEntity.Type.entityMergeFamilyKey(): String =
    when (this) {
        MemoryEntity.Type.USER,
        MemoryEntity.Type.PERSON -> "HUMAN"

        MemoryEntity.Type.TECHNOLOGY,
        MemoryEntity.Type.PRODUCT,
        MemoryEntity.Type.CONCEPT,
        MemoryEntity.Type.SERVICE,
        MemoryEntity.Type.ENVIRONMENT -> "TECHNICAL_OBJECT"

        MemoryEntity.Type.FILE,
        MemoryEntity.Type.DOCUMENT -> "DOCUMENT_ARTIFACT"

        else -> name
    }

internal fun MemoryEntity.Type.isEntityMergeCompatibleWith(other: MemoryEntity.Type): Boolean =
    entityMergeFamilyKey() == other.entityMergeFamilyKey()

internal fun MemoryEntity.mergedObservedTypesWith(others: Iterable<MemoryEntity>): Set<MemoryEntity.Type> =
    buildSet {
        add(entityType)
        addAll(observedTypes)
        others.forEach { entity ->
            add(entity.entityType)
            addAll(entity.observedTypes)
        }
    }
