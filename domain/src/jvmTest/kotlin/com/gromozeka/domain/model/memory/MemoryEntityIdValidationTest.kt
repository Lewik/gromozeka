package com.gromozeka.domain.model.memory

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive

class MemoryEntityIdValidationTest {
    @Test
    fun acceptsCurrentCanonicalEntityIdFormats() {
        assertTrue("entity:9998de57e7aef02b".isValidMemoryEntityId())
        assertTrue("entity-user".isValidMemoryEntityId())
        assertTrue("trace-test-entity-1".isValidMemoryEntityId())
        assertTrue("hot-path:entity:550e8400-e29b-41d4-a716-446655440000".isValidMemoryEntityId())
    }

    @Test
    fun rejectsRawEntityLabels() {
        assertFalse("user".isValidMemoryEntityId())
        assertFalse("assistant".isValidMemoryEntityId())
        assertFalse("project".isValidMemoryEntityId())
    }

    @Test
    fun batchValidationFailsOnRawUserClaimSubject() {
        val error = assertFailsWith<IllegalArgumentException> {
            MemoryUpdateBatch(
                claims = listOf(
                    MemoryClaim(
                        id = MemoryClaim.Id("claim-test"),
                        namespace = MemoryNamespace("test"),
                        subjectEntityId = MemoryEntity.Id("user"),
                        predicate = "prefers",
                        objectValue = JsonPrimitive("Toyota"),
                        normalizedText = "The user prefers Toyota.",
                        scope = MemoryScope.Global("test"),
                        firstSeenAt = NOW,
                        lastSeenAt = NOW,
                        createdAt = NOW,
                        updatedAt = NOW,
                    )
                )
            ).requireValidEntityIds()
        }

        assertTrue(error.message.orEmpty().contains("claims[0].subjectEntityId"))
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-19T00:00:00Z")
    }
}
