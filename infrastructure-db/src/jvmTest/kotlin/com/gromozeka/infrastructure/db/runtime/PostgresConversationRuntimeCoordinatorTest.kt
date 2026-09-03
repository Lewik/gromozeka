package com.gromozeka.infrastructure.db.runtime

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeTaskOutcome
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeSchedulingSignal
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Instant
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
            val claimedTask = agentInvocationTask(
                conversationId = conversationId,
                messageId = "claimed-message",
                createdAt = Instant.fromEpochMilliseconds(1_000),
            )
            val queuedTask = agentInvocationTask(
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
            assertNull(coordinator.claim(claimedTask, firstWorker))

            val incident = coordinator.markActiveTaskInDoubt(
                conversationId = conversationId,
                taskId = claimedTask.id,
                executor = executor(firstWorker),
                message = "Worker heartbeat was lost",
                errorType = "WorkerUnavailable",
            )

            assertEquals(ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN, incident?.kind)
            assertEquals(startedAt, incident?.executionStartedAt)
            assertFalse(
                coordinator.completeActiveTask(
                    conversationId,
                    claimedTask.id,
                    executor(firstWorker),
                    ConversationRuntimeTaskOutcome.CompleteTurn,
                )
            )
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
    fun `postgres notification wakes the scheduler after durable commit`() = runBlocking {
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

        var runtimeDataSource: HikariDataSource? = null
        try {
            runtimeDataSource = pooledDataSource(schema).also(::createRuntimeSchema)
            val coordinator = PostgresConversationRuntimeCoordinator(
                dataSource = runtimeDataSource,
                json = Json {
                    encodeDefaults = true
                    ignoreUnknownKeys = false
                },
            )
            val signals = Channel<ConversationRuntimeSchedulingSignal>(Channel.UNLIMITED)
            val collector = launch {
                coordinator.schedulingSignals.collect(signals::send)
            }
            try {
                assertEquals(
                    ConversationRuntimeSchedulingSignal.ListenerReady,
                    withTimeout(5_000) { signals.receive() },
                )
                val task = agentInvocationTask(
                    conversationId = Conversation.Id("notified-conversation"),
                    messageId = "notified-message",
                    createdAt = Instant.fromEpochMilliseconds(1_000),
                )

                assertTrue(coordinator.submit(task))
                val changed = withTimeout(5_000) {
                    while (true) {
                        val signal = signals.receive()
                        if (signal == ConversationRuntimeSchedulingSignal.Changed(task.conversationId)) {
                            return@withTimeout signal
                        }
                    }
                    error("Unreachable")
                }
                assertEquals(ConversationRuntimeSchedulingSignal.Changed(task.conversationId), changed)
                assertEquals(task.id, coordinator.listReadyWorkItems(1).single().taskId)
            } finally {
                collector.cancelAndJoin()
                signals.close()
            }
        } finally {
            runtimeDataSource?.close()
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }

    @Test
    fun `postgres keeps a continuation ahead of later root input`() = runBlocking {
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
            val conversationId = Conversation.Id("continued-conversation")
            val root = agentInvocationTask(
                conversationId = conversationId,
                messageId = "root-message",
                createdAt = Instant.fromEpochMilliseconds(1_000),
            )
            val continuation = llmTask(root, Instant.fromEpochMilliseconds(2_000))
            val laterRoot = agentInvocationTask(
                conversationId = conversationId,
                messageId = "later-message",
                createdAt = Instant.fromEpochMilliseconds(3_000),
            )
            val worker = worker("worker-1", "session-1")

            assertTrue(coordinator.submit(root))
            assertEquals(root, coordinator.claim(root, worker))
            assertTrue(
                coordinator.markActiveTaskStarted(
                    conversationId,
                    root.id,
                    executor(worker),
                    Instant.fromEpochMilliseconds(4_000),
                )
            )
            assertTrue(coordinator.submit(laterRoot))
            assertTrue(
                coordinator.completeActiveTask(
                    conversationId,
                    root.id,
                    executor(worker),
                    ConversationRuntimeTaskOutcome.Continue(continuation),
                )
            )

            assertEquals(continuation.id, coordinator.listReadyWorkItems(1).single().taskId)
            assertEquals(continuation, coordinator.claim(continuation, worker("worker-1", "session-2")))
        } finally {
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }

    @Test
    fun `runtime lineage migration resets legacy scheduling state`() = runBlocking {
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
            runtimeDataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO conversation_runtime_records(
                        conversation_id,
                        record_json,
                        updated_at,
                        ready_task_id,
                        ready_at
                    )
                    VALUES (?, CAST(? AS jsonb), CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, "legacy-conversation")
                    statement.setString(
                        2,
                        """{
                            "conversationId":"legacy-conversation",
                            "revision":4,
                            "state":null,
                            "activeTask":null,
                            "activeInsertions":[],
                            "continuationTask":null,
                            "pendingTasks":[],
                            "toolExecutions":[],
                            "incidents":[],
                            "eventLog":[],
                            "completedIdempotencyKeys":[]
                        }""".trimIndent(),
                    )
                    statement.setString(3, "legacy-task")
                    statement.executeUpdate()
                }
                connection.createStatement().use { statement ->
                    executeSqlResource(statement, "db/migration/postgres/V32__reset_conversation_runtime_lineage.sql")
                }
            }

            val coordinator = PostgresConversationRuntimeCoordinator(
                runtimeDataSource,
                Json {
                    encodeDefaults = true
                    ignoreUnknownKeys = false
                },
            )
            val snapshot = coordinator.snapshot(Conversation.Id("legacy-conversation"))

            assertEquals(5, snapshot.revision)
            assertNull(snapshot.state)
            assertTrue(snapshot.pendingTasks.isEmpty())
            assertTrue(coordinator.listReadyWorkItems(10).isEmpty())
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
            val lockedTask = agentInvocationTask(
                conversationId = Conversation.Id("conversation-a"),
                messageId = "message-a",
                createdAt = Instant.fromEpochMilliseconds(1_000),
            )
            val availableTask = agentInvocationTask(
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
            val commandCancellationRequestedAt = Instant.fromEpochMilliseconds(5_000)
            assertTrue(
                coordinator.requestCommandTaskCancellation(
                    conversationId,
                    source.id,
                    commandCancellationRequestedAt,
                )
            )
            val cancelledSource = coordinator.upsertCommandTask(
                source.copy(
                    status = CommandTask.Status.CANCELLED,
                    statusMessage = "Command was cancelled",
                    completedAt = now,
                )
            ).task
            assertEquals(commandCancellationRequestedAt, cancelledSource.cancellationRequestedAt)
            assertEquals(commandCancellationRequestedAt, cancelledSource.updatedAt)
            assertEquals("Command was cancelled", cancelledSource.statusMessage)
            coordinator.synchronizeCommandMonitor(monitor, listOf(event))

            assertEquals(monitor, reloadedCoordinator.findCommandMonitor(conversationId, monitor.id))
            assertEquals(listOf(event), reloadedCoordinator.findCommandMonitorEvents(conversationId, monitor.id))
            assertEquals(listOf(monitor), reloadedCoordinator.snapshot(conversationId).commandMonitors)
            val monitorCancellationRequestedAt = Instant.fromEpochMilliseconds(5_000)
            assertTrue(
                reloadedCoordinator.requestCommandMonitorCancellation(
                    conversationId,
                    monitor.id,
                    monitorCancellationRequestedAt,
                )
            )
            assertEquals(
                monitorCancellationRequestedAt,
                reloadedCoordinator.findCommandMonitor(conversationId, monitor.id)?.cancellationRequestedAt,
            )
            assertTrue(
                reloadedCoordinator.markCommandMonitorEventsDelivered(
                    conversationId = conversationId,
                    eventIds = setOf(event.id),
                    deliveredAt = Instant.fromEpochMilliseconds(2_000),
                )
            )
            val terminal = monitor.copy(
                status = CommandMonitor.Status.COMPLETED,
                statusMessage = "Command monitor completed",
                completedAt = Instant.fromEpochMilliseconds(3_000),
                updatedAt = Instant.fromEpochMilliseconds(3_000),
                terminalOutputStartByte = 0,
                terminalOutput = "ERROR",
                terminalErrorOutput = "",
            )
            reloadedCoordinator.synchronizeCommandMonitor(terminal)
            assertEquals(
                monitorCancellationRequestedAt,
                reloadedCoordinator.findCommandMonitor(conversationId, monitor.id)?.cancellationRequestedAt,
            )
            assertEquals(
                "Command monitor completed",
                reloadedCoordinator.findCommandMonitor(conversationId, monitor.id)?.statusMessage,
            )
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

    private fun agentInvocationTask(
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
            payload = ConversationRuntimeTask.Payload.AgentInvocation(message, AGENT_DEFINITION_ID),
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

    private fun llmTask(
        parent: ConversationRuntimeTask,
        createdAt: Instant,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${parent.id.value}:llm"),
            conversationId = parent.conversationId,
            turnId = parent.turnId,
            parentTaskId = parent.id,
            payload = ConversationRuntimeTask.Payload.LlmCall(
                rootUserMessageId = parent.requireAgentInvocation().userMessage.id,
                agentDefinitionId = AGENT_DEFINITION_ID,
                iteration = 1,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "${parent.idempotencyKey}:llm",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
                target = ConversationRuntimeTaskTarget.Worker(
                    ConversationRuntimeWorkerId("worker-1")
                ),
            ),
            createdAt = createdAt,
        )

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

    private fun pooledDataSource(schema: String): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = System.getenv("GROMOZEKA_POSTGRES_URL")
                    ?: "jdbc:postgresql://localhost:5432/gromozeka"
                username = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
                password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
                maximumPoolSize = 2
                connectionInitSql = "SET search_path TO \"$schema\", public"
            }
        )

    private fun createRuntimeSchema(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                listOf(
                    "db/migration/postgres/V4__conversation_runtime_records.sql",
                    "db/migration/postgres/V31__conversation_runtime_ready_work.sql",
                ).forEach { resource -> executeSqlResource(statement, resource) }
            }
        }
    }

    private fun executeSqlResource(
        statement: java.sql.Statement,
        resource: String,
    ) {
        checkNotNull(javaClass.classLoader.getResource(resource))
            .readText()
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach(statement::execute)
    }

    private companion object {
        val AGENT_DEFINITION_ID = AgentDefinition.Id("agent-1")
    }
}
