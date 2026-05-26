package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.repository.PromptRepository
import com.gromozeka.infrastructure.db.persistence.tables.Prompts
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedPromptRepository(
    private val fileSystemPromptScanner: FileSystemPromptScanner,
    private val builtinPromptLoader: BuiltinPromptLoader
) : PromptRepository {

    private var cachedBuiltinPrompts: List<Prompt> = emptyList()

    override suspend fun findBuiltinById(id: Prompt.Id): Prompt? {
        val idValue = id.value

        findDynamicPrompt(id)?.let { return it }
        
        // Only works for builtin
        if (idValue.startsWith("builtin:")) {
            return cachedBuiltinPrompts.find { it.id == id }
        }
        
        return null
    }

    override suspend fun findById(id: Prompt.Id, project: Project): Prompt? {
        val idValue = id.value

        findDynamicPrompt(id)?.let { return it }

        // Handle builtin with project override
        if (idValue.startsWith("builtin:")) {
            val fileName = idValue.removePrefix("builtin:")

            // Check for project override first
            val projectOverride = Prompt.Id("project:$fileName")
            fileSystemPromptScanner.loadPromptById(projectOverride, project.path, logMissing = false)?.let {
                return it
            }

            // Fall back to builtin
            return cachedBuiltinPrompts.find { it.id == id }
        }

        // For global and project prompts, load from disk
        if (idValue.startsWith("global:") || idValue.startsWith("project:")) {
            return fileSystemPromptScanner.loadPromptById(id, project.path)
        }

        return null
    }

    override suspend fun findAll(): List<Prompt> {
        val globalPrompts = fileSystemPromptScanner.scanGlobalPrompts()
        val dynamicPrompts = findDynamicPrompts()
        
        // Note: Project prompts are NOT included in findAll() 
        // because we don't have project context here.
        // Project prompts are loaded explicitly via findById(id, projectPath)
        // when assembling system prompts for specific project.

        return (cachedBuiltinPrompts + globalPrompts + dynamicPrompts)
            .sortedBy { it.name }
    }

    override suspend fun findByType(type: Prompt.Type): List<Prompt> {
        return findAll().filter { it.type == type }
    }

    override suspend fun refresh() {
        cachedBuiltinPrompts = builtinPromptLoader.loadBuiltinPrompts()
    }

    override suspend fun count(): Int {
        return findAll().size
    }
    
    override suspend fun save(prompt: Prompt): Prompt {
        return when (prompt.type) {
            is Prompt.Type.Environment -> saveDynamicPrompt(prompt)
            else -> {
                throw UnsupportedOperationException(
                    "File-based prompts cannot be saved via repository. " +
                    "Use file system to create/edit prompts."
                )
            }
        }
    }

    @jakarta.annotation.PostConstruct
    fun initialize() {
        cachedBuiltinPrompts = builtinPromptLoader.loadBuiltinPrompts()
    }

    private suspend fun saveDynamicPrompt(prompt: Prompt): Prompt = dbQuery {
        val exists = Prompts.selectAll().where { Prompts.id eq prompt.id.value }.count() > 0
        if (exists) {
            Prompts.update({ Prompts.id eq prompt.id.value }) {
                it[name] = prompt.name
                it[content] = prompt.content
                it[sourceType] = ENVIRONMENT_TYPE
                it[sourcePath] = null
                it[updatedAt] = prompt.updatedAt.toKotlin()
            }
        } else {
            Prompts.insert {
                it[id] = prompt.id.value
                it[name] = prompt.name
                it[content] = prompt.content
                it[sourceType] = ENVIRONMENT_TYPE
                it[sourcePath] = null
                it[createdAt] = prompt.createdAt.toKotlin()
                it[updatedAt] = prompt.updatedAt.toKotlin()
            }
        }
        prompt
    }

    private suspend fun findDynamicPrompt(id: Prompt.Id): Prompt? = dbQuery {
        Prompts.selectAll()
            .where { Prompts.id eq id.value }
            .singleOrNull()
            ?.toPrompt()
    }

    private suspend fun findDynamicPrompts(): List<Prompt> = dbQuery {
        Prompts.selectAll()
            .map { it.toPrompt() }
    }

    private fun ResultRow.toPrompt(): Prompt {
        val type = when (this[Prompts.sourceType]) {
            ENVIRONMENT_TYPE -> Prompt.Type.Environment
            else -> error("Unsupported database-backed prompt type: ${this[Prompts.sourceType]}")
        }
        return Prompt(
            id = Prompt.Id(this[Prompts.id]),
            name = this[Prompts.name],
            content = this[Prompts.content],
            type = type,
            createdAt = this[Prompts.createdAt].toKotlinx(),
            updatedAt = this[Prompts.updatedAt].toKotlinx(),
        )
    }

    private companion object {
        const val ENVIRONMENT_TYPE = "environment"
    }
}
