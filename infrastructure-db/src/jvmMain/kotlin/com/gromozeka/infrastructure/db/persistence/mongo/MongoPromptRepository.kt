package com.gromozeka.infrastructure.db.persistence.mongo

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.repository.PromptRepository
import com.gromozeka.infrastructure.db.persistence.BuiltinPromptLoader
import com.gromozeka.infrastructure.db.persistence.FileSystemPromptScanner
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
class MongoPromptRepository(
    database: MongoDatabase,
    private val fileSystemPromptScanner: FileSystemPromptScanner,
    private val builtinPromptLoader: BuiltinPromptLoader,
) : PromptRepository {
    private val prompts: MongoCollection<Prompt> = database.getCollection("prompts")
    private var cachedBuiltinPrompts: List<Prompt> = emptyList()
    private val indexes = MongoIndexInitializer {
        prompts.createIndex(Indexes.ascending("id"), IndexOptions().unique(true))
    }

    override suspend fun findBuiltinById(id: Prompt.Id): Prompt? {
        indexes.ensure()
        prompts.findByDomainId(id.value)?.let { return it }

        return if (id.value.startsWith("builtin:")) {
            cachedBuiltinPrompts.find { it.id == id }
        } else {
            null
        }
    }

    override suspend fun findById(id: Prompt.Id, project: Project): Prompt? {
        indexes.ensure()
        prompts.findByDomainId(id.value)?.let { return it }

        val idValue = id.value
        if (idValue.startsWith("builtin:")) {
            val fileName = idValue.removePrefix("builtin:")
            val projectOverride = Prompt.Id("project:$fileName")
            fileSystemPromptScanner.loadPromptById(projectOverride, project.path, logMissing = false)?.let {
                return it
            }
            return cachedBuiltinPrompts.find { it.id == id }
        }

        if (idValue.startsWith("global:") || idValue.startsWith("project:")) {
            return fileSystemPromptScanner.loadPromptById(id, project.path)
        }

        return null
    }

    override suspend fun findAll(): List<Prompt> {
        indexes.ensure()
        val globalPrompts = fileSystemPromptScanner.scanGlobalPrompts()
        val persistedPrompts = prompts.find().toList()
        return (cachedBuiltinPrompts + globalPrompts + persistedPrompts)
            .distinctBy { it.id }
            .sortedBy { it.name }
    }

    override suspend fun findByType(type: Prompt.Type): List<Prompt> =
        findAll().filter { it.type == type }

    override suspend fun refresh() {
        cachedBuiltinPrompts = builtinPromptLoader.loadBuiltinPrompts()
    }

    override suspend fun count(): Int = findAll().size

    override suspend fun save(prompt: Prompt): Prompt {
        indexes.ensure()
        return when (prompt.type) {
            is Prompt.Type.Environment -> {
                prompts.upsertByDomainId(prompt.id.value, prompt)
                prompt
            }

            else -> throw UnsupportedOperationException(
                "File-based prompts cannot be saved via repository. Use file system to create/edit prompts.",
            )
        }
    }

    @PostConstruct
    fun initialize() {
        cachedBuiltinPrompts = builtinPromptLoader.loadBuiltinPrompts()
    }
}
