package com.gromozeka.server

import com.gromozeka.application.service.RuntimeCatalogTemplateApplicationService
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.AiCatalogManagementService
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.SettingsService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*

class AiCompactionToolsTest {
    @Test
    fun `existing MCP tools expose set read reset revision and access contracts`() = runBlocking<Unit> {
        val seed = RuntimeCatalogTemplateApplicationService().createSeed().aiCatalog
        val original = seed.modelConfigurations.first { seed.modelSpecFor(it)?.limits?.textGeneration != null }
        val summary = original.copy(id = AiModelConfiguration.Id("compaction-test-summary"), enabled = true)
        var snapshot = AiCatalogSnapshot(seed.copy(modelConfigurations = seed.modelConfigurations + summary), 7)
        val configurations = Mockito.mock(AiConfigurationService::class.java) { invocation ->
            when (invocation.method.name.substringBefore('-')) {
                "getSnapshot" -> snapshot
                "refreshIfChanged" -> Unit
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val manager = Mockito.mock(AiCatalogManagementService::class.java) { invocation ->
            if (invocation.method.name.startsWith("upsertModelConfiguration")) {
                val model = invocation.arguments[0] as AiModelConfiguration
                val expected = invocation.arguments[1] as Long
                check(expected == snapshot.revision) { "AI catalog revision conflict" }
                snapshot = AiCatalogSnapshot(snapshot.catalog.copy(modelConfigurations = snapshot.catalog.modelConfigurations.map {
                    if (it.id == model.id) model else it
                }), snapshot.revision + 1)
                snapshot
            } else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        val tools = AiSettingsToolContributor(testServerToolFactory(), configurations, manager, Mockito.mock(SettingsService::class.java)).callbacks
        val upsert = tools.single { it.definition.name == "grz_ai_model_configuration_upsert" }
        val read = tools.single { it.definition.name == "grz_ai_catalog_get" }
        assertTrue(tools.none { it.definition.name.startsWith("grz_ai_compaction_") })
        val configured = original.copy(compaction = AiModelConfiguration.Compaction(1000, summary.id,
            AiModelConfiguration.TokenCounting(AiModelConfiguration.TokenCounting.Mode.LOCAL)))
        val args = buildJsonObject {
            put("modelConfiguration", Json.encodeToJsonElement(AiModelConfiguration.serializer(), configured))
            put("expectedRevision", 7)
        }
        val saved = upsert.invokeStructured(testControlMcpContext(), args)
        assertContains(saved.toString(), "\"thresholdTokens\":1000")
        assertContains(saved.toString(), "\"summaryModelConfigurationId\":\"compaction-test-summary\"")
        val fetched = read.invokeStructured(testControlMcpContext(), buildJsonObject {})
        assertContains(fetched.toString(), "\"thresholdTokens\":1000")
        assertContains(fetched.toString(), "\"mode\":\"LOCAL\"")
        assertEquals(8L, snapshot.revision)
        val denied = upsert.invoke(testControlMcpContext(User.Role.entries.first { it != User.Role.OWNER }), args)
        assertEquals(true, denied.isError)
        assertEquals(true, upsert.invoke(testControlMcpContext(), args).isError)
        assertEquals(8L, snapshot.revision)
        val resetModel = JsonObject(Json.encodeToJsonElement(AiModelConfiguration.serializer(), configured).jsonObject +
            ("compaction" to buildJsonObject {}))
        upsert.invokeStructured(testControlMcpContext(), buildJsonObject {
            put("modelConfiguration", resetModel); put("expectedRevision", 8)
        })
        assertEquals(AiModelConfiguration.Compaction(), snapshot.catalog.modelConfigurations.single { it.id == original.id }.compaction)
        assertEquals(9L, snapshot.revision)
    }
}
