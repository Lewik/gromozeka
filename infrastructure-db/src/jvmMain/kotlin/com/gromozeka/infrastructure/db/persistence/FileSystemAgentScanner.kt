package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Prompt
import klog.KLoggers
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.io.File
import java.util.UUID

@Component
class FileSystemAgentScanner {
    private val log = KLoggers.logger("FileSystemAgentScanner")
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

    fun scanGlobalAgents(): List<AgentDefinition> {
        val gromozekaHome = System.getProperty("GROMOZEKA_HOME") 
            ?: throw IllegalStateException("GROMOZEKA_HOME system property not set")
        
        val agentsDir = File(gromozekaHome, "agents")
        
        log.info { "Scanning GLOBAL agents from: ${agentsDir.absolutePath}" }
        
        if (!agentsDir.exists() || !agentsDir.isDirectory) {
            log.warn { "Global agents directory does not exist: ${agentsDir.absolutePath}" }
            return emptyList()
        }

        return scanAgentsFromDirectory(agentsDir, isGlobal = true)
    }
    
    fun scanProjectAgents(projectPath: String): List<AgentDefinition> {
        val agentsDir = File(projectPath, ".gromozeka/agents")
        
        log.info { "Scanning PROJECT agents from: ${agentsDir.absolutePath}" }
        
        if (!agentsDir.exists() || !agentsDir.isDirectory) {
            log.warn { "Project agents directory does not exist: ${agentsDir.absolutePath}" }
            return emptyList()
        }

        return scanAgentsFromDirectory(agentsDir, isGlobal = false)
    }
    
    fun scanProjectAgents(): List<AgentDefinition> {
        val projectRoot = findProjectRoot()
        
        if (projectRoot == null) {
            log.warn { "Could not find project root with .gromozeka directory" }
            return emptyList()
        }
        
        log.info { "Found project root: ${projectRoot.absolutePath}" }
        return scanProjectAgents(projectRoot.absolutePath)
    }
    
    fun findProjectRoot(): File? {
        val explicitProjectRoot = System.getProperty("gromozeka.project.root")
            ?.let(::File)
            ?.takeIf { root ->
                File(root, ".gromozeka").isDirectory
            }

        if (explicitProjectRoot != null) {
            log.info { "Using configured project root: ${explicitProjectRoot.absolutePath}" }
            return explicitProjectRoot
        }

        var current = File(System.getProperty("user.dir"))
        
        log.debug { "Searching for project root starting from: ${current.absolutePath}" }
        
        while (current != null) {
            val gromozekaDir = File(current, ".gromozeka")
            
            log.debug { "Checking: ${current.absolutePath} -> .gromozeka exists: ${gromozekaDir.exists()}" }
            
            if (gromozekaDir.exists() && gromozekaDir.isDirectory) {
                log.info { "Found .gromozeka directory at: ${current.absolutePath}" }
                return current
            }
            
            current = current.parentFile
        }
        
        log.warn { "Could not find .gromozeka directory in any parent directories" }
        return null
    }
    
    private fun scanAgentsFromDirectory(
        directory: File,
        isGlobal: Boolean
    ): List<AgentDefinition> {
        val agentFiles = directory.listFiles { file -> 
            file.isFile && file.extension == "json"
        } ?: emptyArray()

        log.info { "Found ${agentFiles.size} JSON files in ${directory.absolutePath}" }

        val agents = agentFiles.mapNotNull { file ->
            try {
                log.debug { "Loading agent from: ${file.name}" }
                
                val content = file.readText()
                val dto = json.decodeFromString<AgentDTO>(content)
                
                val id = if (isGlobal) {
                    AgentDefinition.Id("global:${file.name}")
                } else {
                    AgentDefinition.Id("project:${file.name}")
                }
                val type = if (isGlobal) AgentDefinition.Type.Global else AgentDefinition.Type.Project
                
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
                    type = type,
                    createdAt = dto.createdAt,
                    updatedAt = dto.updatedAt
                )
                
                log.info { "Loaded ${if (isGlobal) "GLOBAL" else "PROJECT"} agent: ${agent.name} (${agent.id.value})" }
                agent
            } catch (e: Exception) {
                log.error(e) { "Failed to read agent file: ${file.absolutePath}" }
                null
            }
        }

        val typeName = if (isGlobal) "GLOBAL" else "PROJECT"
        log.info { "Scanned ${agents.size} $typeName agents from ${directory.absolutePath}" }
        return agents
    }

    /**
     * Loads a single agent by ID from filesystem.
     * Supports global:filename and project:filename formats.
     */
    fun loadAgentById(id: AgentDefinition.Id, projectPath: String?, logMissing: Boolean = true): AgentDefinition? {
        val idValue = id.value

        return when {
            idValue.startsWith("global:") -> {
                val fileName = idValue.removePrefix("global:")
                val gromozekaHome = System.getProperty("GROMOZEKA_HOME")
                    ?: throw IllegalStateException("GROMOZEKA_HOME system property not set")

                val file = File(gromozekaHome, "agents/$fileName")

                if (!file.exists() || !file.isFile) {
                    if (logMissing) {
                        log.warn { "Global agent file not found: ${file.absolutePath}" }
                    }
                    return null
                }

                try {
                    val content = file.readText()
                    val dto = json.decodeFromString<AgentDTO>(content)

                    AgentDefinition(
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
                        type = AgentDefinition.Type.Global,
                        createdAt = dto.createdAt,
                        updatedAt = dto.updatedAt
                    )
                } catch (e: Exception) {
                    log.error(e) { "Failed to load global agent: ${file.absolutePath}" }
                    null
                }
            }

            idValue.startsWith("project:") -> {
                if (projectPath == null) {
                    log.warn { "Project path not provided for project agent: $idValue" }
                    return null
                }

                val fileName = idValue.removePrefix("project:")
                val file = File(projectPath, ".gromozeka/agents/$fileName")

                if (!file.exists() || !file.isFile) {
                    if (logMissing) {
                        log.warn { "Project agent file not found: ${file.absolutePath}" }
                    }
                    return null
                }

                try {
                    val content = file.readText()
                    val dto = json.decodeFromString<AgentDTO>(content)

                    AgentDefinition(
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
                        type = AgentDefinition.Type.Project,
                        createdAt = dto.createdAt,
                        updatedAt = dto.updatedAt
                    )
                } catch (e: Exception) {
                    log.error(e) { "Failed to load project agent: ${file.absolutePath}" }
                    null
                }
            }

            else -> null
        }
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
