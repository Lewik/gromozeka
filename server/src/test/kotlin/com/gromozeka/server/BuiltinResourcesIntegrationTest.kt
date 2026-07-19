package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.infrastructure.db.persistence.BuiltinAgentLoader
import com.gromozeka.infrastructure.db.persistence.BuiltinPromptLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltinResourcesIntegrationTest {
    @Test
    fun `server runtime contains the default agent and all of its prompts`() {
        val agents = BuiltinAgentLoader().loadBuiltinAgents()
        val prompts = BuiltinPromptLoader().loadBuiltinPrompts()
        val defaultAgent = agents.single {
            it.id == AgentDefinition.Id("builtin:default-gromozeka.agent.json")
        }

        assertEquals("Gromozeka", defaultAgent.name)
        assertTrue(defaultAgent.prompts.isNotEmpty())
        assertTrue(
            defaultAgent.prompts
                .filterNot { it.value == "env" }
                .all { promptId -> prompts.any { it.id == promptId } }
        )
    }
}
