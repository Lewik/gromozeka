package db.migration.postgres

import com.gromozeka.domain.model.*
import kotlinx.serialization.json.*
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant

class SyntheticMessageMetadataMigrationTest {
    private val migration = V89__typed_synthetic_message_metadata()

    @Test
    fun `all persisted kinds become standard enum names without changing message content or opaque provider data`() {
        for (old in listOf("command_task_completion", "command_monitor_update", "tool_routing_error", "runtime_execution_incident",
            "slot_event", "memory", "memory_focused_context", "compaction_continuation")) {
            val original = oldMessage(old, old, withNul = true)
            val migrated = migration.migrateDocument(original).jsonObject
            assertEquals(old.uppercase(), migrated.getValue("providerMetadata").jsonObject.getValue("syntheticKind").jsonPrimitive.content)
            assertEquals(original - "providerMetadata", migrated - "providerMetadata")
            assertEquals(original.getValue("providerMetadata").jsonObject - "syntheticKind", migrated.getValue("providerMetadata").jsonObject - "syntheticKind")
            assertEquals(SyntheticMessageKind.valueOf(old.uppercase()), Json.decodeFromJsonElement<Conversation.Message>(migrated).syntheticKind)
            assertEquals(migrated, migration.migrateDocument(migrated))
        }
    }

    @Test
    fun `only canonical nested messages are converted and provenance comes from the trusted origin`() {
        val original = oldMessage("completion", "command_task_completion")
        val source = source("launch-call", "agent")
        val input = buildJsonObject {
            put("messages", JsonArray(listOf(original)))
            put("pendingTasks", buildJsonArray { add(buildJsonObject { put("payload", buildJsonObject { put("userMessage", original) }) }) })
            put("input", original)
            put("result", original)
        }
        val migrated = migration.migrateDocument(input) { kind, id, conversation ->
            assertEquals("COMMAND", kind); assertEquals("task", id); assertEquals("conversation", conversation); source
        }.jsonObject
        val message = Json.decodeFromJsonElement<Conversation.Message>(migrated.getValue("messages").jsonArray.single())
        assertEquals(BackgroundActivityMessageSource(Conversation.Message.ContentItem.ToolCall.Id("launch-call"), AgentDefinition.Id("agent")), message.backgroundActivitySource)
        assertNull(message.author)
        assertEquals(original, migrated["input"])
        assertEquals(original, migrated["result"])
        assertEquals("COMMAND_TASK_COMPLETION", migrated.getValue("pendingTasks").jsonArray.single().jsonObject.getValue("payload").jsonObject
            .getValue("userMessage").jsonObject.getValue("providerMetadata").jsonObject.getValue("syntheticKind").jsonPrimitive.content)
    }

    @Test
    fun `unknown kinds and malformed values fail while absent provenance is not fabricated`() {
        for (value in listOf(JsonPrimitive("made_up_kind"), JsonPrimitive(1), JsonNull)) {
            val message = oldMessage("invalid", "memory")
            val invalid = JsonObject(message + ("providerMetadata" to JsonObject(message.getValue("providerMetadata").jsonObject + ("syntheticKind" to value))))
            assertFailsWith<IllegalStateException> { migration.migrateDocument(invalid) }
        }
        val migrated = Json.decodeFromJsonElement<Conversation.Message>(migration.migrateDocument(oldMessage("unproven", "command_task_completion")))
        assertEquals(SyntheticMessageKind.COMMAND_TASK_COMPLETION, migrated.syntheticKind)
        assertNull(migrated.backgroundActivitySource)
    }

    @Test
    fun `postgres upgrade migrates history queued messages events and drafts and backfills their launch source`() {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return
        withSchema { database ->
            flyway(database).target("88").load().migrate()
            val command = oldMessage("command", "command_task_completion")
            val monitor = oldMessage("monitor", "command_monitor_update")
            val nul = oldMessage("nul", "memory", withNul = true)
            database.connection.use { connection ->
                createConversation(connection)
                insertMessage(connection, command); insertMessage(connection, monitor); insertMessage(connection, nul)
                for ((kind, id, call) in listOf(Triple("COMMAND", "task", "launch-call"), Triple("MONITOR", "monitor", "monitor-launch"))) {
                    val record = buildJsonObject {
                        put("kind", kind); put("activityId", id); put("conversationId", "conversation")
                        put("toolCallId", call); put("agentDefinitionId", "agent")
                    }
                    connection.prepareStatement("INSERT INTO background_activity_origins(kind,activity_id,conversation_id,actor_user_id,worker_request_id,tool_call_id,record_json) VALUES (?,?,'conversation','user',?,?,?::jsonb)").use {
                        it.setString(1, kind); it.setString(2, id); it.setString(3, "request-$id"); it.setString(4, call); it.setString(5, record.toString()); it.executeUpdate()
                    }
                }
                val queued = buildJsonObject { put("pendingTasks", buildJsonArray { add(buildJsonObject { put("payload", buildJsonObject { put("userMessage", command) }) }) }) }
                connection.prepareStatement("INSERT INTO conversation_runtime_records(conversation_id,scheduling) VALUES ('conversation',?::jsonb)").use {
                    it.setString(1, queued.toString()); it.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO conversation_runtime_events(conversation_id,sequence,created_at,event_type,entry_json) VALUES ('conversation',1,now(),'test',?::jsonb)").use {
                    it.setString(1, buildJsonObject { put("message", command) }.toString()); it.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO agent_response_drafts(id,conversation_id,record_json) VALUES ('draft','conversation',?::jsonb)").use {
                    it.setString(1, buildJsonObject { put("messages", JsonArray(listOf(monitor))) }.toString()); it.executeUpdate()
                }
            }
            assertEquals(1, flyway(database).load().migrate().migrationsExecuted)
            database.connection.use { connection ->
                val commandAfter = read(connection, "SELECT message_json FROM messages WHERE id='command'")
                val monitorAfter = read(connection, "SELECT message_json FROM messages WHERE id='monitor'")
                assertEquals(nul.getValue("content"), read(connection, "SELECT message_json FROM messages WHERE id='nul'").jsonObject.getValue("content"))
                val commandMessage = Json.decodeFromJsonElement<Conversation.Message>(commandAfter)
                val monitorMessage = Json.decodeFromJsonElement<Conversation.Message>(monitorAfter)
                assertEquals("launch-call", commandMessage.backgroundActivitySource?.toolCallId?.value)
                assertEquals("monitor-launch", monitorMessage.backgroundActivitySource?.toolCallId?.value)
                assertEquals(SyntheticMessageKind.COMMAND_MONITOR_UPDATE, monitorMessage.syntheticKind)
                assertNull(commandMessage.author); assertNull(monitorMessage.author)
                assertEquals(command.getValue("content"), commandAfter.jsonObject.getValue("content"))
                assertEquals(command.getValue("providerMetadata").jsonObject.getValue("openaiCompatibleMessage"), commandAfter.jsonObject.getValue("providerMetadata").jsonObject.getValue("openaiCompatibleMessage"))
                val queued = read(connection, "SELECT scheduling::text FROM conversation_runtime_records WHERE conversation_id='conversation'")
                    .jsonObject.getValue("pendingTasks").jsonArray.single().jsonObject.getValue("payload").jsonObject.getValue("userMessage")
                val event = read(connection, "SELECT entry_json::text FROM conversation_runtime_events WHERE conversation_id='conversation'").jsonObject.getValue("message")
                val draft = read(connection, "SELECT record_json::text FROM agent_response_drafts WHERE id='draft'").jsonObject.getValue("messages").jsonArray.single()
                assertEquals(commandAfter, queued); assertEquals(commandAfter, event); assertEquals(monitorAfter, draft)
            }
            assertEquals(0, flyway(database).load().migrate().migrationsExecuted)
        }
    }

    @Test
    fun `postgres migration failure rolls back earlier message changes`() {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return
        withSchema { database ->
            flyway(database).target("88").load().migrate()
            val known = oldMessage("known", "memory")
            val unknown = oldMessage("unknown", "not_a_real_kind")
            database.connection.use { connection ->
                createConversation(connection); insertMessage(connection, known); insertMessage(connection, unknown)
            }
            assertFailsWith<FlywayException> { flyway(database).load().migrate() }
            database.connection.use { connection ->
                assertEquals(known, read(connection, "SELECT message_json FROM messages WHERE id='known'"))
                assertEquals(unknown, read(connection, "SELECT message_json FROM messages WHERE id='unknown'"))
            }
        }
    }

    @Test
    fun `postgres clean install includes the new migration`() {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return
        withSchema { database ->
            flyway(database).load().migrate()
            assertEquals(0, flyway(database).load().migrate().migrationsExecuted)
        }
    }

    private fun source(callId: String, agentId: String) = buildJsonObject { put("toolCallId", callId); put("agentDefinitionId", agentId) }

    private fun oldMessage(id: String, kind: String, withNul: Boolean = false): JsonObject {
        val message = Conversation.Message(Conversation.Message.Id(id), Conversation.Id("conversation"), role = Conversation.Message.Role.ASSISTANT,
            content = listOf(Conversation.Message.ContentItem.UserMessage(if (withNul) "Original text\u0000\nDo not rewrite input" else "Original text\nDo not rewrite input")),
            providerMetadata = buildJsonObject {
                put("synthetic", true); put("syntheticKind", kind); put("commandTaskId", "task"); put("commandMonitorId", "monitor")
                put("openaiCompatibleMessage", buildJsonObject { put("syntheticKind", "memory"); put("signature", "opaque-provider-state") })
            }, createdAt = Instant.fromEpochMilliseconds(0))
        return Json.encodeToJsonElement(message).jsonObject
    }

    private fun flyway(database: PGSimpleDataSource) = Flyway.configure().dataSource(database)
        .schemas(requireNotNull(database.currentSchema).substringBefore(',')).defaultSchema(requireNotNull(database.currentSchema).substringBefore(','))
        .locations("classpath:db/migration/postgres")

    private fun withSchema(block: (PGSimpleDataSource) -> Unit) {
        val schema = "synthetic_${UUID.randomUUID().toString().replace("-", "")}"
        val database = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
            password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
            currentSchema = "$schema,public"
        }
        try { block(database) } finally {
            database.connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }

    private fun createConversation(connection: Connection) = connection.createStatement().use {
        it.execute("INSERT INTO projects(id,name,created_at,last_used_at) VALUES ('project','Test',now(),now())")
        it.execute("INSERT INTO conversations(id,project_id,display_name,current_thread_id,created_at,updated_at) VALUES ('conversation','project','Test','thread',now(),now())")
        it.execute("INSERT INTO threads(id,conversation_id,created_at,updated_at) VALUES ('thread','conversation',now(),now())")
    }

    private fun insertMessage(connection: Connection, message: JsonObject) = connection.prepareStatement(
        "INSERT INTO messages(id,conversation_id,role,created_at,message_json) VALUES (?,'conversation','ASSISTANT',now(),?)",
    ).use { it.setString(1, message.getValue("id").jsonPrimitive.content); it.setString(2, message.toString()); it.executeUpdate() }

    private fun read(connection: Connection, sql: String): JsonElement = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> check(rows.next()); Json.parseToJsonElement(rows.getString(1)) }
    }
}
