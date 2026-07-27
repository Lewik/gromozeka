package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.repository.PromptRepository
import com.gromozeka.infrastructure.db.persistence.tables.Prompts
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedPromptRepository : PromptRepository {
    override suspend fun findById(id: Prompt.Id): Prompt? = dbQuery {
        Prompts.selectAll()
            .where { Prompts.id eq id.value }
            .singleOrNull()
            ?.toPrompt()
    }

    override suspend fun findAll(): List<Prompt> = dbQuery {
        Prompts.selectAll()
            .map { it.toPrompt() }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun findByProject(projectId: Project.Id): List<Prompt> =
        findAll().filter { it.type is Prompt.Type.Global || it.projectId == projectId }

    override suspend fun findByType(type: Prompt.Type): List<Prompt> =
        findAll().filter { it.type == type }

    override suspend fun count(): Int = dbQuery {
        Prompts.selectAll().count().toInt()
    }

    override suspend fun save(prompt: Prompt): Prompt = dbQuery {
        val existing = Prompts.selectAll()
            .where { Prompts.id eq prompt.id.value }
            .singleOrNull()

        if (existing == null) {
            Prompts.insert {
                it[id] = prompt.id.value
                it[projectId] = prompt.projectId?.value
                it[name] = prompt.name
                it[content] = prompt.content
                it[scope] = prompt.type.databaseValue()
                it[createdAt] = prompt.createdAt.toKotlin()
                it[updatedAt] = prompt.updatedAt.toKotlin()
            }
        } else {
            require(existing[Prompts.projectId] == prompt.projectId?.value) {
                "Prompt scope cannot be changed: ${prompt.id.value}"
            }
            Prompts.update({ Prompts.id eq prompt.id.value }) {
                it[name] = prompt.name
                it[content] = prompt.content
                it[scope] = prompt.type.databaseValue()
                it[updatedAt] = prompt.updatedAt.toKotlin()
            }
        }
        prompt
    }

    override suspend fun delete(id: Prompt.Id) {
        val deleted = dbQuery {
            Prompts.deleteWhere { Prompts.id eq id.value }
        }
        require(deleted > 0) { "Prompt not found: ${id.value}" }
    }

    private fun ResultRow.toPrompt(): Prompt {
        val type = when (this[Prompts.scope]) {
            GLOBAL_TYPE -> Prompt.Type.Global
            PROJECT_TYPE -> Prompt.Type.Project
            else -> error("Unsupported prompt scope: ${this[Prompts.scope]}")
        }
        return Prompt(
            id = Prompt.Id(this[Prompts.id]),
            projectId = this[Prompts.projectId]?.let(Project::Id),
            name = this[Prompts.name],
            content = this[Prompts.content],
            type = type,
            createdAt = this[Prompts.createdAt].toKotlinx(),
            updatedAt = this[Prompts.updatedAt].toKotlinx(),
        )
    }

    private fun Prompt.Type.databaseValue(): String =
        when (this) {
            Prompt.Type.Global -> GLOBAL_TYPE
            Prompt.Type.Project -> PROJECT_TYPE
        }

    private companion object {
        const val GLOBAL_TYPE = "global"
        const val PROJECT_TYPE = "project"
    }
}
