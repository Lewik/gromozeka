package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.RuntimeEnvironmentContext
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

    override suspend fun findById(id: Prompt.Id, runtimeContext: RuntimeEnvironmentContext): Prompt? {
        val idValue = id.value

        findDynamicPrompt(id)?.let { return it }

        if (idValue.startsWith("builtin:")) {
            return cachedBuiltinPrompts.find { it.id == id }
        }

        if (idValue.startsWith("global:")) {
            return fileSystemPromptScanner.loadGlobalPromptById(id)
        }

        if (idValue.startsWith("workspace:")) {
            val workspaceContext = runtimeContext as? RuntimeEnvironmentContext.WorkspaceBound
                ?: error(
                    "Workspace prompt '${id.value}' cannot be loaded outside a workspace-bound runtime"
                )
            val workspaceRootPath = workspaceContext.workspaceRootPath
                ?: error(
                    "Workspace prompt '${id.value}' requires workspace " +
                        "${workspaceContext.workspace.id.value} to be mounted on worker ${workspaceContext.workerId}"
                )
            return fileSystemPromptScanner.loadWorkspacePromptById(id, workspaceRootPath)
        }

        return null
    }

    override suspend fun findAll(): List<Prompt> {
        val globalPrompts = fileSystemPromptScanner.scanGlobalPrompts()
        val dynamicPrompts = findDynamicPrompts()
        
        // Workspace prompts require a resolved mount and are loaded during runtime assembly.

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
