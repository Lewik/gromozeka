package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.ai.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.*

class PostgresAiHttpSettingsTest {
    @Test
    fun `real catalog repository persists HTTP settings secret references and revisions`() = runBlocking {
        val url = System.getenv("GROMOZEKA_AI_HTTP_TEST_URL") ?: run {
            println("SKIP: isolated PostgreSQL fixture not configured for AI HTTP catalog test")
            return@runBlocking
        }
        val source = PGSimpleDataSource().apply {
            setURL(url)
            user = System.getenv("GROMOZEKA_AI_HTTP_TEST_USER") ?: "grz_e_test"
            password = System.getenv("GROMOZEKA_AI_HTTP_TEST_PASSWORD")
        }
        val schema = "ai_http_${UUID.randomUUID().toString().replace("-", "")}"
        source.connection.use { admin ->
            admin.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                source.currentSchema = schema
                source.connection.use { connection -> connection.createStatement().use { sql ->
                    sql.execute("CREATE TABLE agents(id VARCHAR(255) PRIMARY KEY)")
                    sql.execute("INSERT INTO agents VALUES ('synthetic-agent')")
                    sql.execute("CREATE TABLE ai_connections(id VARCHAR(255) PRIMARY KEY, payload_json TEXT NOT NULL)")
                    sql.execute("CREATE TABLE ai_model_specs(provider VARCHAR(50) NOT NULL, model_id VARCHAR(255) NOT NULL, payload_json TEXT NOT NULL, PRIMARY KEY(provider, model_id))")
                    sql.execute("CREATE TABLE ai_model_configurations(id VARCHAR(255) PRIMARY KEY, connection_id VARCHAR(255) NOT NULL REFERENCES ai_connections(id), payload_json TEXT NOT NULL)")
                    sql.execute("CREATE TABLE ai_runtime_assignments(purpose VARCHAR(100) PRIMARY KEY, model_configuration_id VARCHAR(255) NOT NULL REFERENCES ai_model_configurations(id), payload_json TEXT NOT NULL)")
                    sql.execute("CREATE TABLE runtime_catalog_configuration(id VARCHAR(32) PRIMARY KEY, default_agent_id VARCHAR(255) NOT NULL REFERENCES agents(id), web_tools_json TEXT NOT NULL, revision BIGINT NOT NULL)")
                    sql.execute(requireNotNull(PostgresAiHttpSettingsTest::class.java.getResource("/db/migration/postgres/V83__ai_concurrency_groups.sql")).readText())
                    sql.execute(requireNotNull(PostgresAiHttpSettingsTest::class.java.getResource("/db/migration/postgres/V84__model_configuration_tpm.sql")).readText())
                    sql.execute("INSERT INTO runtime_catalog_configuration VALUES ('primary', 'synthetic-agent', '{}', 7)")
                } }
                Database.connect(source)
                val json = Json { encodeDefaults = true }
                val repository = ExposedRuntimeCatalogRepository(json)
                val settings = AiHttpSettings(AiHttpSettings.Authentication.BEARER, "/worker/ca.pem", "http://proxy.example:8080",
                    listOf(".internal", "[::1]:8443"), mapOf("User-Agent" to "PG-fixture", "X-TFY-METADATA" to """{"application":"fixture"}"""))
                val target = AiExecutionTarget.Worker("exact-worker")
                val connections = listOf<AiConnection>(
                    AiConnection.OpenAiCompatible(AiConnection.Id("chat"), "A Chat", baseUrl = "https://gateway.example/api/llm",
                        apiKey = SecretRef.Inline("private-inline-token"), httpSettings = settings, executionTarget = target),
                    AiConnection.OpenAiApi(AiConnection.Id("responses"), "B Responses", baseUrl = "https://gateway.example/api/llm",
                        apiKey = SecretRef.EnvironmentVariable("WORKER_TOKEN"), httpSettings = settings, executionTarget = target),
                    AiConnection.AnthropicApi(AiConnection.Id("messages"), "C Messages", baseUrl = "https://gateway.example/api/llm",
                        apiKey = SecretRef.File("/worker/token"), httpSettings = settings, executionTarget = target),
                )
                val specs = connections.map { connection -> AiModelSpec("account/${connection.id.value}", connection.kind.provider,
                    AiModelCapability.entries.toSet(), AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(32_000),
                        AiModelSpec.Limits.Embeddings(dimensions = 2)),
                    behavior = AiModelBehavior(chatSchemaEmptyToolCallsAsStop = true)) }.sortedWith(compareBy({ it.provider.name }, { it.id }))
                val configurations = connections.map { AiModelConfiguration(AiModelConfiguration.Id(it.id.value), it.id,
                    "account/${it.id.value}", it.displayName) }
                val assignments = AiRuntimeAssignment.Purpose.entries.filter { it.requiresExplicitAssignment }.map {
                    AiRuntimeAssignment(it, AiRuntimeSelection(configurations.first().id))
                }
                val quota = AiTpmGroup(AiTpmGroup.Id("quota"), "account/shared", "Quota", 1000, quotaUsageFraction = 0.8)
                val policy = AiTpmPolicy(AiTpmGroupSelector.RequestValue("/model"), AiQuotaCalculator.BuiltIn(),
                    AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Header("custom-quota")), quota.id)
                val catalog = AiCatalog(connections, specs, configurations.map { it.copy(tpm = policy) }, assignments,
                    AgentDefinition.Id("synthetic-agent"), tpmGroups = listOf(quota))
                val saved = repository.replace(7, catalog)
                assertEquals(8, saved.revision)
                assertEquals(saved, ExposedRuntimeCatalogRepository(json).find())
                assertFalse(json.encodeToString(saved.redactInlineSecrets()).contains("private-inline-token"))
                val updated = catalog.copy(connections = catalog.connections.map {
                    if (it is AiConnection.OpenAiCompatible) it.copy(apiKey = SecretRef.File("/worker/rotated-token"),
                        httpSettings = settings.copy(extraHeaders = mapOf("User-Agent" to "PG-updated"))) else it
                })
                val rotated = repository.replace(8, updated)
                assertEquals(9, rotated.revision)
                assertEquals(rotated, ExposedRuntimeCatalogRepository(json).find())
                assertFailsWith<IllegalStateException> { repository.replace(8, catalog) }
                assertEquals(rotated, repository.find())
                val state = rotated.secretStates.single { it.slot == AiCatalogSecretSlot.ConnectionApiKey(AiConnection.Id("chat")) }
                assertEquals(AiCatalogSecretState.Source.FILE, state.source)
                assertEquals("/worker/rotated-token", state.filePath)
                println("AI_HTTP_PG: production Exposed repository insert/update/reload, selected sources, redaction and revision conflict verified")
            } finally {
                admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
