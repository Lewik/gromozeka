package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Project
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

    override suspend fun findById(id: Prompt.Id): Prompt? {
        val idValue = id.value

        findDynamicPrompt(id)?.let { return it }

        if (idValue.startsWith("builtin:")) {
            return cachedBuiltinPrompts.find { it.id == id }
        }

        return null
    }

    override suspend fun findAll(): List<Prompt> {
        val dynamicPrompts = findDynamicPrompts()
        return (cachedBuiltinPrompts + dynamicPrompts)
            .sortedBy { it.name }
    }

    override suspend fun findByProject(projectId: Project.Id): List<Prompt> =
        (cachedBuiltinPrompts + findDynamicPrompts(projectId))
            .sortedBy { it.name }

    override suspend fun findByType(type: Prompt.Type): List<Prompt> {
        return findAll().filter { it.type == type }
    }

    override suspend fun count(): Int {
        return findAll().size
    }
    
    override suspend fun save(prompt: Prompt): Prompt {
        return when (prompt.type) {
            is Prompt.Type.Project -> saveDynamicPrompt(prompt)
            is Prompt.Type.Builtin ->
                throw UnsupportedOperationException("Builtin prompts are immutable")
        }
    }

    @jakarta.annotation.PostConstruct
    fun initialize() {
        cachedBuiltinPrompts = builtinPromptLoader.loadBuiltinPrompts()
    }

    private suspend fun saveDynamicPrompt(prompt: Prompt): Prompt = dbQuery {
        val projectId = checkNotNull(prompt.projectId) { "Project prompt must have projectId" }
        val existing = Prompts.selectAll()
            .where { Prompts.id eq prompt.id.value }
            .singleOrNull()
        if (existing != null) {
            require(existing[Prompts.projectId] == projectId.value) {
                "Prompt ${prompt.id.value} belongs to another project"
            }
            Prompts.update({ Prompts.id eq prompt.id.value }) {
                it[name] = prompt.name
                it[content] = prompt.content
                it[sourceType] = PROJECT_TYPE
                it[sourcePath] = null
                it[updatedAt] = prompt.updatedAt.toKotlin()
            }
        } else {
            Prompts.insert {
                it[id] = prompt.id.value
                it[Prompts.projectId] = projectId.value
                it[name] = prompt.name
                it[content] = prompt.content
                it[sourceType] = PROJECT_TYPE
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

    private suspend fun findDynamicPrompts(projectId: Project.Id): List<Prompt> = dbQuery {
        Prompts.selectAll()
            .where { Prompts.projectId eq projectId.value }
            .map { it.toPrompt() }
    }

    private fun ResultRow.toPrompt(): Prompt {
        val type = when (this[Prompts.sourceType]) {
            PROJECT_TYPE -> Prompt.Type.Project
            else -> error("Unsupported database-backed prompt type: ${this[Prompts.sourceType]}")
        }
        return Prompt(
            id = Prompt.Id(this[Prompts.id]),
            projectId = Project.Id(this[Prompts.projectId]),
            name = this[Prompts.name],
            content = this[Prompts.content],
            type = type,
            createdAt = this[Prompts.createdAt].toKotlinx(),
            updatedAt = this[Prompts.updatedAt].toKotlinx(),
        )
    }

    private companion object {
        const val PROJECT_TYPE = "project"
    }
}
