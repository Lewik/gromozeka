package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentCatalogImportProposal
import com.gromozeka.domain.model.AgentCatalogImportResult
import com.gromozeka.domain.model.AgentCatalogSourceAgent
import com.gromozeka.domain.model.AgentCatalogSourcePrompt
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.repository.AgentCatalogImportRepository
import com.gromozeka.infrastructure.db.persistence.tables.AgentCatalogImportProposals
import com.gromozeka.infrastructure.db.persistence.tables.Agents
import com.gromozeka.infrastructure.db.persistence.tables.Prompts
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedAgentCatalogImportRepository(
    private val json: Json,
) : AgentCatalogImportRepository {

    override suspend fun report(proposal: AgentCatalogImportProposal): AgentCatalogImportProposal = dbQuery {
        require(proposal.status == AgentCatalogImportProposal.Status.PENDING) {
            "A detected catalog must be pending"
        }
        val existing = findRow(proposal.workspaceId, proposal.workerId)
        if (
            existing != null &&
            existing[AgentCatalogImportProposals.catalogHash] == proposal.catalogHash &&
            existing[AgentCatalogImportProposals.projectId] == proposal.projectId.value &&
            existing[AgentCatalogImportProposals.workspaceName] == proposal.workspaceName &&
            (
                existing[AgentCatalogImportProposals.status] != PENDING ||
                    existing[AgentCatalogImportProposals.validationError] == proposal.validationError
            )
        ) {
            return@dbQuery existing.toProposal()
        }

        if (existing == null) {
            AgentCatalogImportProposals.insert {
                it.writeProposal(proposal)
            }
        } else {
            AgentCatalogImportProposals.update({
                sourceMatches(proposal.workspaceId, proposal.workerId)
            }) {
                it.writeProposal(proposal)
            }
        }
        proposal
    }

    override suspend fun find(
        workspaceId: Workspace.Id,
        workerId: String,
    ): AgentCatalogImportProposal? = dbQuery {
        findRow(workspaceId, workerId)?.toProposal()
    }

    override suspend fun findPending(projectId: Project.Id): List<AgentCatalogImportProposal> = dbQuery {
        AgentCatalogImportProposals.selectAll()
            .where {
                (AgentCatalogImportProposals.projectId eq projectId.value) and
                    (AgentCatalogImportProposals.status eq PENDING)
            }
            .orderBy(AgentCatalogImportProposals.detectedAt)
            .map { it.toProposal() }
    }

    override suspend fun import(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
        prompts: List<Prompt>,
        agents: List<AgentDefinition>,
        decidedAt: Instant,
    ): AgentCatalogImportResult = dbQuery {
        val claimed = AgentCatalogImportProposals.update({
            sourceMatches(workspaceId, workerId) and
                (AgentCatalogImportProposals.catalogHash eq catalogHash) and
                (AgentCatalogImportProposals.status eq PENDING)
        }) {
            it[status] = IMPORTED
            it[AgentCatalogImportProposals.decidedAt] = decidedAt.toKotlin()
        }
        check(claimed == 1) { "Agent catalog proposal is missing, stale, or already decided" }

        prompts.forEach(::upsertPrompt)
        agents.forEach(::upsertAgent)
        AgentCatalogImportResult(
            importedPrompts = prompts.size,
            importedAgents = agents.size,
        )
    }

    override suspend fun skip(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
        decidedAt: Instant,
    ): Unit = dbQuery {
        val updated = AgentCatalogImportProposals.update({
            sourceMatches(workspaceId, workerId) and
                (AgentCatalogImportProposals.catalogHash eq catalogHash) and
                (AgentCatalogImportProposals.status eq PENDING)
        }) {
            it[status] = SKIPPED
            it[AgentCatalogImportProposals.decidedAt] = decidedAt.toKotlin()
        }
        check(updated == 1) { "Agent catalog proposal is missing, stale, or already decided" }
    }

    override suspend fun acknowledge(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
        status: AgentCatalogImportProposal.Status,
    ): Unit = dbQuery {
        require(status != AgentCatalogImportProposal.Status.PENDING) {
            "A pending catalog cannot be acknowledged"
        }
        AgentCatalogImportProposals.deleteWhere {
            sourceMatches(workspaceId, workerId) and
                (AgentCatalogImportProposals.catalogHash eq catalogHash) and
                (AgentCatalogImportProposals.status eq status.name)
        }
    }

    override suspend fun withdraw(
        workspaceId: Workspace.Id,
        workerId: String,
    ): Unit = dbQuery {
        AgentCatalogImportProposals.deleteWhere {
            sourceMatches(workspaceId, workerId) and
                (AgentCatalogImportProposals.status eq PENDING)
        }
    }

    private fun findRow(
        workspaceId: Workspace.Id,
        workerId: String,
    ): ResultRow? =
        AgentCatalogImportProposals.selectAll()
            .where { sourceMatches(workspaceId, workerId) }
            .singleOrNull()

    private fun upsertPrompt(prompt: Prompt) {
        val projectId = checkNotNull(prompt.projectId) { "Imported prompt must belong to a project" }
        val existing = Prompts.selectAll()
            .where { Prompts.id eq prompt.id.value }
            .singleOrNull()
        if (existing == null) {
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
        } else {
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
        }
    }

    private fun upsertAgent(agent: AgentDefinition) {
        val projectId = checkNotNull(agent.projectId) { "Imported agent must belong to a project" }
        val existing = Agents.selectAll()
            .where { Agents.id eq agent.id.value }
            .singleOrNull()
        if (existing == null) {
            Agents.insert {
                it[id] = agent.id.value
                it[Agents.projectId] = projectId.value
                it[name] = agent.name
                it[promptsJson] = json.encodeToString(agent.prompts)
                it[runtimeSelectionJson] = json.encodeToString(agent.runtimeSelection)
                it[runtimeOverridesJson] = json.encodeToString(agent.runtimeOverrides)
                it[toolsJson] = json.encodeToString(agent.tools)
                it[description] = agent.description
                it[type] = PROJECT_TYPE
                it[createdAt] = agent.createdAt.toKotlin()
                it[updatedAt] = agent.updatedAt.toKotlin()
            }
        } else {
            require(existing[Agents.projectId] == projectId.value) {
                "Agent ${agent.id.value} belongs to another project"
            }
            Agents.update({ Agents.id eq agent.id.value }) {
                it[name] = agent.name
                it[promptsJson] = json.encodeToString(agent.prompts)
                it[runtimeSelectionJson] = json.encodeToString(agent.runtimeSelection)
                it[runtimeOverridesJson] = json.encodeToString(agent.runtimeOverrides)
                it[toolsJson] = json.encodeToString(agent.tools)
                it[description] = agent.description
                it[type] = PROJECT_TYPE
                it[updatedAt] = agent.updatedAt.toKotlin()
            }
        }
    }

    private fun ResultRow.toProposal(): AgentCatalogImportProposal =
        AgentCatalogImportProposal(
            projectId = Project.Id(this[AgentCatalogImportProposals.projectId]),
            workspaceId = Workspace.Id(this[AgentCatalogImportProposals.workspaceId]),
            workspaceName = this[AgentCatalogImportProposals.workspaceName],
            workerId = this[AgentCatalogImportProposals.workerId],
            catalogHash = this[AgentCatalogImportProposals.catalogHash],
            prompts = json.decodeFromString<List<AgentCatalogSourcePrompt>>(
                this[AgentCatalogImportProposals.promptsJson]
            ),
            agents = json.decodeFromString<List<AgentCatalogSourceAgent>>(
                this[AgentCatalogImportProposals.agentsJson]
            ),
            validationError = this[AgentCatalogImportProposals.validationError],
            status = AgentCatalogImportProposal.Status.valueOf(this[AgentCatalogImportProposals.status]),
            detectedAt = this[AgentCatalogImportProposals.detectedAt].toKotlinx(),
            decidedAt = this[AgentCatalogImportProposals.decidedAt]?.toKotlinx(),
        )

    private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.writeProposal(
        proposal: AgentCatalogImportProposal,
    ) {
        this[AgentCatalogImportProposals.workspaceId] = proposal.workspaceId.value
        this[AgentCatalogImportProposals.workerId] = proposal.workerId
        this[AgentCatalogImportProposals.projectId] = proposal.projectId.value
        this[AgentCatalogImportProposals.workspaceName] = proposal.workspaceName
        this[AgentCatalogImportProposals.catalogHash] = proposal.catalogHash
        this[AgentCatalogImportProposals.promptsJson] = json.encodeToString(proposal.prompts)
        this[AgentCatalogImportProposals.agentsJson] = json.encodeToString(proposal.agents)
        this[AgentCatalogImportProposals.validationError] = proposal.validationError
        this[AgentCatalogImportProposals.status] = proposal.status.name
        this[AgentCatalogImportProposals.detectedAt] = proposal.detectedAt.toKotlin()
        this[AgentCatalogImportProposals.decidedAt] = proposal.decidedAt?.toKotlin()
    }

    private fun sourceMatches(
        workspaceId: Workspace.Id,
        workerId: String,
    ) = (AgentCatalogImportProposals.workspaceId eq workspaceId.value) and
        (AgentCatalogImportProposals.workerId eq workerId)

    private companion object {
        const val PENDING = "PENDING"
        const val IMPORTED = "IMPORTED"
        const val SKIPPED = "SKIPPED"
        const val PROJECT_TYPE = "project"
    }
}
