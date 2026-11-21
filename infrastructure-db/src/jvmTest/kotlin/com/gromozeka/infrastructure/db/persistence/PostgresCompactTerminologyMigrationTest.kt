package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.ai.AiModelConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresCompactTerminologyMigrationTest {
    @Test fun migratesConfigurationAndDurableRuntimeValuesWithoutRewritingUnrelatedText() {
        assumeTrue(System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        val source = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
            password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        }
        val schema = "compact_terminology_${UUID.randomUUID().toString().replace("-", "") }"
        source.connection.use { admin ->
            admin.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                source.currentSchema = schema
                source.connection.use { connection ->
                    connection.createStatement().use { sql ->
                        sql.execute("CREATE TABLE ai_model_configurations (id TEXT PRIMARY KEY, payload_json TEXT NOT NULL)")
                        sql.execute("CREATE TABLE runtime_catalog_configuration (revision BIGINT NOT NULL)")
                        sql.execute("CREATE TABLE conversation_runtime_records (scheduling JSONB NOT NULL)")
                        sql.execute("CREATE TABLE conversation_runtime_events (entry_json JSONB NOT NULL)")
                        sql.execute("CREATE TABLE conversation_runtime_trace (entry_json JSONB NOT NULL)")
                        sql.execute("INSERT INTO runtime_catalog_configuration VALUES (7)")
                        sql.execute(
                            """INSERT INTO ai_model_configurations VALUES ('main',
                                '{"id":"main","connectionId":"connection","providerModelId":"model","displayName":"Main","compaction":{"thresholdTokens":1000,"summaryModelConfigurationId":"compactor"}}')""".trimIndent()
                        )
                        sql.execute("""INSERT INTO conversation_runtime_records VALUES ('{"mutation":{"strategy":"SUMMARIZE"},"label":"SUMMARIZE"}')""")
                        sql.execute("""INSERT INTO conversation_runtime_events VALUES ('{"event":{"mutation":{"strategy":"SUMMARIZE"}},"label":"SUMMARIZE"}')""")
                        sql.execute("""INSERT INTO conversation_runtime_trace VALUES ('{"mutation":{"strategy":"SUMMARIZE"},"label":"SUMMARIZE"}')""")
                        sql.execute(requireNotNull(javaClass.getResource(
                            "/db/migration/postgres/V92__compact_terminology.sql")).readText())
                    }

                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT payload_json FROM ai_model_configurations").use { rows ->
                            assertTrue(rows.next())
                            val payload = Json.parseToJsonElement(rows.getString(1)).jsonObject
                            val compaction = payload.getValue("compaction").jsonObject
                            assertFalse("summaryModelConfigurationId" in compaction)
                            assertEquals("compactor", compaction.getValue("compactionModelConfigurationId").jsonPrimitive.content)
                            val decoded = Json.decodeFromString(AiModelConfiguration.serializer(), payload.toString())
                            assertEquals(AiModelConfiguration.Id("compactor"), decoded.compaction.compactionModelConfigurationId)
                        }
                        for ((table, column) in listOf(
                            "conversation_runtime_records" to "scheduling",
                            "conversation_runtime_events" to "entry_json",
                            "conversation_runtime_trace" to "entry_json",
                        )) {
                            statement.executeQuery("SELECT $column::text FROM $table").use { rows ->
                                assertTrue(rows.next())
                                val value = rows.getString(1)
                                assertTrue(value.contains("\"strategy\": \"COMPACT\""), value)
                                assertTrue(value.contains("\"label\": \"SUMMARIZE\""), value)
                            }
                        }
                        statement.executeQuery("SELECT revision FROM runtime_catalog_configuration").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(8L, rows.getLong(1))
                        }
                    }
                }
            } finally {
                admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
