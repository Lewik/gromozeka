package com.gromozeka.server

import com.gromozeka.application.service.RuntimeCatalogTemplateApplicationService
import com.gromozeka.domain.model.AgentDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeCatalogTemplatesIntegrationTest {
    @Test
    fun `templates produce a valid initial runtime catalog`() {
        val templateService = RuntimeCatalogTemplateApplicationService()
        val seed = templateService.createSeed()
        val defaultAgent = seed.agents.single { it.id == seed.aiCatalog.defaultAgentId }

        assertEquals(AgentDefinition.Id("global:default-gromozeka"), defaultAgent.id)
        assertEquals("Gromozeka", defaultAgent.name)
        assertTrue(defaultAgent.prompts.isNotEmpty())
        assertTrue(
            defaultAgent.prompts
                .filterNot { it.value == "env" }
                .all { promptId -> seed.prompts.any { it.id == promptId } }
        )
        assertTrue(seed.aiCatalog.modelConfigurations.isNotEmpty())
        assertTrue(seed.aiCatalog.runtimeAssignments.isNotEmpty())
    }
}
