package com.gromozeka.server

import com.gromozeka.application.service.RuntimeCatalogTemplateApplicationService
import com.gromozeka.application.service.ServerAiCallAdmission
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.AiCatalogManagementService
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.SettingsService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mockito.Mockito
import kotlin.test.*

class AiConcurrencyToolsTest {
    @Test
    fun `MCP group mutations decode their entity revision and enforce Server owner access`() = runBlocking<Unit> {
        val seed = RuntimeCatalogTemplateApplicationService().createSeed().aiCatalog
        val group = AiConcurrencyGroup(AiConcurrencyGroup.Id("shared"), "Shared", 2)
        val manager = Mockito.mock(AiCatalogManagementService::class.java)
        Mockito.`when`(manager.upsertConcurrencyGroup(group, 7L))
            .thenReturn(AiCatalogSnapshot(seed.copy(concurrencyGroups = seed.concurrencyGroups + group), 8))
        Mockito.`when`(manager.deleteConcurrencyGroup(group.id, 8L)).thenReturn(AiCatalogSnapshot(seed, 9))
        val contributor = AiSettingsToolContributor(testServerToolFactory(),
            Mockito.mock(AiConfigurationService::class.java), manager, Mockito.mock(SettingsService::class.java))
        val upsert = contributor.callbacks.single { it.definition.name == "grz_ai_concurrency_group_upsert" }
        val arguments = buildJsonObject {
            put("group", Json.encodeToJsonElement(AiConcurrencyGroup.serializer(), group))
            put("expectedRevision", 7)
        }
        val saved = upsert.invokeStructured(testControlMcpContext(), arguments)
        assertContains(saved.toString(), "\"maxConcurrentCalls\":2")
        val denied = upsert.invoke(testControlMcpContext(User.Role.entries.first { it != User.Role.OWNER }), arguments)
        assertEquals(true, denied.isError)
        Mockito.verify(manager).upsertConcurrencyGroup(group, 7L)
        val delete = contributor.callbacks.single { it.definition.name == "grz_ai_concurrency_group_delete" }
        delete.invokeStructured(testControlMcpContext(), buildJsonObject {
            put("groupId", "shared")
            put("expectedRevision", 8)
        })
        Mockito.verify(manager).deleteConcurrencyGroup(group.id, 8L)
        Mockito.verifyNoMoreInteractions(manager)
    }

    @Test
    fun `TPM groups retain human keys and require owner revisioned mutations`() = runBlocking<Unit> {
        val seed = RuntimeCatalogTemplateApplicationService().createSeed().aiCatalog
        val group = AiTpmGroup(AiTpmGroup.Id("quota"), "account/model", "Quota", 1000, quotaUsageFraction = 0.9)
        val manager = Mockito.mock(AiCatalogManagementService::class.java)
        Mockito.`when`(manager.upsertTpmGroup(group, 7L)).thenReturn(AiCatalogSnapshot(seed.copy(tpmGroups = listOf(group)), 8))
        Mockito.`when`(manager.deleteTpmGroup(group.id, 8L)).thenReturn(AiCatalogSnapshot(seed, 9))
        val contributor = AiSettingsToolContributor(testServerToolFactory(), Mockito.mock(AiConfigurationService::class.java),
            manager, Mockito.mock(SettingsService::class.java))
        val upsert = contributor.callbacks.single { it.definition.name == "grz_ai_tpm_group_upsert" }
        val arguments = buildJsonObject { put("group", Json.encodeToJsonElement(AiTpmGroup.serializer(), group)); put("expectedRevision", 7) }
        assertContains(upsert.invokeStructured(testControlMcpContext(), arguments).toString(), "account/model")
        assertEquals(true, upsert.invoke(testControlMcpContext(User.Role.entries.first { it != User.Role.OWNER }), arguments).isError)
        contributor.callbacks.single { it.definition.name == "grz_ai_tpm_group_delete" }.invokeStructured(testControlMcpContext(),
            buildJsonObject { put("groupId", "quota"); put("expectedRevision", 8) })
        Mockito.verify(manager).upsertTpmGroup(group, 7L)
        Mockito.verify(manager).deleteTpmGroup(group.id, 8L)
        Mockito.verifyNoMoreInteractions(manager)
    }

    @Test
    fun `admission status exposes only the typed routing snapshot`() = runBlocking<Unit> {
        val admission = Mockito.mock(ServerAiCallAdmission::class.java)
        Mockito.`when`(admission.snapshot).thenReturn(AiAdmissionSnapshot(waiting = listOf(
            AiAdmissionOperation("operation", AiAdmissionTarget.Connection(AiConnection.Id("connection")), "CONVERSATION"))))
        val contributor = AiAdmissionToolContributor(testServerToolFactory(), admission)
        val result = contributor.callbacks.single().invokeStructured(testControlMcpContext(), buildJsonObject {})
        assertContains(result.toString(), "\"operation\"")
        assertContains(result.toString(), "CONVERSATION")
        assertFalse(result.toString().contains("messages"))
        assertFalse(result.toString().contains("prompt"))
    }
}
