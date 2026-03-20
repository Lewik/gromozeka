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
        val modelName: String = "claude-sonnet-4-6",
        val maxTokens: Int? = null,
        val thinking: ThinkingConfigDTO? = null,
        val outputConfig: OutputConfigDTO? = null,
        val tools: List<String> = emptyList(),
        val description: String? = null,
        val createdAt: Instant,
        val updatedAt: Instant
    )

    @Serializable
    private data class ThinkingConfigDTO(
        val type: String = "adaptive",
        val budgetTokens: Int? = null,
        val display: String = "full"
    )

    @Serializable
    private data class OutputConfigDTO(
        val effort: String = "high"
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
                        maxTokens = dto.maxTokens ?: getDefaultMaxTokens(dto.modelName),
                        thinking = dto.thinking?.let {
                            AgentDefinition.ThinkingConfig(
                                type = it.type,
                                budgetTokens = it.budgetTokens,
                                display = it.display
                            )
                        } ?: getDefaultThinking(dto.modelName),
                        outputConfig = dto.outputConfig?.let {
                            AgentDefinition.OutputConfig(effort = it.effort)
                        } ?: getDefaultOutputConfig(dto.modelName),
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

    private fun getDefaultMaxTokens(modelName: String): Int? {
        return when {
            modelName.contains("opus-4-6") -> 128_000
            modelName.contains("sonnet-4-6") -> 64_000
            else -> null
        }
    }

    private fun getDefaultThinking(modelName: String): AgentDefinition.ThinkingConfig? {
        return when {
            modelName.contains("claude") && modelName.contains("4-6") -> 
                AgentDefinition.ThinkingConfig(type = "adaptive", display = "full")
            modelName.contains("claude") -> 
                AgentDefinition.ThinkingConfig(type = "enabled", budgetTokens = 16_000, display = "full")
            else -> null
        }
    }

    private fun getDefaultOutputConfig(modelName: String): AgentDefinition.OutputConfig? {
        return when {
            modelName.contains("opus-4-6") -> 
                AgentDefinition.OutputConfig(effort = "max")
            modelName.contains("sonnet-4-6") || modelName.contains("claude-4") -> 
                AgentDefinition.OutputConfig(effort = "high")
            else -> null
        }
    }

}
