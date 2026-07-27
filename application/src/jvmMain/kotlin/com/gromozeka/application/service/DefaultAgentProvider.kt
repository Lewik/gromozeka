package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.DefaultAgentProvider
import klog.KLoggers
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class DefaultAgentProvider(
    private val agentService: AgentDomainService,
    private val aiConfigurationProvider: AiConfigurationProvider,
) : DefaultAgentProvider {
    private val log = KLoggers.logger(this)

    override suspend fun getDefault(): AgentDefinition {
        val defaultAgentId = aiConfigurationProvider.catalog.defaultAgentId
        val defaultAgent = agentService.findById(defaultAgentId)
            ?: error("Default agent not found: ${defaultAgentId.value}")

        log.debug("Retrieved default agent: ${defaultAgent.name} (${defaultAgent.id.value})")
        return defaultAgent
    }

    @EventListener(ApplicationReadyEvent::class)
    fun validateDefaultAgent() {
        runBlocking {
            getDefault()
        }
    }
}
