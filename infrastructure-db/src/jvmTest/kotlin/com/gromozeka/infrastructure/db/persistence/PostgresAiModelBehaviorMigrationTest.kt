package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.*
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PostgresAiModelBehaviorMigrationTest {
    @Test
    fun `migration preserves effective native rules and manual data without touching gateway routes`() {
        val url = System.getenv("GROMOZEKA_MODEL_BEHAVIOR_TEST_URL") ?: run {
            println("SKIP: isolated PostgreSQL fixture not configured for model behavior migration")
            return
        }
        val source = PGSimpleDataSource().apply {
            setURL(url)
            user = System.getenv("GROMOZEKA_MODEL_BEHAVIOR_TEST_USER") ?: "grz_d_test"
        }
        val schema = "model_behavior_${UUID.randomUUID().toString().replace("-", "")}"
        source.connection.use { admin ->
            admin.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                source.currentSchema = schema
                source.connection.use { connection ->
                    connection.createStatement().use {
                        it.execute("CREATE TABLE ai_model_specs(provider TEXT NOT NULL, model_id TEXT NOT NULL, payload_json TEXT NOT NULL, PRIMARY KEY(provider, model_id))")
                        it.execute("CREATE TABLE runtime_catalog_configuration(id TEXT PRIMARY KEY, revision BIGINT NOT NULL)")
                        it.execute("INSERT INTO runtime_catalog_configuration VALUES ('catalog', 7)")
                    }
                    val opus5 = spec("claude-opus-5")
                    val opus55 = spec("claude-opus-5-5").copy(reasoning = AiReasoningCapabilities(efforts = setOf(AiReasoningEffort.LOW)))
                    val gateway = spec("account/claude-opus-5-5")
                    val customProvider = opus5.copy(provider = AiProvider.CUSTOM)
                    val originals = listOf(opus5, opus55, gateway, customProvider).associateBy { it.provider to it.id }
                    for (spec in originals.values) {
                        val payload = Json.encodeToString(spec)
                        assertFalse("behavior" in payload)
                        connection.prepareStatement("INSERT INTO ai_model_specs VALUES (?, ?, ?)").use {
                            it.setString(1, spec.provider.name); it.setString(2, spec.id); it.setString(3, payload); it.executeUpdate()
                        }
                    }
                    migrate(connection)
                    val saved = read(connection)
                    assertEquals(8, revision(connection))
                    assertEquals(gateway, saved.getValue(gateway.provider to gateway.id))
                    assertEquals(customProvider, saved.getValue(customProvider.provider to customProvider.id))
                    val expected5 = opus5.copy(
                        reasoning = AiReasoningCapabilities(modes = setOf(AiReasoningMode.DISABLED, AiReasoningMode.ADAPTIVE)),
                        behavior = AiModelBehavior(chatCompletionsTokenLimit = AiModelBehavior.ChatCompletionsTokenLimit.MAX_TOKENS,
                            chatCompletionsReasoning = AiModelBehavior.ChatCompletionsReasoning.ANTHROPIC_THINKING,
                            maximumDisabledReasoningEffort = AiReasoningEffort.HIGH))
                    val expected55 = opus55.copy(
                        reasoning = opus55.reasoning!!.copy(modes = setOf(AiReasoningMode.ADAPTIVE)),
                        behavior = AiModelBehavior(chatCompletionsTokenLimit = AiModelBehavior.ChatCompletionsTokenLimit.MAX_TOKENS,
                            chatCompletionsReasoning = AiModelBehavior.ChatCompletionsReasoning.ANTHROPIC_THINKING,
                            defaultReasoningMode = AiReasoningMode.ADAPTIVE,
                            supportsForcedToolChoice = false, anthropicThinkingPrefixDrop = true))
                    assertEquals(expected5, saved.getValue(opus5.provider to opus5.id))
                    assertEquals(expected55, saved.getValue(opus55.provider to opus55.id))
                    val customized = expected55.copy(behavior = expected55.behavior.copy(
                        minimumToolReasoningEffort = AiReasoningEffort.LOW, anthropicThinkingPrefixDrop = false))
                    connection.prepareStatement("UPDATE ai_model_specs SET payload_json = ? WHERE provider = ? AND model_id = ?").use {
                        it.setString(1, Json.encodeToString(customized)); it.setString(2, customized.provider.name); it.setString(3, customized.id); it.executeUpdate()
                    }
                    migrate(connection)
                    assertEquals(8, revision(connection))
                    assertEquals(saved + ((customized.provider to customized.id) to customized), read(connection))
                    println("MODEL_BEHAVIOR_PG: migration, exact scope, manual fields, JSON roundtrip and idempotence verified")
                }
            } finally {
                admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun migrate(connection: Connection) = connection.createStatement().use {
        it.execute(requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/V79__explicit_ai_model_behavior.sql")).readText())
    }

    private fun read(connection: Connection): Map<Pair<AiProvider, String>, AiModelSpec> =
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

    private fun revision(connection: Connection) = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT revision FROM runtime_catalog_configuration").use { it.next(); it.getInt(1) }
    }

    private fun spec(id: String) = AiModelSpec(id, AiProvider.ANTHROPIC,
        setOf(AiModelCapability.TEXT_GENERATION, AiModelCapability.TOOL_CALLING),
        AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(32_000, 4096)))
}
