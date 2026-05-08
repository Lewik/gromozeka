package com.gromozeka.infrastructure.db.persistence.mongo

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.infrastructure.db.persistence.BuiltinAgentLoader
import com.gromozeka.infrastructure.db.persistence.FileSystemAgentScanner
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.flow.toList
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Service
@Primary
class MongoAgentRepository(
    database: MongoDatabase,
    private val builtinAgentLoader: BuiltinAgentLoader,
    private val fileSystemAgentScanner: FileSystemAgentScanner,
) : AgentRepository {
    private val agents: MongoCollection<AgentDefinition> = database.getCollection("agents")
    private var cachedBuiltinAgents: List<AgentDefinition> = emptyList()
    private val indexes = MongoIndexInitializer {
        agents.createIndex(Indexes.ascending("id"), IndexOptions().unique(true))
    }

    override suspend fun save(agent: AgentDefinition): AgentDefinition {
        indexes.ensure()
        return when (agent.type) {
            is AgentDefinition.Type.Inline -> {
                agents.upsertByDomainId(agent.id.value, agent)
                agent
            }

            else -> throw UnsupportedOperationException(
                "File-based agents cannot be saved via repository. Use file system to create/edit agents.",
            )
        }
    }

    override suspend fun findById(id: AgentDefinition.Id): AgentDefinition? {
        indexes.ensure()
        agents.findByDomainId(id.value)?.let { return it }

        val idValue = id.value
        if (idValue.startsWith("builtin:")) {
            val fileName = idValue.removePrefix("builtin:")
            val projectPath = getProjectPath()
            if (projectPath != null) {
                val projectOverride = AgentDefinition.Id("project:$fileName")
                fileSystemAgentScanner.loadAgentById(projectOverride, projectPath, logMissing = false)?.let {
                    return it
                }
            }
            return cachedBuiltinAgents.find { it.id == id }
        }

        if (idValue.startsWith("global:") || idValue.startsWith("project:")) {
            return fileSystemAgentScanner.loadAgentById(id, getProjectPath())
        }

        return null
    }

    override suspend fun findAll(projectPath: String?): List<AgentDefinition> {
        indexes.ensure()
        val effectiveProjectPath = projectPath ?: getProjectPath()
        val globalAgents = fileSystemAgentScanner.scanGlobalAgents()
        val projectAgents = effectiveProjectPath?.let(fileSystemAgentScanner::scanProjectAgents).orEmpty()
        val persistedAgents = agents.find().toList()

        return (cachedBuiltinAgents + globalAgents + projectAgents + persistedAgents)
            .distinctBy { it.id }
            .sortedBy { it.name }
    }

    override suspend fun delete(id: AgentDefinition.Id) {
        indexes.ensure()
        val deleted = agents.deleteMany(Filters.eq("id", id.value)).deletedCount
        if (deleted == 0L) {
            throw UnsupportedOperationException(
                "File-based agents cannot be deleted via repository. Use file system to delete agents.",
            )
        }
    }

    override suspend fun count(): Int = findAll(null).size

    private fun getProjectPath(): String? =
        fileSystemAgentScanner.findProjectRoot()?.absolutePath

    @PostConstruct
    fun initialize() {
        cachedBuiltinAgents = builtinAgentLoader.loadBuiltinAgents()
    }
}
