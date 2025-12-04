package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Agent
import com.gromozeka.domain.repository.AgentRepository
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ExposedAgentRepository(
    private val builtinAgentLoader: BuiltinAgentLoader,
    private val fileSystemAgentScanner: FileSystemAgentScanner
) : AgentRepository {

    private var cachedBuiltinAgents: List<Agent> = emptyList()

    // In-memory cache for inline agents (created dynamically via MCP)
    private val inlineAgentsCache = ConcurrentHashMap<Agent.Id, Agent>()

    private fun getProjectPath(): String? {
        return fileSystemAgentScanner.findProjectRoot()?.absolutePath
    }

    override suspend fun save(agent: Agent): Agent {
        return when (agent.type) {
            is Agent.Type.Inline -> {
                // Store inline agents in memory cache
                inlineAgentsCache[agent.id] = agent
                agent
            }
            else -> {
                throw UnsupportedOperationException(
                    "File-based agents cannot be saved via repository. " +
                    "Use file system to create/edit agents."
                )
            }
        }
    }

    override suspend fun findById(id: Agent.Id): Agent? {
        val idValue = id.value

        // Check inline cache first
        inlineAgentsCache[id]?.let { return it }

        // Handle builtin with project override
        if (idValue.startsWith("builtin:")) {
            val fileName = idValue.removePrefix("builtin:")
            val projectPath = getProjectPath()

            // Check for project override first
            if (projectPath != null) {
                val projectOverride = Agent.Id("project:$fileName")
                fileSystemAgentScanner.loadAgentById(projectOverride, projectPath)?.let {
                    return it
                }
            }

            // Fall back to builtin
            return cachedBuiltinAgents.find { it.id == id }
        }

        // For global and project agents, load from disk
        if (idValue.startsWith("global:") || idValue.startsWith("project:")) {
            val projectPath = getProjectPath()
            return fileSystemAgentScanner.loadAgentById(id, projectPath)
        }

        return null
    }

    override suspend fun findAll(projectPath: String?): List<Agent> {
        val effectiveProjectPath = projectPath ?: getProjectPath()

        val globalAgents = fileSystemAgentScanner.scanGlobalAgents()
        val projectAgents = if (effectiveProjectPath != null) {
            fileSystemAgentScanner.scanProjectAgents(effectiveProjectPath)
        } else {
            emptyList()
        }

        return (cachedBuiltinAgents + globalAgents + projectAgents + inlineAgentsCache.values)
            .sortedBy { it.name }
    }

    override suspend fun delete(id: Agent.Id) {
        // Check if it's an inline agent
        if (inlineAgentsCache.containsKey(id)) {
            inlineAgentsCache.remove(id)
            return
        }
        
        throw UnsupportedOperationException(
            "File-based agents cannot be deleted via repository. " +
            "Use file system to delete agents."
        )
    }

    override suspend fun count(): Int {
        return findAll(null).size
    }

    @jakarta.annotation.PostConstruct
    fun initialize() {
        cachedBuiltinAgents = builtinAgentLoader.loadBuiltinAgents()
    }
}
