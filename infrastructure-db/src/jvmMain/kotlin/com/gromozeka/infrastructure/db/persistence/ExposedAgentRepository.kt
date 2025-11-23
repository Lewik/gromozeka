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
    private var cachedGlobalAgents: List<Agent> = emptyList()
    
    // In-memory cache for inline agents (created dynamically via MCP)
    private val inlineAgentsCache = ConcurrentHashMap<Agent.Id, Agent>()
    
    // Lazy cache for PROJECT agents per project path
    private val projectAgentsCache = ConcurrentHashMap<String, List<Agent>>()

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
        // Check inline cache first
        inlineAgentsCache[id]?.let { return it }
        
        // Check builtin and global
        (cachedBuiltinAgents + cachedGlobalAgents).find { it.id == id }?.let { return it }
        
        // Check all project caches
        projectAgentsCache.values.flatten().find { it.id == id }?.let { return it }
        
        return null
    }

    override suspend fun findAll(projectPath: String?): List<Agent> {
        val projectAgents = projectPath?.let { path ->
            projectAgentsCache.getOrPut(path) {
                fileSystemAgentScanner.scanProjectAgents(path)
            }
        } ?: emptyList()
        
        return (cachedBuiltinAgents + cachedGlobalAgents + projectAgents + inlineAgentsCache.values)
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
        return cachedBuiltinAgents.size + 
               cachedGlobalAgents.size + 
               projectAgentsCache.values.flatten().size + 
               inlineAgentsCache.size
    }
    
    @jakarta.annotation.PostConstruct
    fun initialize() {
        cachedBuiltinAgents = builtinAgentLoader.loadBuiltinAgents()
        cachedGlobalAgents = fileSystemAgentScanner.scanGlobalAgents()
        // PROJECT agents are loaded lazily in findAll(projectPath)
    }
}
