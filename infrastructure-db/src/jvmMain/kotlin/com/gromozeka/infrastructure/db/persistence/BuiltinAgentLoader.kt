package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import klog.KLoggers
import kotlinx.serialization.json.Json
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component

@Component
class BuiltinAgentLoader {
    private val log = KLoggers.logger("BuiltinAgentLoader")
    private val resourceResolver = PathMatchingResourcePatternResolver()
    private val json = Json

    fun loadBuiltinAgents(): List<AgentDefinition> {
        val agents = mutableListOf<AgentDefinition>()
        
        try {
            val resources = resourceResolver.getResources("classpath:/agents/*.json")
            
            log.info { "Scanning for builtin agents: found ${resources.size} JSON files" }
            
            resources.forEach { resource ->
                try {
                    val fileName = resource.filename ?: return@forEach
                    val content = resource.inputStream.bufferedReader().use { it.readText() }
                    
                    log.debug { "Loading builtin agent from: $fileName" }
                    
                    val id = AgentDefinition.Id("builtin:$fileName")
                    val agent = json.decodeFromString<AgentDefinition>(content)
                        .validatedIdentity(id, AgentDefinition.Type.Builtin, fileName)
                    
                    agents.add(agent)
                    log.info { "Loaded builtin agent: ${agent.name} (${agent.id.value})" }
                } catch (e: Exception) {
                    log.error(e) { "Failed to load builtin agent: ${resource.filename}" }
                }
            }
            
            log.info { "Loaded ${agents.size} builtin agents from /agents directory" }
        } catch (e: Exception) {
            log.error(e) { "Failed to scan /agents directory for agents" }
        }

        return agents
    }

}
