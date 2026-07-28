package com.gromozeka.application.service

import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerCatalogEntry
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class WorkerCatalogApplicationServiceTest {
    @Test
    fun `lists stable worker profiles with current availability`() = runBlocking {
        val registry = InMemoryConversationRuntimeWorkerRegistry()
        val now = Clock.System.now()
        register(registry, "online-worker", now)
        register(registry, "offline-worker", now - 2.minutes)

        val workers = WorkerCatalogApplicationService(registry).listWorkers()

        assertEquals(listOf("offline-worker", "online-worker"), workers.map { it.workerId.value })
        assertEquals(
            WorkerCatalogEntry.Status.OFFLINE,
            workers.first { it.workerId.value == "offline-worker" }.status,
        )
        assertEquals(
            WorkerCatalogEntry.Status.ONLINE,
            workers.first { it.workerId.value == "online-worker" }.status,
        )
        assertEquals(
            testWorkerEnvironmentProfile(now).architecture,
            workers.first { it.workerId.value == "online-worker" }.environmentProfile.architecture,
        )
    }

    private suspend fun register(
        registry: InMemoryConversationRuntimeWorkerRegistry,
        workerId: String,
        at: kotlinx.datetime.Instant,
    ) {
        registry.register(
            ConversationRuntimeWorkerRegistration(
                identity = ConversationRuntimeWorkerIdentity(
                    ConversationRuntimeWorkerId(workerId),
                    ConversationRuntimeWorkerSessionId("session-$workerId"),
                ),
                capabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
                tools = emptyList(),
                environmentProfile = testWorkerEnvironmentProfile(at),
                version = "test",
                startedAt = at,
                lastHeartbeatAt = at,
            ),
            staleBefore = at,
        )
    }
}
