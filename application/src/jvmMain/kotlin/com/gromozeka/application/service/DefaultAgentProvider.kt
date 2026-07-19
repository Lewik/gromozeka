package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.DefaultAgentProvider
import klog.KLoggers
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class DefaultAgentProvider(
    private val agentService: AgentDomainService
) : DefaultAgentProvider {
    private val log = KLoggers.logger(this)

    override suspend fun getDefault(): AgentDefinition {
        val defaultAgent = agentService.findById(DEFAULT_AGENT_ID)
            ?: error("Default agent not found: ${DEFAULT_AGENT_ID.value}")

        log.debug("Retrieved default agent: ${defaultAgent.name} (${defaultAgent.id.value})")
        return defaultAgent
    }

    @EventListener(ApplicationReadyEvent::class)
    fun validateDefaultAgent() {
        runBlocking {
            getDefault()
        }
    }

    private companion object {
        val DEFAULT_AGENT_ID = AgentDefinition.Id("builtin:default-gromozeka.agent.json")
    }
}
