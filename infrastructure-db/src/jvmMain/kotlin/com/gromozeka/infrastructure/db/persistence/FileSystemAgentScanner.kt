package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import klog.KLoggers
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.io.File

@Component
class FileSystemAgentScanner {
    private val log = KLoggers.logger("FileSystemAgentScanner")
    private val json = Json

    fun scanGlobalAgents(): List<AgentDefinition> {
        val gromozekaHome = System.getProperty("GROMOZEKA_HOME") 
            ?: throw IllegalStateException("GROMOZEKA_HOME system property not set")
        
        val agentsDir = File(gromozekaHome, "agents")
        
        log.info { "Scanning GLOBAL agents from: ${agentsDir.absolutePath}" }
        
        if (!agentsDir.exists() || !agentsDir.isDirectory) {
            log.warn { "Global agents directory does not exist: ${agentsDir.absolutePath}" }
            return emptyList()
        }

        return scanAgentsFromDirectory(agentsDir)
    }
    
    private fun scanAgentsFromDirectory(
        directory: File,
    ): List<AgentDefinition> {
        val agentFiles = directory.listFiles { file -> 
            file.isFile && file.extension == "json"
        } ?: emptyArray()

        log.info { "Found ${agentFiles.size} JSON files in ${directory.absolutePath}" }

        val agents = agentFiles.mapNotNull { file ->
            try {
                log.debug { "Loading agent from: ${file.name}" }
                
                val content = file.readText()
                val id = AgentDefinition.Id("global:${file.name}")
                
                val agent = json.decodeFromString<AgentDefinition>(content)
                    .validatedIdentity(id, AgentDefinition.Type.Global, file.absolutePath)
                
                log.info { "Loaded GLOBAL agent: ${agent.name} (${agent.id.value})" }
                agent
            } catch (e: Exception) {
                log.error(e) { "Failed to read agent file: ${file.absolutePath}" }
                null
            }
        }

        log.info { "Scanned ${agents.size} GLOBAL agents from ${directory.absolutePath}" }
        return agents
    }

    fun loadGlobalAgentById(id: AgentDefinition.Id): AgentDefinition? {
        val idValue = id.value
        require(idValue.startsWith("global:")) {
            "Global agent id must start with 'global:': $idValue"
        }
        val fileName = idValue.removePrefix("global:")
        val gromozekaHome = System.getProperty("GROMOZEKA_HOME")
            ?: throw IllegalStateException("GROMOZEKA_HOME system property not set")
        val file = File(gromozekaHome, "agents/$fileName")
        if (!file.isFile) {
            log.warn { "Global agent file not found: ${file.absolutePath}" }
            return null
        }
        return try {
            json.decodeFromString<AgentDefinition>(file.readText())
                .validatedIdentity(id, AgentDefinition.Type.Global, file.absolutePath)
        } catch (error: Exception) {
            log.error(error) { "Failed to load global agent: ${file.absolutePath}" }
            null
        }
    }
}
