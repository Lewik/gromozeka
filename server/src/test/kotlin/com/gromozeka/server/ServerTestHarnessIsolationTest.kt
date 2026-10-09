package com.gromozeka.server

import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.server.testsupport.app.ServerTestHarness
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "GROMOZEKA_POSTGRES_RUNTIME_TEST", matches = "true")
class ServerTestHarnessIsolationTest {
    @Test fun `default E2E context excludes unrelated model and tool fixtures`() {
        val schema = "harness_isolation_${UUID.randomUUID().toString().replace("-", "")}"
        val jdbc = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL"))
        try {
            ServerTestHarness(subscriptionSession = null, systemProperties = mapOf(
                "gromozeka.postgres.jdbc-url" to jdbc, "gromozeka.postgres.schema" to schema,
                "gromozeka.llm.cassette.mode" to "replay-only",
            ), aiCatalogTransform = { it }).use { harness ->
                assertEquals("CassetteAiRuntimeProvider", harness.context.getBean(AiRuntimeProvider::class.java).javaClass.simpleName)
                assertFalse(harness.context.containsBean("telegramIsolationModel"))
                assertFalse(harness.context.containsBean("memoryE2eReadTraceCollector"))
                assertTrue(harness.context.getBean(AiToolProvider::class.java).getTools().any { it.definition.name == "grz_slot_acquire" })
            }
        } finally {
            DriverManager.getConnection(jdbc, "gromozeka", "gromozeka").use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }
}
