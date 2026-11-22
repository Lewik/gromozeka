package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.syntheticKind
import com.gromozeka.domain.model.backgroundActivitySource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.flywaydb.core.Flyway
import org.junit.Assume.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

class AsyncCleanBackupUpgradeTest {
    @Serializable
    private data class ArchiveArtifact(val bytes: Long, val sha256: String)
    private data class MessageSnapshot(val count: Long, val digest: String, val artifacts: Set<String>)

    @Test fun `restored pre async backup upgrades compact records without changing history semantics or losing managed artifacts`() {
        assumeTrue("Requires an independently restored private backup", System.getenv("GROMOZEKA_ASYNC_BACKUP_TEST") == "true")
        val schema = requireNotNull(System.getenv("GROMOZEKA_ASYNC_BACKUP_SCHEMA"))
        require(schema.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        val source = source(requireNotNull(System.getenv("GROMOZEKA_ASYNC_BACKUP_URL")), schema)
        val archive = Json.decodeFromString<Map<String, ArchiveArtifact>>(
            Files.readString(Path.of(requireNotNull(System.getenv("GROMOZEKA_ASYNC_BACKUP_ARTIFACT_INDEX"))))
        )
        val flyway = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration/postgres").load()
        assertEquals("88", assertNotNull(flyway.info().current()).version.toString())
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name='async_tool_operations'").use { rows ->
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1))
                }
            }
        }
        val before = messages(source, validateTypedMetadata = false)
        val migration = flyway.migrate()
        assertEquals(listOf("89", "90", "91", "92", "93", "94"), migration.migrations.map { it.version })
        flyway.validate()
        assertEquals("94", assertNotNull(flyway.info().current()).version.toString())
        val after = messages(source, validateTypedMetadata = true)
        assertEquals(before, after, "Upgrade may normalize typed metadata and compact terminology, but must preserve history semantics and opaque replay payloads")
        assertTrue(after.count > 0, "The restored backup must contain actual history")
        var verified = 0
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id,size_bytes,content_source,state FROM artifacts").use { rows ->
                    val found = mutableSetOf<String>()
                    while (rows.next()) {
                        val id = rows.getString("id")
                        if (id !in after.artifacts) continue
                        found += id
                        assertEquals("COMMITTED", rows.getString("state"), "A persisted history artifact must be committed")
                        val content = Json.parseToJsonElement(rows.getString("content_source")).jsonObject
                        if (content["type"] == JsonPrimitive("managed")) {
                            val saved = assertNotNull(archive[id], "Managed history artifact missing from home archive: $id")
                            assertEquals(rows.getLong("size_bytes"), saved.bytes)
                            assertEquals(content.getValue("sha256").jsonPrimitive.content, saved.sha256)
                            verified++
                        }
                    }
                    assertEquals(after.artifacts, found, "Every saved history reference must retain its artifact metadata")
                }
                statement.executeQuery("SELECT count(*) FROM async_tool_operations").use { rows ->
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1))
                }
            }
        }
        println("ASYNC_BACKUP_UPGRADE_VERIFIED messages=${after.count} referencedManagedArtifacts=$verified flyway=88->94")
    }

    @Test fun `empty database receives the same final schema without the old async upgrade step`() {
        assumeTrue("Requires isolated PostgreSQL test configuration", System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        val schema = "async_clean_empty_${UUID.randomUUID().toString().replace("-", "") }"
        val source = source(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")), schema)
        try {
            val flyway = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/postgres").load()
            flyway.migrate(); flyway.validate()
            assertEquals("94", assertNotNull(flyway.info().current()).version.toString())
            source.connection.use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=current_schema() AND t.relname='async_tool_operations' AND conname IN ('async_tool_claim_generation_nonnegative','async_tool_claim_generation_owner')").use { rows ->
                    assertTrue(rows.next()); assertEquals(2, rows.getInt(1))
                }
            } }
        } finally {
            source.connection.use { connection -> connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }

    private fun source(url: String, schema: String) = PGSimpleDataSource().apply {
        setURL(url)
        user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
        password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        currentSchema = "$schema,public"
    }

    private fun messages(source: PGSimpleDataSource, validateTypedMetadata: Boolean): MessageSnapshot {
        val digest = MessageDigest.getInstance("SHA-256")
        val artifacts = mutableSetOf<String>()
        var count = 0L
        source.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("SELECT id,message_json FROM messages ORDER BY id").use { statement ->
                statement.fetchSize = 64
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val message = try {
                            Json.decodeFromString(Conversation.Message.serializer(), currentCompactionTerminology(rows.getString("message_json")))
                        } catch (failure: Exception) {
                            fail("Backup message ${rows.getString("id")} cannot be decoded (${failure::class.simpleName}); private payload is not logged")
                        }
                        if (validateTypedMetadata && message.syntheticKind != null) message.backgroundActivitySource
                        if (validateTypedMetadata) message.content.filterIsInstance<ContentItem.ContextCompactionResult>()
                            .filter { it.coversAllPrevious }.forEach { checkpoint ->
                                assertTrue(checkpoint.sourceMessageIds.isEmpty())
                                assertNotNull(checkpoint.sourceBoundaryMessageId)
                            }
                        val normalized = normalizeFullCompactions(message)
                        val metadata = JsonObject(normalized.providerMetadata.filterKeys { it !in setOf("syntheticKind", "backgroundActivitySource") })
                        val canonical = Json.encodeToString(Conversation.Message.serializer(), normalized.copy(providerMetadata = metadata))
                        digest.update(canonical.encodeToByteArray()); digest.update(0.toByte())
                        message.content.forEach { item -> when (item) {
                            is ContentItem.ArtifactItem -> artifacts += item.artifact.id.value
                            is ContentItem.ToolResult -> item.result.filterIsInstance<ContentItem.ToolResult.Data.ArtifactData>()
                                .forEach { artifacts += it.artifact.id.value }
                            else -> Unit
                        } }
                        count++
                    }
                }
            }
            connection.rollback()
        }
        return MessageSnapshot(count, digest.digest().joinToString("") { "%02x".format(it) }, artifacts)
    }

    private fun currentCompactionTerminology(value: String): String = value
        .replace("\"strategy\":\"SUMMARIZE\"", "\"strategy\":\"COMPACT\"")
        .replace(
            "\"id\":\"gromozeka.message-compaction.summarize\"",
            "\"id\":\"gromozeka.message-compaction.compact\"",
        )

    private fun normalizeFullCompactions(message: Conversation.Message): Conversation.Message = message.copy(
        content = message.content.map { item ->
            if (item !is ContentItem.ContextCompactionResult || !item.coversAllPrevious) item
            else item.copy(sourceBoundaryMessageId = null, sourceMessageIds = emptyList())
        },
    )
}
