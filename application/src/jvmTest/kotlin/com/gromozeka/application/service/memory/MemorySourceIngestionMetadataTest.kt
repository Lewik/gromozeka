package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemorySource
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemorySourceIngestionMetadataTest {
    @Test
    fun documentAndForceDetectionRequireExplicitMetadata() {
        val legacyLookingSource = source(
            contentText = "Document source: docs/legacy.md\n\nContent.",
            metadata = buildJsonObject { put("mode", "force") },
        )

        assertFalse(legacyLookingSource.isDocumentIngestSource())
        assertFalse(legacyLookingSource.isForcedMemoryWriteSource())

        val typedSource = source(
            contentText = "Content.",
            metadata = buildJsonObject {
                put("sourceKind", "document")
                put("forceMemoryWrite", true)
            },
        )

        assertTrue(typedSource.isDocumentIngestSource())
        assertTrue(typedSource.isForcedMemoryWriteSource())
    }

    private fun source(
        contentText: String,
        metadata: JsonObject,
    ): MemorySource.ExternalRecord = MemorySource.ExternalRecord(
        id = MemorySource.Id("source"),
        namespace = MemoryNamespace("test"),
        recordRef = "test",
        contentText = contentText,
        contentPayload = metadata,
        contentHash = "hash",
        observedAt = NOW,
        createdAt = NOW,
    )

    private companion object {
        val NOW = Instant.parse("2026-07-11T00:00:00Z")
    }
}
