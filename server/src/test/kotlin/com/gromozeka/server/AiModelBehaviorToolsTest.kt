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

class AiModelBehaviorToolsTest {
    @Test
    fun `schema finish reason workaround is explicit revisioned and round trips through existing MCP tools`() = runBlocking<Unit> {
        val seed = RuntimeCatalogTemplateApplicationService().createSeed().aiCatalog
        val original = seed.modelSpecs.first { it.limits.textGeneration != null }
        assertFalse(original.behavior.chatSchemaEmptyToolCallsAsStop)
        var snapshot = AiCatalogSnapshot(seed, 10)
        val configuration = Mockito.mock(AiConfigurationService::class.java) { invocation ->
            when (invocation.method.name.substringBefore('-')) {
                "getSnapshot" -> snapshot
                "refreshIfChanged" -> Unit
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val manager = Mockito.mock(AiCatalogManagementService::class.java) { invocation ->
            if (invocation.method.name.startsWith("upsertModelSpec")) {
                val spec = invocation.arguments[0] as AiModelSpec
                check(invocation.arguments[1] == snapshot.revision) { "AI catalog revision conflict" }
                snapshot = AiCatalogSnapshot(snapshot.catalog.copy(modelSpecs = snapshot.catalog.modelSpecs.map {
                    if (it.id == spec.id && it.provider == spec.provider) spec else it
                }), snapshot.revision + 1)
                snapshot
            } else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        val tools = AiSettingsToolContributor(testServerToolFactory(), configuration, manager, Mockito.mock(SettingsService::class.java)).callbacks
        val upsert = tools.single { it.definition.name == "grz_ai_model_spec_upsert" }
        val read = tools.single { it.definition.name == "grz_ai_catalog_get" }
        val enabled = original.copy(behavior = original.behavior.copy(chatSchemaEmptyToolCallsAsStop = true))
        fun arguments(spec: AiModelSpec, revision: Long) = buildJsonObject {
            put("modelSpec", Json.encodeToJsonElement(AiModelSpec.serializer(), spec))
            put("expectedRevision", revision)
        }
        assertContains(upsert.invokeStructured(testControlMcpContext(), arguments(enabled, 10)).toString(), "\"chatSchemaEmptyToolCallsAsStop\":true")
        assertContains(read.invokeStructured(testControlMcpContext(), buildJsonObject {}).toString(), "\"chatSchemaEmptyToolCallsAsStop\":true")
        assertEquals(11L, snapshot.revision)
        assertEquals(true, upsert.invoke(testControlMcpContext(), arguments(enabled, 10)).isError)
        assertEquals(true, upsert.invoke(testControlMcpContext(User.Role.entries.first { it != User.Role.OWNER }), arguments(enabled, 11)).isError)
        assertEquals(11L, snapshot.revision)
        upsert.invokeStructured(testControlMcpContext(), arguments(original, 11))
        assertEquals(original.behavior, snapshot.catalog.modelSpecs.single { it.id == original.id && it.provider == original.provider }.behavior)
        assertEquals(12L, snapshot.revision)
    }
}
