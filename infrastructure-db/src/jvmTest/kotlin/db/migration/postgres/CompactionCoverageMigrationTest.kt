package db.migration.postgres

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.model.compactions
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult as Compaction
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventLogEntry
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.*
import kotlin.time.Instant

class CompactionCoverageMigrationTest {
    private val migration = V63__explicit_compaction_coverage()

    @Test
    fun `old records are normalized once without changing explicit coverage`() {
        for (origin in Compaction.Origin.entries) {
            for (nullCoverage in listOf(false, true)) {
                val old = oldMessage(origin.name, origin, nullCoverage)
                val migrated = migration.migrateDocument(old)
                val decoded = Json.decodeFromJsonElement<Conversation.Message>(migrated)
                assertEquals(if (origin == Compaction.Origin.USER_REQUESTED) Compaction.Coverage.SELECTED_MESSAGES
                    else Compaction.Coverage.ALL_PREVIOUS, decoded.compactions().single().coverage)
                assertEquals(migrated, migration.migrateDocument(migrated))
                val originalContent = old["content"]!!.jsonArray.single().jsonObject
                val migratedContent = migrated.jsonObject["content"]!!.jsonArray.single().jsonObject
                assertEquals(JsonObject(originalContent - "coverage"), JsonObject(migratedContent - "coverage"))
            }
        }
        val explicit = encodedMessage("explicit", Compaction.Origin.USER_REQUESTED, Compaction.Coverage.ALL_PREVIOUS)
        assertEquals(explicit, migration.migrateDocument(explicit))
    }

    @Test
    fun `native state tool data and provider metadata are not rewritten`() {
        val fakeContent = oldMessage("embedded", Compaction.Origin.PROVIDER_AUTO)
        val old = oldMessage("opaque", Compaction.Origin.PROVIDER_AUTO)
        val item = old["content"]!!.jsonArray.single().jsonObject
        val opaque = JsonObject(item + ("payload" to buildJsonObject {
            put("kind", "opaque_provider_state")
            put("state", fakeContent)
        }))
        val document = JsonObject(old + mapOf(
            "content" to JsonArray(listOf(opaque, buildJsonObject {
                put("type", "ToolCall")
                put("call", buildJsonObject { put("input", fakeContent) })
            })),
            "providerMetadata" to fakeContent,
        ))
        val migrated = migration.migrateDocument(document).jsonObject
        assertEquals(fakeContent, migrated["providerMetadata"])
        assertEquals(opaque["payload"], migrated["content"]!!.jsonArray[0].jsonObject["payload"])
        assertEquals(document["content"]!!.jsonArray[1], migrated["content"]!!.jsonArray[1])
        val mutation = buildJsonObject { put("mutationType", "edit"); put("newContent", JsonArray(listOf(item))) }
        assertEquals(JsonPrimitive("ALL_PREVIOUS"), migration.migrateDocument(mutation).jsonObject["newContent"]!!
            .jsonArray.single().jsonObject["coverage"])
    }

    @Test
    fun `unrecognized origin fails rather than guessing or removing protection`() {
        val old = oldMessage("bad", Compaction.Origin.PROVIDER_AUTO)
        val item = old["content"]!!.jsonArray.single().jsonObject
        val invalid = JsonObject(old + ("content" to JsonArray(listOf(JsonObject(item + ("origin" to JsonPrimitive("UNKNOWN")))))))
        assertFailsWith<IllegalStateException> { migration.migrateDocument(invalid) }
    }

    @Test
    fun `postgres upgrades messages and replay events while preserving history links`() {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return
        withSchema { source ->
            flyway(source).target("62").load().migrate()
            val oldPartial = oldMessage("partial", Compaction.Origin.USER_REQUESTED)
            val oldFull = oldMessage("full", Compaction.Origin.PROVIDER_AUTO, nullCoverage = true)
            val plain = Json.encodeToJsonElement(Conversation.Message.serializer(), plainMessage()).jsonObject
            val eventMessage = Json.decodeFromJsonElement<Conversation.Message>(migration.migrateDocument(oldFull))
            val entry = Json.encodeToJsonElement(ConversationRuntimeEventLogEntry.serializer(), ConversationRuntimeEventLogEntry(
                sequence = 7, conversationId = conversationId,
                event = ConversationRuntimeEvent.MessageEmitted(conversationId, null, eventMessage, cursorSequence = 7),
                createdAt = Instant.fromEpochMilliseconds(0),
            )).jsonObject
            val oldEntry = JsonObject(entry + ("event" to JsonObject(entry["event"]!!.jsonObject + ("message" to oldFull))))
            val record = buildJsonObject {
                put("conversationId", conversationId.value)
                put("eventSequence", 7)
                put("eventLog", JsonArray(listOf(oldEntry)))
            }
            source.connection.use { connection ->
                createConversation(connection)
                listOf(plain, oldPartial, oldFull).forEach { insertMessage(connection, it) }
                connection.createStatement().use { it.execute("""
                    INSERT INTO thread_messages(thread_id,message_id,position)
                    VALUES ('thread','source',0),('thread','partial',1),('thread','full',2)
                """.trimIndent()) }
                connection.prepareStatement("INSERT INTO conversation_runtime_records(conversation_id,record_json) VALUES (?,CAST(? AS jsonb))").use {
                    it.setString(1, conversationId.value); it.setString(2, record.toString()); it.executeUpdate()
                }
            }
            assertFailsWith<SerializationException> { Json.decodeFromJsonElement<Conversation.Message>(oldFull) }
            assertEquals(1, flyway(source).load().migrate().migrationsExecuted)
            source.connection.use { connection ->
                val messages = readMessages(connection)
                assertEquals(3, messages.size)
                val partial = messages.single { it.id.value == "partial" }
                val full = messages.single { it.id.value == "full" }
                assertEquals(Compaction.Coverage.SELECTED_MESSAGES, partial.compactions().single().coverage)
                assertEquals(Compaction.Coverage.ALL_PREVIOUS, full.compactions().single().coverage)
                val history = listOf(messages.single { it.id.value == "source" }, partial, full)
                assertEquals(listOf(full), ConversationContext(history).messages())
                assertEquals(history.map { it.id }.toSet(), ConversationContext(history).protectedMessageIds())
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT message_id FROM thread_messages ORDER BY position").use { rows ->
                        val links = buildList { while (rows.next()) add(rows.getString(1)) }
                        assertEquals(listOf("source", "partial", "full"), links)
                    }
                    statement.executeQuery("SELECT record_json FROM conversation_runtime_records").use { rows ->
                        assertTrue(rows.next())
                        val updated = Json.parseToJsonElement(rows.getString(1)).jsonObject
                        val restored = Json.decodeFromJsonElement<ConversationRuntimeEventLogEntry>(updated["eventLog"]!!.jsonArray.single())
                        assertEquals(7L, restored.sequence)
                        assertEquals(full, (restored.event as ConversationRuntimeEvent.MessageEmitted).message)
                    }
                }
            }
            assertEquals(0, flyway(source).load().migrate().migrationsExecuted)
        }
    }

    @Test
    fun `postgres clean install writes only the explicit current format`() {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return
        withSchema { source ->
            flyway(source).load().migrate()
            source.connection.use { connection ->
                createConversation(connection)
                insertMessage(connection, encodedMessage("new", Compaction.Origin.USER_REQUESTED, Compaction.Coverage.SELECTED_MESSAGES))
                val result = readMessages(connection).single().compactions().single()
                assertEquals(Compaction.Coverage.SELECTED_MESSAGES, result.coverage)
                assertFalse(result.coversAllPrevious)
            }
            assertEquals(0, flyway(source).load().migrate().migrationsExecuted)
        }
    }

    private fun encodedMessage(id: String, origin: Compaction.Origin, coverage: Compaction.Coverage): JsonObject =
        Json.encodeToJsonElement(Conversation.Message.serializer(), plainMessage().copy(
            id = Conversation.Message.Id(id), role = Conversation.Message.Role.ASSISTANT,
            content = listOf(Compaction(Compaction.Payload.ReadableSummary("Saved summary"), origin, coverage = coverage)),
        )).jsonObject

    private fun oldMessage(id: String, origin: Compaction.Origin, nullCoverage: Boolean = false): JsonObject {
        val encoded = encodedMessage(id, origin, Compaction.Coverage.SELECTED_MESSAGES)
        val content = encoded["content"]!!.jsonArray.single().jsonObject
        val old = JsonObject(content - "coverage" + if (nullCoverage) mapOf("coverage" to JsonNull) else emptyMap())
        return JsonObject(encoded + ("content" to JsonArray(listOf(old))))
    }

    private fun plainMessage() = Conversation.Message(
        id = Conversation.Message.Id("source"), conversationId = conversationId, role = Conversation.Message.Role.USER,
        content = listOf(Conversation.Message.ContentItem.UserMessage("Original instruction")), createdAt = Instant.fromEpochMilliseconds(0),
    )

    private fun flyway(source: PGSimpleDataSource) = Flyway.configure().dataSource(source)
        .schemas(requireNotNull(source.currentSchema).substringBefore(',')).defaultSchema(requireNotNull(source.currentSchema).substringBefore(','))
        .locations("classpath:db/migration/postgres")

    private fun withSchema(block: (PGSimpleDataSource) -> Unit) {
        val schema = "coverage_${UUID.randomUUID().toString().replace("-", "") }"
        val source = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
            currentSchema = "$schema,public"
        }
        try { block(source) } finally {
            source.connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }

    private fun createConversation(connection: Connection) = connection.createStatement().use {
        it.execute("INSERT INTO projects(id,name,created_at,last_used_at) VALUES ('project','Test',now(),now())")
        it.execute("INSERT INTO conversations(id,project_id,display_name,current_thread_id,created_at,updated_at) VALUES ('conversation','project','Test','thread',now(),now())")
        it.execute("INSERT INTO threads(id,conversation_id,created_at,updated_at) VALUES ('thread','conversation',now(),now())")
    }

    private fun insertMessage(connection: Connection, message: JsonObject) {
        connection.prepareStatement("INSERT INTO messages(id,conversation_id,role,created_at,message_json) VALUES (?,'conversation',?,now(),?)").use {
            it.setString(1, message["id"]!!.jsonPrimitive.content)
            it.setString(2, message["role"]!!.jsonPrimitive.content)
            it.setString(3, message.toString())
            it.executeUpdate()
        }
    }

    private fun readMessages(connection: Connection): List<Conversation.Message> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT message_json FROM messages ORDER BY id").use { rows ->
            buildList { while (rows.next()) add(Json.decodeFromString<Conversation.Message>(rows.getString(1))) }
        }
    }

    private val conversationId = Conversation.Id("conversation")
}
