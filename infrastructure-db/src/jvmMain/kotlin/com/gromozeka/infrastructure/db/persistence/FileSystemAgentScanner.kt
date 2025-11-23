package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Agent
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
        val description: String? = null,
        val createdAt: Instant,
        val updatedAt: Instant
    )

    fun scanGlobalAgents(): List<Agent> {
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
    
    fun scanProjectAgents(projectPath: String): List<Agent> {
        val agentsDir = File(projectPath, ".gromozeka/agents")
        
        log.info { "Scanning PROJECT agents from: ${agentsDir.absolutePath}" }
        
        if (!agentsDir.exists() || !agentsDir.isDirectory) {
            log.warn { "Project agents directory does not exist: ${agentsDir.absolutePath}" }
            return emptyList()
        }

        return scanAgentsFromDirectory(agentsDir, isGlobal = false)
    }
    
    fun scanProjectAgents(): List<Agent> {
        val projectRoot = findProjectRoot()
        
        if (projectRoot == null) {
            log.warn { "Could not find project root with .gromozeka directory" }
            return emptyList()
        }
        
        log.info { "Found project root: ${projectRoot.absolutePath}" }
        return scanProjectAgents(projectRoot.absolutePath)
    }
    
    private fun findProjectRoot(): File? {
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
    ): List<Agent> {
        val agentFiles = directory.listFiles { file -> 
            file.isFile && file.extension == "json"
        } ?: emptyArray()

        log.info { "Found ${agentFiles.size} JSON files in ${directory.absolutePath}" }

        val agents = agentFiles.mapNotNull { file ->
            try {
                log.debug { "Loading agent from: ${file.name}" }
                
                val content = file.readText()
                val dto = json.decodeFromString<AgentDTO>(content)
                
                // Generate ID and type from path
                val relativePath = if (isGlobal) {
                    "agents/${file.name}"
                } else {
                    ".gromozeka/agents/${file.name}"
                }
                
                val id = Agent.Id(relativePath)  // ID = relative path
                val type = if (isGlobal) Agent.Type.Global else Agent.Type.Project
                
                val agent = Agent(
                    id = id,
                    name = dto.name,
                    prompts = dto.prompts.map { Prompt.Id(it) },
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

}
