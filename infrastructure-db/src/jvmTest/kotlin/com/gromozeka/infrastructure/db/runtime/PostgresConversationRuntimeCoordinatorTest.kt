package com.gromozeka.infrastructure.db.runtime

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresConversationRuntimeCoordinatorTest {
    @Test
    fun `claimed task remains fenced and becomes an incident when its worker is lost`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val schema = "runtime_coordinator_test_${UUID.randomUUID().toString().replace("-", "")}"
        val adminDataSource = dataSource()
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
            }
        }

        try {
            val coordinator = PostgresConversationRuntimeCoordinator(
                dataSource = dataSource(schema).also(::createRuntimeSchema),
                json = Json {
                    encodeDefaults = true
                    ignoreUnknownKeys = false
                },
            )
            val conversationId = Conversation.Id("fenced-conversation")
            val claimedTask = userTurnTask(
                conversationId = conversationId,
                messageId = "claimed-message",
                createdAt = Instant.fromEpochMilliseconds(1_000),
            )
            val queuedTask = userTurnTask(
                conversationId = conversationId,
                messageId = "queued-message",
                createdAt = Instant.fromEpochMilliseconds(2_000),
            )
            val firstWorker = worker("worker-1", "session-1")
            val secondWorker = worker("worker-1", "session-2")

            assertTrue(coordinator.submit(claimedTask))
            assertEquals(claimedTask, coordinator.claim(claimedTask, firstWorker))
            assertTrue(coordinator.submit(queuedTask))
            assertNull(coordinator.claim(claimedTask, secondWorker))
            assertNull(coordinator.listActiveTaskAssignments().single().startedAt)
            val startedAt = Instant.fromEpochMilliseconds(3_000)
            assertTrue(
                coordinator.markActiveTaskStarted(
                    conversationId = conversationId,
                    taskId = claimedTask.id,
                    executor = executor(firstWorker),
                    startedAt = startedAt,
                )
            )
            assertEquals(startedAt, coordinator.listActiveTaskAssignments().single().startedAt)

            val incident = coordinator.markActiveTaskInDoubt(
                conversationId = conversationId,
                taskId = claimedTask.id,
                executor = executor(firstWorker),
                message = "Worker heartbeat was lost",
                errorType = "WorkerUnavailable",
            )

            assertEquals(ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN, incident?.kind)
            assertEquals(startedAt, incident?.executionStartedAt)
            assertFalse(coordinator.completeActiveTask(conversationId, claimedTask.id, executor(firstWorker)))
            val snapshot = coordinator.snapshot(conversationId)
            assertNull(snapshot.activeTask)
            assertEquals(claimedTask.id, snapshot.incidents.single().task.id)
            assertEquals(
                listOf(
                    ConversationRuntimeTask.Payload.ExecutionIncident(claimedTask.id),
                    queuedTask.payload,
                ),
                snapshot.pendingTasks.map { it.payload },
            )
        } finally {
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }

    @Test
    fun `ready work index follows durable runtime state`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val schema = "runtime_coordinator_test_${UUID.randomUUID().toString().replace("-", "")}"
        val adminDataSource = dataSource()
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
            }
        }

        try {
            val runtimeDataSource = dataSource(schema)
            createRuntimeSchema(runtimeDataSource)
            val coordinator = PostgresConversationRuntimeCoordinator(
                dataSource = runtimeDataSource,
                json = Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                },
            )
            val lockedTask = userTurnTask(
                conversationId = Conversation.Id("conversation-a"),
                messageId = "message-a",
                createdAt = Instant.fromEpochMilliseconds(1_000),
            )
            val availableTask = userTurnTask(
                conversationId = Conversation.Id("conversation-b"),
                messageId = "message-b",
                createdAt = Instant.fromEpochMilliseconds(2_000),
            )
            assertTrue(coordinator.submit(lockedTask))
            assertTrue(coordinator.submit(availableTask))

            assertEquals(
                listOf(lockedTask.id, availableTask.id),
                coordinator.listReadyWorkItems(limit = 10).map { it.taskId },
            )
            assertEquals(lockedTask, coordinator.claim(lockedTask, worker("worker-1", "session-1")))
            assertEquals(
                listOf(availableTask.id),
                coordinator.listReadyWorkItems(limit = 10).map { it.taskId },
            )
        } finally {
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }

    @Test
    fun `command monitors and events survive postgres round trip`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val schema = "runtime_coordinator_test_${UUID.randomUUID().toString().replace("-", "")}"
        val adminDataSource = dataSource()
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
            }
        }

        try {
            val runtimeDataSource = dataSource(schema).also(::createRuntimeSchema)
            val json = Json {
                encodeDefaults = true
                ignoreUnknownKeys = false
            }
            val coordinator = PostgresConversationRuntimeCoordinator(runtimeDataSource, json)
            val reloadedCoordinator = PostgresConversationRuntimeCoordinator(runtimeDataSource, json)
            val conversationId = Conversation.Id("monitor-conversation")
            val now = Instant.fromEpochMilliseconds(1_000)
            val source = CommandTask(
                id = CommandTask.Id("source-command"),
                conversationId = conversationId,
                workerId = ConversationRuntimeWorkerId("worker-1"),
                workspaceMountId = WorkspaceMount.Id("mount-1"),
                command = "tail -f app.log",
                workingDirectory = "/tmp",
                status = CommandTask.Status.WORKING,
                processId = 100,
                processStartedAt = now,
                outputFile = "/tmp/source-command.log",
                outputBytes = 12,
                createdAt = now,
                updatedAt = now,
            )
            val monitor = CommandMonitor(
                id = CommandMonitor.Id("monitor-1"),
                conversationId = conversationId,
                commandTaskId = source.id,
                workerId = source.workerId,
                workspaceMountId = source.workspaceMountId,
                agentDefinitionId = AGENT_DEFINITION_ID,
                filterCommand = "grep ERROR",
                mode = CommandMonitor.Mode.CONTINUOUS,
                startFrom = CommandMonitor.StartFrom.NOW,
                status = CommandMonitor.Status.WORKING,
                sourceOutputCursor = 12,
                processId = 101,
                processStartedAt = now,
                outputFile = "/tmp/monitor-1.log",
                errorFile = "/tmp/monitor-1.err",
                outputBytes = 6,
                eventOutputCursor = 6,
                eventCount = 1,
                createdAt = now,
                updatedAt = now,
                terminalNotificationRequestedAt = now,
            )
            val event = CommandMonitorEvent(
                id = CommandMonitorEvent.Id("monitor-1:6"),
                conversationId = conversationId,
                monitorId = monitor.id,
                outputStartByte = 0,
                outputEndByte = 6,
                output = "ERROR",
                outputTruncatedBefore = false,
                occurredAt = now,
                deliveryRequested = true,
            )

            coordinator.upsertCommandTask(source)
            coordinator.synchronizeCommandMonitor(monitor, listOf(event))

            assertEquals(monitor, reloadedCoordinator.findCommandMonitor(conversationId, monitor.id))
            assertEquals(listOf(event), reloadedCoordinator.findCommandMonitorEvents(conversationId, monitor.id))
            assertEquals(listOf(monitor), reloadedCoordinator.snapshot(conversationId).commandMonitors)
            assertTrue(reloadedCoordinator.requestCommandMonitorCancellation(conversationId, monitor.id, now))
            assertEquals(
                now,
                reloadedCoordinator.findCommandMonitor(conversationId, monitor.id)?.cancellationRequestedAt,
            )
            assertTrue(
                reloadedCoordinator.markCommandMonitorEventsDelivered(
                    conversationId = conversationId,
                    eventIds = setOf(event.id),
                    deliveredAt = Instant.fromEpochMilliseconds(2_000),
                )
            )
            val terminal = requireNotNull(
                reloadedCoordinator.findCommandMonitor(conversationId, monitor.id)
            ).copy(
                status = CommandMonitor.Status.COMPLETED,
                completedAt = Instant.fromEpochMilliseconds(3_000),
                updatedAt = Instant.fromEpochMilliseconds(3_000),
                terminalOutputStartByte = 0,
                terminalOutput = "ERROR",
                terminalErrorOutput = "",
            )
            reloadedCoordinator.synchronizeCommandMonitor(terminal)
            assertTrue(
                reloadedCoordinator.markCommandMonitorTerminalNotificationDelivered(
                    conversationId = conversationId,
                    monitorId = monitor.id,
                    deliveredAt = Instant.fromEpochMilliseconds(4_000),
                )
            )
            assertEquals(
                Instant.fromEpochMilliseconds(4_000),
                coordinator.findCommandMonitor(conversationId, monitor.id)
                    ?.terminalNotificationDeliveredAt,
            )
        } finally {
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }

    private fun userTurnTask(
        conversationId: Conversation.Id,
        messageId: String,
        createdAt: Instant,
    ): ConversationRuntimeTask {
        val message = Conversation.Message(
            id = Conversation.Message.Id(messageId),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Text $messageId")),
            createdAt = createdAt,
        )
        return ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id(messageId),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.UserTurn(message, AGENT_DEFINITION_ID),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "test:$messageId",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
                target = ConversationRuntimeTaskTarget.Worker(
                    ConversationRuntimeWorkerId("worker-1")
                ),
            ),
            createdAt = createdAt,
        )
    }

    private suspend fun PostgresConversationRuntimeCoordinator.claim(
        task: ConversationRuntimeTask,
        worker: ConversationRuntimeWorkerIdentity,
    ): ConversationRuntimeTask? =
        claimDeliveredTask(
            conversationId = task.conversationId,
            taskId = task.id,
            executor = executor(worker),
            executorCapabilities = task.requirements.capabilities,
            workerWorkspaceMountIds = emptySet(),
        )

    private fun executor(worker: ConversationRuntimeWorkerIdentity): ConversationRuntimeExecutorIdentity =
        ConversationRuntimeExecutorIdentity.Worker(worker)

    private fun worker(
        workerId: String,
        sessionId: String,
    ): ConversationRuntimeWorkerIdentity =
        ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId(workerId),
            sessionId = ConversationRuntimeWorkerSessionId(sessionId),
        )

    private fun dataSource(schema: String? = null): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
            currentSchema = schema
        }

    private fun createRuntimeSchema(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                listOf(
                    "db/migration/postgres/V4__conversation_runtime_records.sql",
                    "db/migration/postgres/V31__conversation_runtime_ready_work.sql",
                ).forEach { resource ->
                    checkNotNull(javaClass.classLoader.getResource(resource))
                        .readText()
                        .split(';')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .forEach(statement::execute)
                }
            }
        }
    }

    private companion object {
        val AGENT_DEFINITION_ID = AgentDefinition.Id("agent-1")
    }
}
