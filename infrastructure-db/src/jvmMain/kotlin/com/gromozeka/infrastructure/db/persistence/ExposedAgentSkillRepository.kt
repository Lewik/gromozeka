package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillFile
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.AgentSkillRepository
import com.gromozeka.infrastructure.db.persistence.tables.AgentSkillFiles
import com.gromozeka.infrastructure.db.persistence.tables.AgentSkills
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedAgentSkillRepository(
    private val json: Json,
) : AgentSkillRepository {

    override suspend fun savePackage(skillPackage: AgentSkillPackage): AgentSkillPackage = dbQuery {
        val skill = skillPackage.skill
        val existing = AgentSkills.selectAll()
            .where { AgentSkills.id eq skill.id.value }
            .singleOrNull()
        if (existing == null) {
            AgentSkills.insert {
                it[id] = skill.id.value
                it[projectId] = skill.projectId.value
                it[name] = skill.name
                it[description] = skill.description
                it[instructions] = skill.instructions
                it[license] = skill.license
                it[compatibility] = skill.compatibility
                it[metadataJson] = json.encodeToString(skill.metadata)
                it[allowedTools] = skill.allowedTools
                it[contentHash] = skill.contentHash
                it[createdAt] = skill.createdAt.toKotlin()
                it[updatedAt] = skill.updatedAt.toKotlin()
            }
        } else {
            require(existing[AgentSkills.projectId] == skill.projectId.value) {
                "Agent skill ${skill.id.value} belongs to another project"
            }
            AgentSkills.update({ AgentSkills.id eq skill.id.value }) {
                it[name] = skill.name
                it[description] = skill.description
                it[instructions] = skill.instructions
                it[license] = skill.license
                it[compatibility] = skill.compatibility
                it[metadataJson] = json.encodeToString(skill.metadata)
                it[allowedTools] = skill.allowedTools
                it[contentHash] = skill.contentHash
                it[updatedAt] = skill.updatedAt.toKotlin()
            }
        }

        AgentSkillFiles.deleteWhere { AgentSkillFiles.skillId eq skill.id.value }
        skillPackage.files.forEach { file ->
            AgentSkillFiles.insert {
                it[skillId] = skill.id.value
                it[path] = file.path
                it[content] = file.content
            }
        }
        skillPackage
    }

    override suspend fun findById(id: AgentSkill.Id): AgentSkill? = dbQuery {
        AgentSkills.selectAll()
            .where { AgentSkills.id eq id.value }
            .singleOrNull()
            ?.toAgentSkill()
    }

    override suspend fun findByIds(ids: List<AgentSkill.Id>): List<AgentSkill> {
        if (ids.isEmpty()) {
            return emptyList()
        }
        val foundById = dbQuery {
            AgentSkills.selectAll()
                .where { AgentSkills.id inList ids.map(AgentSkill.Id::value) }
                .map { it.toAgentSkill() }
                .associateBy { it.id }
        }
        return ids.mapNotNull(foundById::get)
    }

    override suspend fun findByName(projectId: Project.Id, name: String): AgentSkill? = dbQuery {
        AgentSkills.selectAll()
            .where {
                (AgentSkills.projectId eq projectId.value) and
                    (AgentSkills.name eq name)
            }
            .singleOrNull()
            ?.toAgentSkill()
    }

    override suspend fun findByProject(projectId: Project.Id): List<AgentSkill> = dbQuery {
        AgentSkills.selectAll()
            .where { AgentSkills.projectId eq projectId.value }
            .map { it.toAgentSkill() }
            .sortedBy { it.name }
    }

    override suspend fun findPackage(id: AgentSkill.Id): AgentSkillPackage? = dbQuery {
        val skill = AgentSkills.selectAll()
            .where { AgentSkills.id eq id.value }
            .singleOrNull()
            ?.toAgentSkill()
            ?: return@dbQuery null
        val files = AgentSkillFiles.selectAll()
            .where { AgentSkillFiles.skillId eq id.value }
            .map {
                AgentSkillFile(
                    path = it[AgentSkillFiles.path],
                    content = it[AgentSkillFiles.content],
                )
            }
            .sortedBy { it.path }
        AgentSkillPackage(skill, files)
    }

    override suspend fun delete(id: AgentSkill.Id) {
        val deleted = dbQuery {
            AgentSkills.deleteWhere { AgentSkills.id eq id.value }
        }
        require(deleted > 0) { "Agent skill not found: ${id.value}" }
    }

    private fun ResultRow.toAgentSkill(): AgentSkill =
        AgentSkill(
            id = AgentSkill.Id(this[AgentSkills.id]),
            projectId = Project.Id(this[AgentSkills.projectId]),
            name = this[AgentSkills.name],
            description = this[AgentSkills.description],
            instructions = this[AgentSkills.instructions],
            license = this[AgentSkills.license],
            compatibility = this[AgentSkills.compatibility],
            metadata = json.decodeFromString(this[AgentSkills.metadataJson]),
            allowedTools = this[AgentSkills.allowedTools],
            contentHash = this[AgentSkills.contentHash],
            createdAt = this[AgentSkills.createdAt].toKotlinx(),
            updatedAt = this[AgentSkills.updatedAt].toKotlinx(),
        )
}
