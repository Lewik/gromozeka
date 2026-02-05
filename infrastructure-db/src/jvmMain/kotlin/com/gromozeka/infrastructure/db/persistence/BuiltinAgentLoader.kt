package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Prompt
import klog.KLoggers
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class BuiltinAgentLoader {
    private val log = KLoggers.logger("BuiltinAgentLoader")
    private val resourceResolver = PathMatchingResourcePatternResolver()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class AgentDTO(
        val id: String,
        val name: String,
        val prompts: List<String>,
        val aiProvider: String = "ANTHROPIC",
        val modelName: String = "claude-haiku-4-5-20251001",
        val tools: List<String> = emptyList(),
        val description: String? = null,
        val createdAt: Instant,
        val updatedAt: Instant
    )

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
                    
                    val dto = json.decodeFromString<AgentDTO>(content)
                    
                    val id = AgentDefinition.Id("builtin:$fileName")
                    
                    val agent = AgentDefinition(
                        id = id,
                        name = dto.name,
                        prompts = dto.prompts.map { Prompt.Id(it) },
                        aiProvider = dto.aiProvider,
                        modelName = dto.modelName,
                        tools = dto.tools,
                        description = dto.description,
                        type = AgentDefinition.Type.Builtin,
                        createdAt = dto.createdAt,
                        updatedAt = dto.updatedAt
                    )
                    
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
