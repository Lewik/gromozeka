package com.gromozeka.infrastructure.db.runtime

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
    fun `binary migration preserves existing command output and permits zero bytes in jsonb`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "binary_migration_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = dataSource()
        admin.connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        try {
            val source = dataSource(schema).also(::createRuntimeSchema)
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
                        "INSERT INTO conversation_runtime_records(conversation_id, record_json, updated_at) " +
                            "VALUES ('empty-conversation', '{\"conversationId\":\"empty-conversation\"}'::jsonb, CURRENT_TIMESTAMP)"
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
                        "UPDATE conversation_runtime_records SET record_json = " +
                            "jsonb_set(record_json, '{trace}', '\"not part of command inventory\"'::jsonb)"
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
                    INSERT INTO conversation_runtime_records(conversation_id, record_json, updated_at)
                    SELECT 'bulk-' || n,
                        jsonb_build_object(
                            'conversationId', 'bulk-' || n,
                            'commandTasks', jsonb_build_array(CAST(? AS jsonb) || jsonb_build_object(
                                'id', 'bulk-task-' || n, 'conversationId', 'bulk-' || n
                            )),
                            'commandMonitors', jsonb_build_array(CAST(? AS jsonb) || jsonb_build_object(
                                'id', 'bulk-monitor-' || n, 'conversationId', 'bulk-' || n,
                                'commandTaskId', 'bulk-task-' || n
                            ))
                        ), CURRENT_TIMESTAMP
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
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                listOf(
                    "db/migration/postgres/V4__conversation_runtime_records.sql",
                    "db/migration/postgres/V31__conversation_runtime_ready_work.sql",
                    "db/migration/postgres/V61__worker_command_inventory_indexes.sql",
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
