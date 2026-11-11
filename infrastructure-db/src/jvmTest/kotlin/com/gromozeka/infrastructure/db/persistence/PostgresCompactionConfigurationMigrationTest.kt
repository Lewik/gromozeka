package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.*

class PostgresCompactionConfigurationMigrationTest {
    @Test fun resetsOnlyCompactionPolicyAndRemovesTheGlobalAssignment() {
        assumeTrue(System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        val source = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
            password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        }
        val schema = "compaction_config_${UUID.randomUUID().toString().replace("-", "") }"
        source.connection.use { admin ->
            admin.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                source.currentSchema = schema
                source.connection.use { connection ->
                    connection.createStatement().use { sql ->
                        sql.execute("CREATE TABLE ai_model_specs (id TEXT PRIMARY KEY, payload_json TEXT NOT NULL)")
                        sql.execute("CREATE TABLE ai_model_configurations (id TEXT PRIMARY KEY, payload_json TEXT NOT NULL)")
                        sql.execute("CREATE TABLE ai_runtime_assignments (purpose TEXT PRIMARY KEY, model_configuration_id TEXT NOT NULL)")
                        sql.execute("CREATE TABLE runtime_catalog_configuration (revision BIGINT NOT NULL)")
                        sql.execute("INSERT INTO runtime_catalog_configuration VALUES (7)")
                        sql.execute("INSERT INTO ai_runtime_assignments VALUES ('MESSAGE_SQUASH','model'), ('DEFAULT_CHAT','model')")
                    }
                    val policies = listOf("""{"type":"percent","value":80}""", """{"type":"absolute","tokens":1500}""")
                    for ((index, policy) in policies.withIndex()) {
                        val spec = """{"id":"route-$index","provider":"OPENAI","capabilities":["TEXT_GENERATION"],"limits":{"textGeneration":{"contextWindowTokens":2000,"maxOutputTokens":1000,"autoCompaction":$policy}}}"""
                        val configuration = """{"id":"config-$index","connectionId":"connection","providerModelId":"route-$index","displayName":"Preserve me","defaultParameters":{"maxOutputTokens":500}}"""
                        for ((table, payload) in listOf("ai_model_specs" to spec, "ai_model_configurations" to configuration)) {
                            connection.prepareStatement("INSERT INTO $table VALUES (?,?)").use { statement ->
                                statement.setString(1, index.toString()); statement.setString(2, payload); statement.executeUpdate()
                            }
                        }
                    }
                    connection.createStatement().use { it.execute(requireNotNull(javaClass.getResource(
                        "/db/migration/postgres/V85__configuration_compaction.sql")).readText()) }
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT payload_json FROM ai_model_specs ORDER BY id").use { result ->
                            var count = 0
                            while (result.next()) {
                                val payload = result.getString(1)
                                assertFalse(payload.contains("autoCompaction"))
                                val spec = Json.decodeFromString(AiModelSpec.serializer(), payload)
                                assertEquals(2000, spec.contextWindowTokens); assertEquals(1000, spec.maxOutputTokens)
                                count++
                            }
                            assertEquals(2, count)
                        }
                        statement.executeQuery("SELECT payload_json FROM ai_model_configurations ORDER BY id").use { result ->
                            var count = 0
                            while (result.next()) {
                                val model = Json.decodeFromString(AiModelConfiguration.serializer(), result.getString(1))
                                assertEquals(AiModelConfiguration.Compaction(), model.compaction)
                                assertEquals(500, model.defaultParameters.maxOutputTokens)
                                assertEquals("Preserve me", model.displayName)
                                count++
                            }
                            assertEquals(2, count)
                        }
                        statement.executeQuery("SELECT purpose FROM ai_runtime_assignments").use { result ->
                            assertTrue(result.next()); assertEquals("DEFAULT_CHAT", result.getString(1)); assertFalse(result.next())
                        }
                        statement.executeQuery("SELECT revision FROM runtime_catalog_configuration").use { result ->
                            assertTrue(result.next()); assertEquals(8L, result.getLong(1))
                        }
                    }
                }
            } finally { admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
