package com.gromozeka.infrastructure.db.runtime

import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventLogEntry
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.ConversationRuntimeSchedulingState
import com.gromozeka.domain.service.ConversationHistoryMutationKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlin.test.assertFailsWith
import com.gromozeka.domain.model.BinaryContent
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.encodeToString
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
    fun `event cursor and bounded replay do not decode unrelated runtime payloads`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "history_cursor_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = dataSource()
        admin.connection.use { it.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") } }
        try {
            val source = dataSource(schema).also(::createRuntimeSchema)
            val coordinator = PostgresConversationRuntimeCoordinator(source, Json { encodeDefaults = true })
            val conversationId = Conversation.Id("cursor")
            assertEquals(0L, coordinator.lastEventSequence(conversationId))
            repeat(5) {
                coordinator.recordEvent(com.gromozeka.domain.service.ConversationRuntimeEvent.ExecutionCompleted(conversationId, shouldNotifyUser = false))
            }
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("UPDATE conversation_runtime_records SET command_tasks = '[{\"intentionallyInvalid\":true}]'::jsonb")
                }
            }
            assertEquals(5L, coordinator.lastEventSequence(conversationId))
            assertEquals(listOf(4L, 5L), coordinator.listEventLogEntries(conversationId, null, 2).map { it.sequence })
            assertEquals(listOf(2L, 3L), coordinator.listEventLogEntries(conversationId, 1, 2).map { it.sequence })
            assertTrue(coordinator.listEventLogEntries(conversationId, 5, 2).isEmpty())
        } finally {
            admin.connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }

    @Test
    fun `binary migration preserves existing command output and permits zero bytes in jsonb`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "binary_migration_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = dataSource()
        admin.connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        try {
            val source = dataSource(schema).also(::createLegacyRuntimeSchema)
            val text = "שלום \\u0000 literal\nold output"
            val encoded = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))
            val old = """{"conversationId":"migration","commandTasks":[{"id":"task","status":"COMPLETED","terminalOutput":$encoded}],"commandMonitors":[{"id":"monitor","terminalOutput":$encoded,"terminalErrorOutput":""}],"commandMonitorEvents":[{"id":"event","output":$encoded}]}"""
            source.connection.use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE artifacts(id TEXT)") }
                connection.prepareStatement("INSERT INTO conversation_runtime_records(conversation_id,record_json) VALUES ('migration',?::jsonb)").use {
                    it.setString(1, old)
                    it.executeUpdate()
                }
                connection.createStatement().use {
                    executeSqlResource(it, "db/migration/postgres/V60__binary_command_output.sql")
                }
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT record_json::text FROM conversation_runtime_records WHERE conversation_id='migration'").use { row ->
                        assertTrue(row.next())
                        val json = Json.parseToJsonElement(row.getString(1)) as kotlinx.serialization.json.JsonObject
                        for ((array, field) in listOf("commandTasks" to "terminalOutputContent", "commandMonitors" to "terminalOutputContent", "commandMonitorEvents" to "content")) {
                            val item = (json.getValue(array) as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
                            val restored = Json.decodeFromString<BinaryContent>(item.getValue(field).toString())
                            kotlin.test.assertContentEquals(text.encodeToByteArray(), restored.bytes())
                            assertFalse(item.containsKey("terminalOutput"))
                        }
                    }
                }
            }
            source.connection.use { c -> c.createStatement().use { executeSqlResource(it, "db/migration/postgres/V64__split_conversation_runtime_storage.sql") } }
            val coordinator = PostgresConversationRuntimeCoordinator(source, Json { encodeDefaults = true })
            val bytes = ByteArray(1024) { it.toByte() }
            val task = CommandTask(CommandTask.Id("raw"), Conversation.Id("raw-conversation"),
                ConversationRuntimeWorkerId("worker"), WorkspaceMount.Id("mount"), command = "binary", workingDirectory = "/workspace",
                status = CommandTask.Status.COMPLETED, processId = 1, processStartedAt = null, outputFile = "/output",
                outputBytes = bytes.size.toLong(), createdAt = Instant.fromEpochSeconds(1), updatedAt = Instant.fromEpochSeconds(2),
                terminalOutputStartByte = 0, terminalOutputContent = BinaryContent.fromBytes(bytes))
            coordinator.upsertCommandTask(task)
            kotlin.test.assertContentEquals(bytes, coordinator.findCommandTask(task.conversationId, task.id)!!.terminalOutputContent!!.bytes())
        } finally {
            admin.connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
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
    fun `postgres keeps continuation order and interrupts only the expected turn`() = runBlocking {
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
            assertFalse(coordinator.requestInterrupt(conversationId, com.gromozeka.domain.service.ConversationRuntimeTurnId("stale")))
            assertTrue(coordinator.requestInterrupt(conversationId, continuation.turnId))
            assertEquals(com.gromozeka.domain.service.ConversationExecutionState.ControlState.INTERRUPTING,
                coordinator.find(conversationId)?.controlState)
            coordinator.abort(conversationId)
            assertEquals(listOf(laterRoot.id), coordinator.listPending(conversationId).map { it.id })
            assertTrue(coordinator.requestResume(conversationId))
            assertFalse(coordinator.requestInterrupt(conversationId, continuation.turnId))
            assertEquals(laterRoot.id, coordinator.listReadyWorkItems(1).single().taskId)
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
            val runtimeDataSource = dataSource(schema).also(::createLegacyRuntimeSchema)
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

            runtimeDataSource.connection.use { c -> c.createStatement().use { executeSqlResource(it, "db/migration/postgres/V64__split_conversation_runtime_storage.sql") } }
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
    fun `response command cancellation excludes visual handlers and preserves explicit cancellation`() = runBlocking<Unit> {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "visual_cancellation_${UUID.randomUUID().toString().replace("-", "") }"
        val admin = dataSource()
        admin.connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        try {
            val source = dataSource(schema).also(::createRuntimeSchema)
            val json = Json { encodeDefaults = true }
            val coordinator = PostgresConversationRuntimeCoordinator(source, json)
            val reloaded = PostgresConversationRuntimeCoordinator(source, json)
            val conversationId = Conversation.Id("visual-cancellation")
            val now = Instant.fromEpochMilliseconds(1_000)
            val ordinary = CommandTask(
                id = CommandTask.Id("ordinary"), conversationId = conversationId,
                workerId = ConversationRuntimeWorkerId("worker-1"), workspaceMountId = WorkspaceMount.Id("mount-1"),
                command = "tail -f log", workingDirectory = "/tmp", status = CommandTask.Status.WORKING,
                processId = 100, processStartedAt = now, outputFile = "/tmp/ordinary.log", outputBytes = 0,
                createdAt = now, updatedAt = now,
            )
            val visual = ordinary.copy(id = CommandTask.Id("visual-handler"), visualId = "visual-1")
            val terminal = ordinary.copy(id = CommandTask.Id("finished"), status = CommandTask.Status.COMPLETED, exitCode = 0, completedAt = now)
            val foreign = ordinary.copy(id = CommandTask.Id("another-conversation"), conversationId = Conversation.Id("other"))
            listOf(ordinary, visual, terminal, foreign).forEach { coordinator.upsertCommandTask(it) }
            val interruptedAt = Instant.fromEpochMilliseconds(2_000)
            assertEquals(1, coordinator.requestCommandTaskCancellations(conversationId, interruptedAt))
            assertEquals(interruptedAt, reloaded.findCommandTask(conversationId, ordinary.id)?.cancellationRequestedAt)
            assertNull(reloaded.findCommandTask(conversationId, visual.id)?.cancellationRequestedAt)
            assertNull(reloaded.findCommandTask(conversationId, terminal.id)?.cancellationRequestedAt)
            assertNull(reloaded.findCommandTask(foreign.conversationId, foreign.id)?.cancellationRequestedAt)
            assertEquals(CommandTask.Status.WORKING, reloaded.findCommandTask(conversationId, visual.id)?.status)

            val explicitlyStoppedAt = Instant.fromEpochMilliseconds(3_000)
            assertTrue(coordinator.requestCommandTaskCancellation(conversationId, visual.id, explicitlyStoppedAt))
            assertEquals(explicitlyStoppedAt, reloaded.findCommandTask(conversationId, visual.id)?.cancellationRequestedAt)
        } finally {
            admin.connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA $schema CASCADE") } }
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
                content = BinaryContent.fromText("ERROR"),
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
                terminalOutputContent = ("ERROR")?.let(BinaryContent::fromText),
                terminalErrorContent = ("")?.let(BinaryContent::fromText),
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

    @Test
    fun `worker inventory matches global filtering across mixed conversations`() = runBlocking {
        withInventoryDatabase { source, coordinator, json ->
            val workerA = ConversationRuntimeWorkerId("inventory-worker-a")
            val workerB = ConversationRuntimeWorkerId("inventory-worker-b")
            val quotedWorker = ConversationRuntimeWorkerId("worker-\"quoted\\id")
            val tasks = listOf(
                inventoryTask("a-running", "mixed-conversation", workerA),
                inventoryTask("b-running", "mixed-conversation", workerB),
                inventoryTask("a-terminal", "another-conversation", workerA, terminal = true),
                inventoryTask("quoted", "mixed-conversation", quotedWorker),
            )
            tasks.forEach { coordinator.upsertCommandTask(it) }
            val monitors = tasks.map(::inventoryMonitor)
            monitors.forEach { coordinator.synchronizeCommandMonitor(it) }
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO conversation_runtime_records(conversation_id, scheduling) " +
                            "VALUES ('empty-conversation', '{\"conversationId\":\"empty-conversation\"}'::jsonb)"
                    )
                }
            }
            val allTasks = coordinator.findCommandTasks()
            val allMonitors = coordinator.findCommandMonitors()
            for (worker in listOf(workerA, workerB, quotedWorker, ConversationRuntimeWorkerId("no-inventory"))) {
                assertEquals(allTasks.filter { it.workerId == worker }, coordinator.findCommandTasks(workerId = worker))
                assertEquals(allMonitors.filter { it.workerId == worker }, coordinator.findCommandMonitors(workerId = worker))
            }
            assertTrue(coordinator.findCommandTasks(workerId = workerA).any { it.isTerminal })
            assertTrue(coordinator.findCommandMonitors(workerId = workerA).any { it.isTerminal })
            assertEquals("\"inventory-worker-a\"", json.encodeToString(workerA))
            println("POSTGRES_INVENTORY_FILTER_OK")
        }
    }

    @Test
    fun `worker inventory does not deserialize unrelated runtime fields`() = runBlocking {
        withInventoryDatabase { source, coordinator, _ ->
            val worker = ConversationRuntimeWorkerId("projection-worker")
            val own = inventoryTask("own", "own-conversation", worker)
            val foreign = inventoryTask("foreign", "foreign-conversation", ConversationRuntimeWorkerId("another-worker"))
            listOf(own, foreign).forEach { coordinator.upsertCommandTask(it) }
            val monitor = inventoryMonitor(own)
            coordinator.synchronizeCommandMonitor(monitor)
            // Valid JSON but an intentionally incompatible trace schema: inventory must not decode it,
            // even in the selected Worker's own conversation. No real runtime data is used here.
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO conversation_runtime_trace(conversation_id, sequence, created_at, entry_json) " +
                            "SELECT conversation_id, 99, CURRENT_TIMESTAMP, '\"not part of command inventory\"'::jsonb FROM conversation_runtime_records"
                    )
                }
            }
            assertEquals(listOf(own), coordinator.findCommandTasks(workerId = worker))
            assertEquals(listOf(monitor), coordinator.findCommandMonitors(workerId = worker))
            assertEquals(emptyList(), coordinator.findCommandTasks(workerId = ConversationRuntimeWorkerId("missing")))
            println("POSTGRES_INVENTORY_PROJECTION_OK")
        }
    }

    @Test
    fun `selective worker inventory uses worker identifier indexes`() = runBlocking {
        withInventoryDatabase { source, coordinator, json ->
            val selectedWorker = ConversationRuntimeWorkerId("selected-worker")
            val selected = inventoryTask("selected-task", "selected-conversation", selectedWorker)
            coordinator.upsertCommandTask(selected)
            coordinator.synchronizeCommandMonitor(inventoryMonitor(selected))
            val unrelated = inventoryTask("template", "template-conversation", ConversationRuntimeWorkerId("other-worker"))
            source.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO conversation_runtime_records(conversation_id, scheduling, command_tasks, command_monitors)
                    SELECT 'bulk-' || n, jsonb_build_object('conversationId', 'bulk-' || n),
                        jsonb_build_array(CAST(? AS jsonb) || jsonb_build_object(
                            'id', 'bulk-task-' || n, 'conversationId', 'bulk-' || n
                        )),
                        jsonb_build_array(CAST(? AS jsonb) || jsonb_build_object(
                            'id', 'bulk-monitor-' || n, 'conversationId', 'bulk-' || n,
                            'commandTaskId', 'bulk-task-' || n
                        ))
                    FROM generate_series(1, 10000) AS n
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, json.encodeToString(unrelated))
                    statement.setString(2, json.encodeToString(inventoryMonitor(unrelated)))
                    assertEquals(10000, statement.executeUpdate())
                }
                connection.createStatement().use { it.execute("ANALYZE conversation_runtime_records") }
                for ((kind, indexName) in listOf(
                    WorkerCommandInventoryKind.TASKS to "idx_conversation_runtime_command_tasks_workers",
                    WorkerCommandInventoryKind.MONITORS to "idx_conversation_runtime_command_monitors_workers",
                )) {
                    connection.prepareStatement(
                        "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + workerCommandInventorySql(kind)
                    ).use { statement ->
                        val encodedWorker = json.encodeToString(selectedWorker)
                        statement.setString(1, "[$encodedWorker]")
                        statement.setString(2, encodedWorker)
                        statement.executeQuery().use { result ->
                            assertTrue(result.next())
                            val plan = json.parseToJsonElement(result.getString(1)).jsonArray.single()
                                .jsonObject.getValue("Plan").jsonObject
                            assertTrue(plan.toString().contains(indexName), "Expected $indexName in plan: $plan")
                            assertEquals(1.0, plan.getValue("Actual Rows").jsonPrimitive.double)
                            println("POSTGRES_INVENTORY_PLAN_OK kind=${kind.operation} index=$indexName result_rows=1")
                        }
                    }
                }
            }
            assertEquals(listOf(selected), coordinator.findCommandTasks(workerId = selectedWorker))
            assertEquals(listOf(inventoryMonitor(selected)), coordinator.findCommandMonitors(workerId = selectedWorker))
        }
    }

    @Test
    fun `scheduling is independent of journals and unrelated components and noops do not rewrite rows`() = runBlocking {
        withInventoryDatabase { source, coordinator, _ ->
            val conversationId = Conversation.Id("isolated-scheduling")
            val task = agentInvocationTask(conversationId, "queued", Instant.fromEpochSeconds(1))
            val owner = worker("worker-1", "session-1")
            assertTrue(coordinator.submit(task))
            source.connection.use { c -> c.createStatement().use { s ->
                s.execute("UPDATE conversation_runtime_records SET command_tasks = '[\"invalid\"]', memory_operations = '[\"invalid\"]'")
                s.execute("UPDATE conversation_runtime_trace SET entry_json = '\"invalid\"'")
            } }
            assertEquals(task.id, coordinator.listReadyWorkItems(10).single().taskId)
            assertEquals(listOf(task), coordinator.listPending(conversationId))
            assertTrue(coordinator.schedulingSnapshot(conversationId).containsTask(task.id))
            assertEquals(task, coordinator.claim(task, owner))
            assertTrue(coordinator.confirmActiveTaskOwner(conversationId, task.id, executor(owner)))
            assertEquals(task, coordinator.listActiveTaskAssignments().single().task)
            assertEquals(task.id, coordinator.find(conversationId)!!.activeTaskId)
            assertTrue(coordinator.markActiveTaskStarted(conversationId, task.id, executor(owner), Instant.fromEpochSeconds(2)))
            val before = runtimeRowFingerprint(source)
            assertNull(coordinator.claim(task, owner))
            assertFalse(coordinator.submit(task))
            assertTrue(coordinator.markActiveTaskStarted(conversationId, task.id, executor(owner), Instant.fromEpochSeconds(3)))
            assertFalse(coordinator.requestCommandMonitorCancellation(conversationId, CommandMonitor.Id("missing"), Instant.fromEpochSeconds(4)))
            assertEquals(before, runtimeRowFingerprint(source))
            assertTrue(coordinator.completeActiveTask(conversationId, task.id, executor(owner), ConversationRuntimeTaskOutcome.CompleteTurn))
        }
    }

    @Test
    fun `incident transitions still clear tool executions and duplicate command writes are noops`() = runBlocking {
        withInventoryDatabase { source, coordinator, _ ->
            val command = inventoryTask("command", "cleanup", ConversationRuntimeWorkerId("worker-1"))
            coordinator.upsertCommandTask(command)
            val before = runtimeRowFingerprint(source)
            coordinator.upsertCommandTask(command)
            assertEquals(before, runtimeRowFingerprint(source))
            for (deliveryFailure in listOf(false, true)) {
                val task = agentInvocationTask(Conversation.Id("cleanup-$deliveryFailure"), "task-$deliveryFailure", Instant.fromEpochSeconds(1))
                val owner = worker("worker-1", "session-1")
                assertTrue(coordinator.submit(task))
                assertEquals(task, coordinator.claim(task, owner))
                assertTrue(coordinator.upsertToolExecution(task.conversationId, ConversationRuntimeToolExecution(
                    toolCallId = Conversation.Message.ContentItem.ToolCall.Id("tool"), toolName = "test",
                    status = ConversationRuntimeToolExecution.Status.RUNNING, runtimeTaskId = task.id,
                    executor = executor(owner), startedAt = task.createdAt,
                )))
                if (deliveryFailure) coordinator.recordClaimedTaskDeliveryFailure(task.conversationId, task.id, executor(owner), "failed", null)
                else {
                    coordinator.markActiveTaskStarted(task.conversationId, task.id, executor(owner), task.createdAt)
                    coordinator.markActiveTaskInDoubt(task.conversationId, task.id, executor(owner), "lost", null)
                }
                assertTrue(coordinator.snapshot(task.conversationId).toolExecutions.isEmpty())
                coordinator.abort(task.conversationId)
            }
        }
    }

    @Test
    fun `journal failures roll back state counters and traces together`() = runBlocking {
        withInventoryDatabase { source, coordinator, _ ->
            val conversationId = Conversation.Id("atomic")
            val event = ConversationRuntimeEvent.ExecutionCompleted(conversationId)
            coordinator.recordEvent(event)
            val before = coordinator.snapshot(conversationId)
            source.connection.use { c -> c.createStatement().use { it.execute("ALTER TABLE conversation_runtime_events ADD CHECK (sequence < 2)") } }
            assertFailsWith<java.sql.SQLException> { coordinator.recordEvent(event) }
            assertEquals(before, coordinator.snapshot(conversationId))
            assertEquals(listOf(1L), coordinator.listEventLogEntries(conversationId, null, 100).map { it.sequence })
            source.connection.use { c -> c.createStatement().use { it.execute("ALTER TABLE conversation_runtime_trace ADD CHECK (sequence < 2)") } }
            val task = agentInvocationTask(conversationId, "rolled-back", Instant.fromEpochSeconds(1))
            assertFailsWith<java.sql.SQLException> { coordinator.submit(task) }
            assertEquals(before, coordinator.snapshot(conversationId))
            assertTrue(coordinator.listReadyWorkItems(10).isEmpty())
        }
    }

    @Test
    fun `concurrent journal appends allocate unique cursors and retain bounded ordered replay`() = runBlocking {
        withInventoryDatabase { source, coordinator, _ ->
            val conversationId = Conversation.Id("concurrent")
            val event = ConversationRuntimeEvent.ExecutionCompleted(conversationId)
            val entries = kotlinx.coroutines.coroutineScope {
                (1..24).map { async { coordinator.recordEvent(event) } }.awaitAll()
            }
            assertEquals((1L..24L).toList(), entries.map { it.sequence }.sorted())
            assertEquals(24L, coordinator.lastEventSequence(conversationId))
            source.connection.use { c -> c.createStatement().use { s ->
                s.execute("INSERT INTO conversation_runtime_events SELECT conversation_id, n, created_at, event_type, task_id, turn_id, message_id, jsonb_set(entry_json, '{sequence}', to_jsonb(n)) FROM conversation_runtime_events CROSS JOIN generate_series(25,10000) n WHERE sequence=1")
                s.execute("INSERT INTO conversation_runtime_trace SELECT conversation_id, n, created_at, jsonb_set(entry_json, '{sequence}', to_jsonb(n)) FROM conversation_runtime_trace CROSS JOIN generate_series(25,2000) n WHERE sequence=1")
                s.execute("UPDATE conversation_runtime_records SET event_sequence=10000, trace_sequence=2000")
            } }
            assertEquals(10001L, coordinator.recordEvent(event).sequence)
            assertEquals((2L..10001L).toList(), coordinator.listEventLogEntries(conversationId, 0, 20000).map { it.sequence })
            assertEquals(listOf(10000L, 10001L), coordinator.listEventLogEntries(conversationId, null, 2).map { it.sequence })
            assertEquals((1802L..2001L).toList(), coordinator.snapshot(conversationId).trace.map { it.sequence })
            source.connection.use { c -> c.createStatement().use { s ->
                s.executeQuery("SELECT count(*), min(sequence) FROM conversation_runtime_trace").use { r ->
                    assertTrue(r.next()); assertEquals(2000, r.getInt(1)); assertEquals(2L, r.getLong(2))
                }
                s.execute("UPDATE conversation_runtime_records SET scheduling='\"invalid\"', command_tasks='\"invalid\"'")
            } }
            assertEquals(10002L, coordinator.recordEvent(event).sequence)
        }
    }

    @Test
    fun `targeted history and turn lookups skip unrelated event payloads`() = runBlocking {
        withInventoryDatabase { source, coordinator, _ ->
            val conversationId = Conversation.Id("lookup")
            val task = agentInvocationTask(conversationId, "message", Instant.fromEpochSeconds(1))
            val message = task.requireAgentInvocation().userMessage
            coordinator.recordEvent(ConversationRuntimeEvent.MessageEmitted(conversationId, task.id, message, turnId = task.turnId))
            coordinator.recordEvent(ConversationRuntimeEvent.MessageEmitted(conversationId, task.id, message, turnId = task.turnId))
            val history = ConversationRuntimeEvent.HistoryChanged(conversationId, task.id, ConversationHistoryMutationKind.COMPACT)
            coordinator.recordEvent(history)
            coordinator.recordEvent(ConversationRuntimeEvent.ExecutionCompleted(conversationId))
            source.connection.use { c -> c.createStatement().use { it.execute("UPDATE conversation_runtime_events SET entry_json='\"invalid\"' WHERE task_id IS NULL OR message_id IS NOT NULL") } }
            assertEquals(setOf(message.id), coordinator.findEmittedMessageIds(conversationId, task.turnId, 0))
            assertTrue(coordinator.findEmittedMessageIds(conversationId, task.turnId, 2).isEmpty())
            assertEquals(history, coordinator.findHistoryChanged(conversationId, task.id))
            assertNull(coordinator.findHistoryChanged(conversationId, ConversationRuntimeTask.Id("absent")))
        }
    }

    @Test
    fun `storage migration preserves all components journal payloads and cursor gaps`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "runtime_migration_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = dataSource()
        admin.connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            val source = dataSource(schema).also(::createLegacyRuntimeSchema)
            val json = Json { encodeDefaults = true }
            val id = Conversation.Id("migrated")
            val task = agentInvocationTask(id, "pending", Instant.fromEpochSeconds(1))
            val scheduling = ConversationRuntimeSchedulingState(id, pendingTasks = listOf(task), completedIdempotencyKeys = setOf("completed-key"))
            val command = inventoryTask("bytes", id.value, ConversationRuntimeWorkerId("worker-1")).copy(
                terminalOutputStartByte = 0, outputBytes = 3, terminalOutputContent = BinaryContent.fromBytes(byteArrayOf(0, -1, 65)))
            val event = ConversationRuntimeEventLogEntry(42, id, ConversationRuntimeEvent.MessageEmitted(id, task.id, task.requireAgentInvocation().userMessage, turnId = task.turnId), Instant.fromEpochSeconds(1))
            val trace = ConversationRuntimeTraceEntry(sequence = 55, conversationId = id, taskId = task.id, executor = null,
                kind = ConversationRuntimeTraceEntry.Kind.TASK_SUBMITTED, status = ConversationRuntimeTraceEntry.Status.STARTED,
                message = "trace", createdAt = Instant.fromEpochSeconds(1))
            val original = buildJsonObject {
                put("conversationId", id.value); put("revision", 60); put("eventSequence", 80); put("traceSequence", 90)
                put("scheduling", json.encodeToJsonElement(ConversationRuntimeSchedulingState.serializer(), scheduling))
                put("commandTasks", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(CommandTask.serializer()), listOf(command)))
                put("commandMonitors", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(CommandMonitor.serializer()), listOf(inventoryMonitor(command))))
                put("toolExecutions", JsonArray(emptyList())); put("memoryOperations", JsonArray(emptyList())); put("commandMonitorEvents", JsonArray(emptyList()))
                put("eventLog", JsonArray(listOf(json.encodeToJsonElement(ConversationRuntimeEventLogEntry.serializer(), event))))
                put("trace", JsonArray(listOf(json.encodeToJsonElement(ConversationRuntimeTraceEntry.serializer(), trace))))
            }
            source.connection.use { c ->
                c.prepareStatement("INSERT INTO conversation_runtime_records(conversation_id,record_json,ready_task_id,ready_at) VALUES (?,?::jsonb,?,?)").use { s ->
                    s.setString(1, id.value); s.setString(2, original.toString()); s.setString(3, task.id.value)
                    s.setTimestamp(4, java.sql.Timestamp.from(java.time.Instant.ofEpochSecond(1))); s.executeUpdate()
                }
                c.autoCommit = false
                c.createStatement().use { s ->
                    s.execute("UPDATE conversation_runtime_records SET record_json = jsonb_set(record_json, '{eventSequence}', '0')")
                    assertFailsWith<java.sql.SQLException> {
                        executeSqlResource(s, "db/migration/postgres/V64__split_conversation_runtime_storage.sql")
                    }
                }
                c.rollback()
                c.autoCommit = true
                c.createStatement().use { s ->
                    s.execute("CREATE TEMP TABLE original AS SELECT record_json FROM conversation_runtime_records")
                    executeSqlResource(s, "db/migration/postgres/V64__split_conversation_runtime_storage.sql")
                    s.executeQuery("""SELECT scheduling = record_json->'scheduling' AND command_tasks = record_json->'commandTasks'
                        AND command_monitors = record_json->'commandMonitors' AND tool_executions = record_json->'toolExecutions'
                        AND memory_operations = record_json->'memoryOperations' AND command_monitor_events = record_json->'commandMonitorEvents'
                        AND (SELECT jsonb_agg(entry_json ORDER BY sequence) FROM conversation_runtime_events) = record_json->'eventLog'
                        AND (SELECT jsonb_agg(entry_json ORDER BY sequence) FROM conversation_runtime_trace) = record_json->'trace'
                        FROM conversation_runtime_records, original""").use { r -> assertTrue(r.next()); assertTrue(r.getBoolean(1)) }
                }
            }
            val coordinator = PostgresConversationRuntimeCoordinator(source, json)
            val snapshot = coordinator.snapshot(id)
            assertEquals(60L, snapshot.revision); assertEquals(80L, snapshot.lastEventSequence)
            assertEquals(listOf(task), snapshot.pendingTasks); assertEquals(listOf(trace), snapshot.trace)
            assertEquals(listOf(command), snapshot.commandTasks)
            assertEquals(listOf(event), coordinator.listEventLogEntries(id, 0, 100))
            assertEquals(setOf(task.requireAgentInvocation().userMessage.id), coordinator.findEmittedMessageIds(id, task.turnId, 0))
            assertEquals(task.id, coordinator.listReadyWorkItems(10).single().taskId)
            assertEquals(81L, coordinator.recordEvent(ConversationRuntimeEvent.ExecutionCompleted(id)).sequence)
            assertEquals(91L, coordinator.snapshot(id).trace.last().sequence)
        } finally {
            admin.connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }

    private fun runtimeRowFingerprint(source: DataSource): String = source.connection.use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT xmin::text || ':' || row_to_json(r)::text FROM conversation_runtime_records r").use { r ->
            check(r.next()); r.getString(1)
        } }
    }

    private suspend fun withInventoryDatabase(
        block: suspend (DataSource, PostgresConversationRuntimeCoordinator, Json) -> Unit,
    ) {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return
        val schema = "runtime_inventory_test_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = dataSource()
        admin.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        try {
            val source = dataSource(schema).also(::createRuntimeSchema)
            val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
            block(source, PostgresConversationRuntimeCoordinator(source, json), json)
        } finally {
            admin.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun inventoryTask(
        id: String,
        conversation: String,
        worker: ConversationRuntimeWorkerId,
        terminal: Boolean = false,
    ): CommandTask {
        val now = Instant.fromEpochMilliseconds(1_000)
        return CommandTask(
            id = CommandTask.Id(id), conversationId = Conversation.Id(conversation), workerId = worker,
            workspaceMountId = WorkspaceMount.Id("inventory-mount"), command = "synthetic-command-do-not-log",
            workingDirectory = "/tmp", status = if (terminal) CommandTask.Status.COMPLETED else CommandTask.Status.WORKING,
            processId = 100, processStartedAt = now, outputFile = "/tmp/inventory-$id.log", outputBytes = 0,
            exitCode = if (terminal) 0 else null, completedAt = now.takeIf { terminal }, createdAt = now, updatedAt = now,
        )
    }

    private fun inventoryMonitor(task: CommandTask): CommandMonitor = CommandMonitor(
        id = CommandMonitor.Id("monitor-${task.id.value}"), conversationId = task.conversationId,
        commandTaskId = task.id, workerId = task.workerId, workspaceMountId = task.workspaceMountId,
        filterCommand = "synthetic-filter-do-not-log", mode = CommandMonitor.Mode.CONTINUOUS, startFrom = CommandMonitor.StartFrom.NOW,
        status = if (task.isTerminal) CommandMonitor.Status.COMPLETED else CommandMonitor.Status.WORKING,
        sourceOutputCursor = 0, processId = 101, processStartedAt = task.createdAt,
        outputFile = "/tmp/monitor-${task.id.value}.log", errorFile = "/tmp/monitor-${task.id.value}.err",
        outputBytes = 0, eventOutputCursor = 0, createdAt = task.createdAt, updatedAt = task.updatedAt,
        completedAt = task.completedAt, exitCode = task.exitCode,
    )

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
        createLegacyRuntimeSchema(dataSource)
        dataSource.connection.use { c -> c.createStatement().use { executeSqlResource(it, "db/migration/postgres/V64__split_conversation_runtime_storage.sql") } }
    }

    private fun createLegacyRuntimeSchema(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                listOf(
                    "db/migration/postgres/V4__conversation_runtime_records.sql",
                    "db/migration/postgres/V31__conversation_runtime_ready_work.sql",
                    "db/migration/postgres/V62__worker_command_inventory_indexes.sql",
                ).forEach { resource -> executeSqlResource(statement, resource) }
            }
        }
    }

    private fun executeSqlResource(
        statement: java.sql.Statement,
        resource: String,
    ) {
        statement.execute(checkNotNull(javaClass.classLoader.getResource(resource)).readText())
    }

    private companion object {
        val AGENT_DEFINITION_ID = AgentDefinition.Id("agent-1")
    }
}
