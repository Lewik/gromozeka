package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryEntityRefValidatorTest {

    @Test
    fun resolvesUserLiteralThroughResolvedEntityAlias() {
        val userEntityId = MemoryEntity.Id("entity-user")
        val validator = MemoryEntityRefValidator(
            stageName = "TestStage",
            allowedEntityIds = setOf(userEntityId),
            entityAliases = listOf(
                MemoryEntityCanonicalizationOp(
                    mention = "I",
                    action = MemoryEntityCanonicalizationOp.Action.CREATE_NEW,
                    entityId = userEntityId,
                    newEntity = MemoryEntityCanonicalizationOp.NewEntity(
                        entityType = MemoryEntity.Type.USER,
                        canonicalName = "User",
                    ),
                    confidence = 1.0,
                    reason = "Stable user.",
                )
            ).toEntityRefAliases(),
        )

        assertEquals(userEntityId, validator.required("user", "\$.subject_entity_id"))
    }

    @Test
    fun restoresStrippedEntityPrefixWhenItMatchesAllowedId() {
        val entityId = MemoryEntity.Id("entity:0cdfb4d3913a9344")
        val validator = MemoryEntityRefValidator(
            stageName = "TestStage",
            allowedEntityIds = setOf(entityId),
        )

        assertEquals(entityId, validator.required("0cdfb4d3913a9344", "\$.entity_id"))
    }
}
