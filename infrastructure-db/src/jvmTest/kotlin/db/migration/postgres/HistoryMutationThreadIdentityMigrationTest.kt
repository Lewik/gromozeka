package db.migration.postgres

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationHistoryMutation
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeSchedulingState
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class HistoryMutationThreadIdentityMigrationTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Test
    fun `migration removes only terminal history mutations without a thread identity`() {
        withSchema { source ->
            val legacyTask = legacy(historyMutationTask("legacy"))
            val currentIncident = incident(historyMutationTask("current"), "current incident")
            val legacyIncident = legacy(incident(historyMutationTask("legacy"), "legacy incident"), legacyTask)
            val scheduling = json.encodeToJsonElement(
                ConversationRuntimeSchedulingState(
                    conversationId = Conversation.Id("conversation"),
                    incidents = listOf(currentIncident),
                ),
            ).jsonObject
            val stored = JsonObject(
                scheduling + (
                    "incidents" to JsonArray(
                        listOf(legacyIncident, json.encodeToJsonElement(currentIncident)),
                    )
                ),
            )

            source.connection.use { connection ->
                createRuntimeTable(connection)
                connection.prepareStatement(
                    "INSERT INTO conversation_runtime_records(conversation_id, scheduling) VALUES (?, ?::jsonb)",
                ).use { insert ->
                    insert.setString(1, "conversation")
                    insert.setString(2, stored.toString())
                    insert.executeUpdate()
                }
                connection.createStatement().use { statement ->
                    statement.execute(migrationSql())
                    statement.executeQuery("SELECT scheduling::text FROM conversation_runtime_records").use { rows ->
                        assertTrue(rows.next())
                        val migrated = json.decodeFromString<ConversationRuntimeSchedulingState>(rows.getString(1))
                        assertEquals(listOf(currentIncident), migrated.incidents)
                    }
                }
            }
        }
    }

    @Test
    fun `migration rejects every executable history mutation without a thread identity before pruning incidents`() {
        withSchema { source ->
            val legacyTask = legacy(historyMutationTask("legacy"))
            val incidentTask = historyMutationTask("incident")
            val legacyIncident = legacy(incident(incidentTask, "legacy incident"), legacy(incidentTask))
            val base = JsonObject(
                mapOf(
                    "conversationId" to json.encodeToJsonElement(Conversation.Id("conversation")),
                    "incidents" to JsonArray(listOf(legacyIncident)),
                ),
            )
            val executableLocations = listOf(
                "activeTask" to legacyTask,
                "continuationTask" to legacyTask,
                "activeInsertions" to JsonArray(listOf(legacyTask)),
                "pendingTasks" to JsonArray(listOf(legacyTask)),
            )

            source.connection.use { connection ->
                createRuntimeTable(connection)
                executableLocations.forEach { (field, task) ->
                    connection.createStatement().use { it.execute("TRUNCATE conversation_runtime_records") }
                    val stored = JsonObject(base + (field to task))
                    connection.prepareStatement(
                        "INSERT INTO conversation_runtime_records(conversation_id, scheduling) VALUES (?, ?::jsonb)",
                    ).use { insert ->
                        insert.setString(1, "conversation")
                        insert.setString(2, stored.toString())
                        insert.executeUpdate()
                    }

                    val failure = assertFailsWith<SQLException>(field) {
                        connection.createStatement().use { it.execute(migrationSql()) }
                    }
                    assertContains(failure.message.orEmpty(), "Legacy executable history mutation")
                    connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT jsonb_array_length(scheduling -> 'incidents') FROM conversation_runtime_records",
                        ).use { rows ->
                            assertTrue(rows.next())
                            assertEquals(1, rows.getInt(1), "Migration changed incidents before rejecting $field")
                        }
                    }
                }
            }
        }
    }

    private fun historyMutationTask(id: String): ConversationRuntimeTask = ConversationRuntimeTask(
        id = ConversationRuntimeTask.Id(id),
        conversationId = Conversation.Id("conversation"),
        payload = ConversationRuntimeTask.Payload.HistoryMutation(
            mutation = ConversationHistoryMutation.Delete(listOf(Conversation.Message.Id("message"))),
            expectedThreadId = Conversation.Thread.Id("thread"),
        ),
        placement = QueuedMessagePlacement.END_OF_TURN,
        idempotencyKey = id,
        requirements = ConversationRuntimeTaskRequirements(
            capabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN),
            target = ConversationRuntimeTaskTarget.Server,
        ),
        createdAt = Instant.fromEpochMilliseconds(1),
    )

    private fun incident(task: ConversationRuntimeTask, message: String): ConversationRuntimeTaskIncident =
        ConversationRuntimeTaskIncident(
            task = task,
            kind = ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED,
            message = message,
            executor = null,
            executionStartedAt = null,
            occurredAt = Instant.fromEpochMilliseconds(2),
        )

    private fun legacy(task: ConversationRuntimeTask): JsonObject {
        val document = json.encodeToJsonElement(task).jsonObject
        return JsonObject(document + ("payload" to JsonObject(document.getValue("payload").jsonObject - "expectedThreadId")))
    }

    private fun legacy(incident: ConversationRuntimeTaskIncident, task: JsonObject): JsonObject {
        val document = json.encodeToJsonElement(incident).jsonObject
        return JsonObject(document + ("task" to task))
    }

    private fun createRuntimeTable(connection: java.sql.Connection) {
        connection.createStatement().use {
            it.execute(
                "CREATE TABLE conversation_runtime_records (" +
                    "conversation_id TEXT PRIMARY KEY, scheduling JSONB NOT NULL)",
            )
        }
    }

    private fun migrationSql(): String = requireNotNull(
        javaClass.getResource("/db/migration/postgres/V100__history_mutation_thread_identity.sql"),
    ).readText()

    private fun withSchema(block: (PGSimpleDataSource) -> Unit) {
        assumeTrue("Requires isolated PostgreSQL", System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        val source = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
            password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        }
        val schema = "history_mutation_thread_${UUID.randomUUID().toString().replace("-", "") }"
        source.connection.use { admin ->
            admin.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                source.currentSchema = schema
                block(source)
            } finally {
                admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
