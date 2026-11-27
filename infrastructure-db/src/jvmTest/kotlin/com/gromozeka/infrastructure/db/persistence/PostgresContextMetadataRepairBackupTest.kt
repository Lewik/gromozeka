package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageContextMetadata
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.junit.Assume.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresContextMetadataRepairBackupTest {
    @Test
    fun `restored production backup repairs stale metadata without changing message count`() {
        assumeTrue(
            "Requires an independently restored private production backup",
            System.getenv("GROMOZEKA_CONTEXT_METADATA_BACKUP_TEST") == "true",
        )
        val source = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_CONTEXT_METADATA_BACKUP_URL")))
            user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
            password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
            currentSchema = "public"
        }
        val flyway = Flyway.configure().dataSource(source).schemas("public").defaultSchema("public")
            .locations("classpath:db/migration/postgres").load()
        assertEquals("103", requireNotNull(flyway.info().current()).version.toString())
        val messageCount = messageCount(source)
        val staleBefore = metadataMismatchCount(source)
        assertTrue(staleBefore > 0, "The restored production backup must reproduce stale context metadata")

        val elapsed = measureTimeMillis {
            val result = flyway.migrate()
            assertEquals(listOf("104", "105"), result.migrations.map { it.version })
        }

        flyway.validate()
        assertEquals("105", requireNotNull(flyway.info().current()).version.toString())
        assertEquals(messageCount, messageCount(source))
        assertEquals(0, metadataMismatchCount(source))
        println("CONTEXT_METADATA_BACKUP_REPAIR messages=$messageCount repaired=$staleBefore migration_ms=$elapsed")
    }

    private fun messageCount(source: PGSimpleDataSource): Long = source.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM messages").use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun metadataMismatchCount(source: PGSimpleDataSource): Int {
        val json = Json { ignoreUnknownKeys = true }
        var mismatches = 0
        source.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "SELECT id, message_json, context_metadata_json FROM messages ORDER BY id",
                ).use { statement ->
                    statement.fetchSize = 64
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            val message = json.decodeFromString<Conversation.Message>(rows.getString("message_json"))
                            check(message.id.value == rows.getString("id"))
                            val stored = json.decodeFromString<MessageContextMetadata>(rows.getString("context_metadata_json"))
                            if (stored != MessageContextMetadata.from(message)) mismatches++
                        }
                    }
                }
            } finally {
                connection.rollback()
            }
        }
        return mismatches
    }
}
