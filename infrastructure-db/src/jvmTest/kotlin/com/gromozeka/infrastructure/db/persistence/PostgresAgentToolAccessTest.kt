package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.*

class PostgresAgentToolAccessTest {
    @Test fun `migration preserves preloads and policy saves do not reject blocked entries`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "agent_tools_${UUID.randomUUID().toString().replace("-", "")}"
        val source = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = "gromozeka"; password = "gromozeka"; currentSchema = "$schema,public"
        }
        try {
            fun flyway() = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration/postgres")
            flyway().target("58").load().migrate()
            source.connection.use { it.createStatement().use { sql -> sql.execute("""
                INSERT INTO agents (id, name, prompts_json, skills_json, runtime_selection_json, runtime_overrides_json, tools_json, type, created_at, updated_at)
                VALUES ('test-agent', 'Test', '[]', '[]', '{"modelConfigurationId":"model"}', '{}', '["blocked_tool"]', 'global', now(), now())
            """.trimIndent()) } }
            flyway().load().migrate()
            Database.connect(source)
            val repository = ExposedAgentRepository(Json)
            val initial = assertNotNull(repository.findById(AgentDefinition.Id("test-agent")))
            assertEquals(AgentPreloadedTools(listOf("blocked_tool")), initial.tools)
            assertEquals(ToolAccessPolicy.DenyListed(), initial.toolAccess)
            for (policy in listOf(ToolAccessPolicy.AllowOnly(), ToolAccessPolicy.DenyListed(setOf(
                ToolSelector.ByName(QualifiedToolName("gromozeka", "blocked_tool")),
                ToolSelector.ExactRevision(ToolContractFingerprint("a".repeat(64))),
            )))) {
                repository.save(initial.copy(toolAccess = policy))
                val loaded = assertNotNull(repository.findById(initial.id))
                assertEquals(policy, loaded.toolAccess)
                assertEquals(initial.tools, loaded.tools)
            }
        } finally {
            source.connection.use { it.createStatement().use { sql -> sql.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }
}
