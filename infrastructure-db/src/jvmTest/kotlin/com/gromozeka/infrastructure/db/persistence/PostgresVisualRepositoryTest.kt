package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.visual.Visual
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.postgresql.ds.PGSimpleDataSource
import java.sql.SQLException
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant

class PostgresVisualRepositoryTest {
    @Test
    fun `migration fills missing preferences and persisted Visual never supplies a default`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "visual_context_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = source()
        admin.connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            val database = source(schema)
            database.connection.use { c -> c.createStatement().use { s ->
                s.execute("CREATE TABLE conversations (id VARCHAR(255) PRIMARY KEY)")
                s.execute("INSERT INTO conversations VALUES ('conversation')")
                s.execute(checkNotNull(javaClass.classLoader.getResource("db/migration/postgres/V66__conversation_visuals.sql")).readText())
            } }
            val now = Instant.parse("2026-11-16T00:00:00Z")
            val visual = Visual("visual", Conversation.Id("conversation"), User.Id("user"),
                document = """<html><head><title>Panel</title><state-schema><![CDATA[{"type":"object","properties":{"form":{"type":"object"},"data":{"type":"object"}},"required":["form","data"]}]]></state-schema></head><body><p>{data.value}</p></body></html>""", title = "Panel",
                state = Json.parseToJsonElement("""{"form":{},"data":{"value":1}}""").jsonObject,
                createdAt = now, updatedAt = now, includeStateInContext = false)
            val repository = PostgresVisualRepository(database)
            repository.save(visual)
            val old = visual.copy(id = "old", includeStateInContext = true)
            val oldRecord = JsonObject(Json.encodeToJsonElement(old).jsonObject - "includeStateInContext")
            database.connection.use { c -> c.prepareStatement("""
                INSERT INTO conversation_visuals(id,conversation_id,revision,has_handler,status,created_at,record_json)
                VALUES ('old','conversation',1,false,'ACTIVE',?::timestamptz,?::jsonb)
            """.trimIndent()).use { s ->
                s.setString(1, now.toString()); s.setString(2, oldRecord.toString()); s.executeUpdate()
            } }
            assertFailsWith<SerializationException> { repository.find(old.conversationId, old.id) }
            database.connection.use { c -> c.createStatement().use { s ->
                s.execute(checkNotNull(javaClass.classLoader.getResource("db/migration/postgres/V88__visual_context_preference.sql")).readText())
            } }
            val restored = PostgresVisualRepository(database)
            assertEquals(old, restored.find(old.conversationId, old.id))
            assertEquals(visual, restored.find(visual.conversationId, visual.id))
            assertEquals(mapOf("old" to true, "visual" to false), restored.list(visual.conversationId).associate { it.id to it.includeStateInContext })
            val enabled = visual.copy(includeStateInContext = true, revision = 2)
            restored.save(enabled)
            assertEquals(enabled, PostgresVisualRepository(database).find(visual.conversationId, visual.id))
            for (invalid in listOf(
                "record_json - 'includeStateInContext'",
                "jsonb_set(record_json, '{includeStateInContext}', 'null'::jsonb)",
                "jsonb_set(record_json, '{includeStateInContext}', '\"false\"'::jsonb)",
            )) {
                database.connection.use { c -> c.createStatement().use { s ->
                    assertFailsWith<SQLException> { s.executeUpdate("UPDATE conversation_visuals SET record_json = $invalid WHERE id = 'visual'") }
                } }
                assertEquals(enabled, restored.find(visual.conversationId, visual.id))
            }
        } finally {
            admin.connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }

    private fun source(schema: String? = null) = PGSimpleDataSource().apply {
        setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
        user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
        password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        currentSchema = schema
    }
}
