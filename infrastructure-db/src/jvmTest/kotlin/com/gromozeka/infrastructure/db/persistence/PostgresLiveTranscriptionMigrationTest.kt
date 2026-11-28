package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelSpec
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresLiveTranscriptionMigrationTest {
    @Test
    fun `migration upgrades the native GPT transcribe spec without changing unrelated catalog entries`() {
        val url = System.getenv("GROMOZEKA_AI_HTTP_TEST_URL") ?: run {
            println("SKIP: isolated PostgreSQL fixture not configured for Live transcription migration")
            return
        }
        val source = PGSimpleDataSource().apply {
            setURL(url)
            user = System.getenv("GROMOZEKA_AI_HTTP_TEST_USER") ?: "grz_e_test"
            password = System.getenv("GROMOZEKA_AI_HTTP_TEST_PASSWORD")
        }
        val schema = "live_transcription_${UUID.randomUUID().toString().replace("-", "")}"
        source.connection.use { admin ->
            admin.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                source.currentSchema = schema
                source.connection.use { connection ->
                    connection.createStatement().use { sql ->
                        sql.execute("CREATE TABLE ai_model_specs(provider TEXT NOT NULL, model_id TEXT NOT NULL, payload_json TEXT NOT NULL, PRIMARY KEY(provider, model_id))")
                        sql.execute("CREATE TABLE ai_runtime_assignments(purpose TEXT PRIMARY KEY, model_configuration_id TEXT NOT NULL, payload_json TEXT NOT NULL)")
                        sql.execute("CREATE TABLE runtime_catalog_configuration(id TEXT PRIMARY KEY, revision BIGINT NOT NULL)")
                        sql.execute("INSERT INTO runtime_catalog_configuration VALUES ('primary', 41)")
                        sql.execute("INSERT INTO ai_runtime_assignments VALUES ('SPEECH_TO_TEXT', 'dictation', '{}'), ('LIVE_TRANSCRIPT_STABILIZER', 'old-stabilizer', '{}'), ('LIVE_TRANSLATION', 'old-translation', '{}')")
                    }
                    val native = spec("gpt-transcribe", AiProvider.OPENAI)
                    val customRoute = spec("gpt-transcribe", AiProvider.CUSTOM)
                    val unrelated = spec("other-transcribe", AiProvider.OPENAI)
                    for (model in listOf(native, customRoute, unrelated)) {
                        connection.prepareStatement("INSERT INTO ai_model_specs VALUES (?, ?, ?)").use { statement ->
                            statement.setString(1, model.provider.name)
                            statement.setString(2, model.id)
                            statement.setString(3, Json.encodeToString(model))
                            statement.executeUpdate()
                        }
                    }

                    migrate(connection)

                    assertEquals(
                        native.copy(capabilities = native.capabilities + AiModelCapability.REALTIME_TRANSCRIPTION),
                        readSpecs(connection).getValue(AiProvider.OPENAI to "gpt-transcribe"),
                    )
                    assertEquals(customRoute, readSpecs(connection).getValue(AiProvider.CUSTOM to "gpt-transcribe"))
                    assertEquals(unrelated, readSpecs(connection).getValue(AiProvider.OPENAI to "other-transcribe"))
                    assertEquals(setOf("SPEECH_TO_TEXT"), readPurposes(connection))
                    assertEquals(42, revision(connection))
                }
            } finally {
                admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun migrate(connection: Connection) = connection.createStatement().use {
        it.execute(requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/V106__live_transcription.sql")).readText())
    }

    private fun readSpecs(connection: Connection): Map<Pair<AiProvider, String>, AiModelSpec> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT payload_json FROM ai_model_specs").use { result ->
                buildMap {
                    while (result.next()) {
                        val spec = Json.decodeFromString<AiModelSpec>(result.getString(1))
                        put(spec.provider to spec.id, spec)
                    }
                }
            }
        }

    private fun readPurposes(connection: Connection): Set<String> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT purpose FROM ai_runtime_assignments").use { result ->
            buildSet { while (result.next()) add(result.getString(1)) }
        }
    }

    private fun revision(connection: Connection) = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT revision FROM runtime_catalog_configuration").use { it.next(); it.getInt(1) }
    }

    private fun spec(id: String, provider: AiProvider) = AiModelSpec(
        id = id,
        provider = provider,
        capabilities = setOf(AiModelCapability.SPEECH_TO_TEXT),
    )
}
