package com.gromozeka.server

import com.gromozeka.application.service.BackgroundActivityCompletionApplicationService
import com.gromozeka.application.service.BackgroundActivityContinuationPolicy
import com.gromozeka.application.service.BackgroundActivityLifecycleApplicationService
import com.gromozeka.application.service.ConversationRuntimeDispatcher
import com.gromozeka.application.service.InMemoryConversationRuntimeEventBus
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.BinaryContent
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.repository.BackgroundActivityOrigin
import com.gromozeka.domain.repository.BackgroundActivityOriginRepository
import com.gromozeka.domain.service.*
import com.gromozeka.infrastructure.db.runtime.PostgresConversationRuntimeCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant

@EnabledIfEnvironmentVariable(named = "GROMOZEKA_POSTGRES_RUNTIME_TEST", matches = "true")
class PostgresBackgroundActivityDeliveryTest {
    @Test
    fun `failed delivery remains failed while later monitor delivery survives repository recreation`(): Unit = runBlocking {
        database { dataSource ->
            val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
            val conversation = Conversation.Id("background-delivery")
            val agent = AgentDefinition.Id("agent")
            val executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("test-server"))
            val at = Instant.fromEpochSeconds(1)
            val coordinator = PostgresConversationRuntimeCoordinator(dataSource, json)
            val failed = CommandTask(CommandTask.Id("failed-command"), conversation, ConversationRuntimeWorkerId("worker"),
                WorkspaceMount.Id("mount"), agent, command = "never executed by this test", workingDirectory = "/tmp",
                status = CommandTask.Status.COMPLETED, processId = 1, processStartedAt = at, outputFile = "/tmp/not-opened",
                outputBytes = 4, exitCode = 0, createdAt = at, updatedAt = at, completedAt = at,
                completionNotificationRequestedAt = at, terminalOutputStartByte = 0, terminalOutputContent = BinaryContent.fromText("kept"))
            coordinator.upsertCommandTask(failed)
            val sourceKey = "command-task:${failed.id.value}"
            assertTrue(dispatcher(coordinator).submitBackgroundActivityCompletion(conversation, sourceKey))
            val failedTask = coordinator.listPending(conversation).single()
            claim(coordinator, failedTask, executor)
            assertNotNull(coordinator.markActiveTaskInDoubt(conversation, failedTask.id, executor,
                "Synthetic delivery failure", "IllegalStateException"))

            val restarted = PostgresConversationRuntimeCoordinator(dataSource, json)
            assertFalse(dispatcher(restarted).submitBackgroundActivityCompletion(conversation, sourceKey))
            val monitor = CommandMonitor(CommandMonitor.Id("monitor"), conversation, failed.id, failed.workerId, failed.workspaceMountId,
                agent, filterCommand = "never executed by this test", mode = CommandMonitor.Mode.CONTINUOUS,
                startFrom = CommandMonitor.StartFrom.BEGINNING, status = CommandMonitor.Status.WORKING,
                sourceOutputCursor = 0, processId = 2, processStartedAt = at, outputFile = "/tmp/not-opened-monitor",
                errorFile = "/tmp/not-opened-error", outputBytes = 5, eventOutputCursor = 5, eventCount = 1,
                createdAt = at, updatedAt = at)
            val event = CommandMonitorEvent(CommandMonitorEvent.Id("new-event"), conversation, monitor.id,
                0, 5, BinaryContent.fromText("event"), false, at, true)
            restarted.synchronizeCommandMonitor(monitor, listOf(event))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                lifecycle(restarted, scope).start()
                val delivery = withTimeout(10_000) {
                    while (true) {
                        val task = restarted.listPending(conversation).singleOrNull {
                            it.payload is ConversationRuntimeTask.Payload.BackgroundActivityCompletion
                        }
                        if (task != null) return@withTimeout task
                        delay(20)
                    }
                    error("Unreachable")
                }
                assertEquals("command-monitor-event:${event.id.value}",
                    assertIs<ConversationRuntimeTask.Payload.BackgroundActivityCompletion>(delivery.payload).sourceKey)
                val incidentTask = restarted.listPending(conversation).first()
                assertIs<ConversationRuntimeTask.Payload.ExecutionIncident>(incidentTask.payload)
                claim(restarted, incidentTask, executor)
                assertTrue(restarted.completeActiveTask(conversation, incidentTask.id, executor, ConversationRuntimeTaskOutcome.CompleteTurn))
                claim(restarted, delivery, executor)
                val completions = BackgroundActivityCompletionApplicationService(restarted, emptyOrigins(),
                    BackgroundActivityContinuationPolicy { _, _ -> false })
                val batch = completions.prepareBatch(conversation, "command-monitor-event:${event.id.value}")
                assertTrue(batch.commandTasks.isEmpty())
                assertEquals(listOf(event.id), batch.monitorDeliveries.single().events.map { it.id })
                completions.markDelivered(batch, Clock.System.now())
                assertTrue(restarted.completeActiveTask(conversation, delivery.id, executor, ConversationRuntimeTaskOutcome.CompleteTurn))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }

            val verified = PostgresConversationRuntimeCoordinator(dataSource, json)
            assertNotNull(verified.findTaskIncident(conversation, failedTask.id))
            assertEquals(failed, verified.findCommandTask(conversation, failed.id))
            assertNotNull(verified.findCommandMonitorEvents(conversation, monitor.id).single().deliveredAt)
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                lifecycle(verified, secondScope).start()
                delay(500)
                assertTrue(verified.listPending(conversation).isEmpty())
                assertEquals(1, verified.schedulingSnapshot(conversation).incidents.size)
            } finally {
                secondScope.coroutineContext.job.cancelAndJoin()
            }
        }
    }

    private suspend fun claim(coordinator: ConversationRuntimeCoordinator, task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity) {
        assertEquals(task, coordinator.claimDeliveredTask(task.conversationId, task.id, executor, task.requirements.capabilities, emptySet()))
        assertTrue(coordinator.markActiveTaskStarted(task.conversationId, task.id, executor, Clock.System.now()))
    }

    private fun dispatcher(coordinator: ConversationRuntimeCoordinator) = ConversationRuntimeDispatcher(
        coordinator, InMemoryConversationRuntimeEventBus(), testConversationRuntimeStateSyncService(coordinator),
        ArtifactReferenceValidator { _, _ -> },
    )

    private fun lifecycle(coordinator: ConversationRuntimeCoordinator, scope: CoroutineScope) = BackgroundActivityLifecycleApplicationService(
        object : CommandTaskLifecycleEventStream { override val events = emptyFlow<CommandTaskLifecycleEvent>() },
        object : CommandMonitorLifecycleEventStream { override val events = emptyFlow<CommandMonitorLifecycleEvent>() },
        coordinator, dispatcher(coordinator), scope,
    )

    private fun emptyOrigins() = object : BackgroundActivityOriginRepository {
        override suspend fun bindWhileRequestPending(origin: BackgroundActivityOrigin, at: Instant) = error("No launches in this test")
        override suspend fun find(conversationId: Conversation.Id, keys: Set<BackgroundActivityOrigin.Key>) =
            emptyMap<BackgroundActivityOrigin.Key, BackgroundActivityOrigin>()
    }

    private suspend fun database(block: suspend (DataSource) -> Unit) {
        val url = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL"))
        val user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
        val password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        val schema = "background_delivery_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = DriverManagerDataSource(url, user, password)
        JdbcTemplate(admin).execute("CREATE SCHEMA $schema")
        try {
            val source = DriverManagerDataSource(url + (if ('?' in url) "&" else "?") + "currentSchema=$schema,public", user, password)
            Flyway.configure().dataSource(source).locations("classpath:db/migration/postgres").schemas(schema).defaultSchema(schema).load().migrate()
            block(source)
        } finally {
            JdbcTemplate(admin).execute("DROP SCHEMA $schema CASCADE")
        }
    }
}
