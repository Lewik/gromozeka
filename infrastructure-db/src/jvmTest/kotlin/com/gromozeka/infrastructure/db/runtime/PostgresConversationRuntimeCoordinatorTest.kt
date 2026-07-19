package com.gromozeka.infrastructure.db.runtime

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
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
                    worker = firstWorker,
                    startedAt = startedAt,
                )
            )
            assertEquals(startedAt, coordinator.listActiveTaskAssignments().single().startedAt)

            val incident = coordinator.markActiveTaskInDoubt(
                conversationId = conversationId,
                taskId = claimedTask.id,
                worker = firstWorker,
                message = "Worker heartbeat was lost",
                errorType = "WorkerUnavailable",
            )

            assertEquals(ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN, incident?.kind)
            assertEquals(startedAt, incident?.executionStartedAt)
            assertFalse(coordinator.completeActiveTask(conversationId, claimedTask.id, firstWorker))
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
    fun `work outbox claim skips a conversation locked by another transaction`() = runBlocking {
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

            runtimeDataSource.connection.use { lockConnection ->
                lockConversation(lockConnection, lockedTask.conversationId)
                val claimed = withTimeout(3_000) {
                    coordinator.claimUnpublishedWorkItems(
                        leaseOwnerId = "publisher-1",
                        now = Instant.fromEpochMilliseconds(3_000),
                        leaseUntil = Instant.fromEpochMilliseconds(13_000),
                        limit = 1,
                    )
                }
                assertEquals(listOf(availableTask.id), claimed.map { it.item.taskId })
                lockConnection.rollback()
            }
        } finally {
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }

    private fun lockConversation(
        connection: Connection,
        conversationId: Conversation.Id,
    ) {
        connection.autoCommit = false
        connection.prepareStatement(
            "SELECT conversation_id FROM conversation_runtime_records WHERE conversation_id = ? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, conversationId.value)
            statement.executeQuery().use { result ->
                assertTrue(result.next())
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
            payload = ConversationRuntimeTask.Payload.UserTurn(message, agent),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "test:$messageId",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
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
            worker = worker,
            workerCapabilities = task.requirements.capabilities,
            workerWorkspaceIds = emptySet(),
        )

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
        val migration = checkNotNull(
            javaClass.classLoader.getResource("db/migration/postgres/V4__conversation_runtime_records.sql")
        ).readText()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                migration
                    .split(';')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(statement::execute)
            }
        }
    }

    private companion object {
        val agent = AgentDefinition(
            id = AgentDefinition.Id("agent-1"),
            name = "Test Agent",
            prompts = listOf(Prompt.Id("prompt-1")),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model-1")),
            type = AgentDefinition.Type.Inline,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )
    }
}
