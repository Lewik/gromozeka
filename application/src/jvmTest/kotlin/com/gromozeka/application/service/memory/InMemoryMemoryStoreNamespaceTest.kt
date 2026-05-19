package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class InMemoryMemoryStoreNamespaceTest {
    @Test
    fun listNamespaceSummariesGroupsItemsByNamespace() = runBlocking {
        val projectNamespace = MemoryNamespace("project:test-project")
        val customNamespace = MemoryNamespace("work:hebrew")
        val now = Instant.parse("2026-05-19T10:00:00Z")
        val store = InMemoryMemoryStore()

        store.apply(
            MemoryUpdateBatch(
                sources = listOf(
                    externalSource("source:project", projectNamespace, now),
                    externalSource("source:work", customNamespace, now),
                )
            )
        )

        val summaries = store.listNamespaceSummaries().associateBy { it.namespace }

        assertEquals(setOf(projectNamespace, customNamespace), summaries.keys)
        assertEquals(1, summaries.getValue(projectNamespace).counts.sources)
        assertEquals(1, summaries.getValue(customNamespace).counts.sources)
        assertEquals("PROJECT", summaries.getValue(projectNamespace).kind.name)
        assertEquals("CUSTOM", summaries.getValue(customNamespace).kind.name)
    }

    private fun externalSource(
        id: String,
        namespace: MemoryNamespace,
        createdAt: Instant,
    ): MemorySource.ExternalRecord =
        MemorySource.ExternalRecord(
            id = MemorySource.Id(id),
            namespace = namespace,
            recordRef = id,
            contentText = "Remember $id",
            contentHash = id,
            observedAt = createdAt,
            createdAt = createdAt,
        )
}
