package com.gromozeka.server

import com.gromozeka.application.service.MemoryToolApplicationService
import com.gromozeka.application.service.memory.InMemoryMemoryStore
import com.gromozeka.application.service.memory.MemoryAsyncOperationApplicationService
import com.gromozeka.application.service.memory.MemoryEmbeddingIndexer
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeExecutorDescriptor
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeServerSessionId
import com.gromozeka.domain.service.WorkspaceDomainService
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import org.mockito.Mockito

class MemoryToolNamespaceIsolationTest {
    private val visibleNamespace = MemoryNamespace("user:visible")
    private val foreignNamespace = MemoryNamespace("user:foreign")
    private val memoryStore = InMemoryMemoryStore()
    private val service = MemoryToolApplicationService(
        conversationService = mock(),
        workspaceService = mock(),
        memoryOperations = mock(),
        memoryEmbeddingIndexer = mock(),
        memoryStore = memoryStore,
        runtimeExecutorDescriptor = ConversationRuntimeExecutorDescriptor(
            identity = ConversationRuntimeExecutorIdentity.Server(
                ConversationRuntimeServerSessionId("test-server-session")
            ),
            capabilities = setOf(ConversationRuntimeCapability.MEMORY_PIPELINE),
        ),
    )

    @Test
    fun `run status hides a run from another namespace`() = runBlocking {
        val foreignRun = memoryRun("foreign-run", foreignNamespace)
        memoryStore.apply(MemoryUpdateBatch(runs = listOf(foreignRun)))

        val result = service.memoryRunStatus(
            namespace = visibleNamespace,
            runIdValue = foreignRun.id.value,
        )

        assertContains(result, "Memory run not found")
        assertFalse(result.contains(foreignNamespace.value))
        assertFalse(result.contains(foreignRun.summary))
    }

    @Test
    fun `queue status includes only runs from the requested namespace`() = runBlocking {
        val visibleRun = memoryRun("visible-run", visibleNamespace)
        val foreignRun = memoryRun("foreign-run", foreignNamespace)
        memoryStore.apply(MemoryUpdateBatch(runs = listOf(visibleRun, foreignRun)))

        val result = service.memoryQueueStatus(visibleNamespace)

        assertContains(result, visibleRun.id.value)
        assertFalse(result.contains(foreignRun.id.value))
        assertFalse(result.contains(foreignNamespace.value))
    }

    private fun memoryRun(id: String, namespace: MemoryNamespace): MemoryRun {
        val now = Instant.fromEpochMilliseconds(1)
        return MemoryRun(
            id = MemoryRun.Id(id),
            namespace = namespace,
            runType = MemoryRun.Type.REMEMBER,
            summary = "summary:$id",
            status = MemoryRun.Status.RUNNING,
            createdAt = now,
            startedAt = now,
        )
    }

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
