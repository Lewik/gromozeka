package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentCatalogImportProposal
import com.gromozeka.domain.model.AgentCatalogImportResult
import com.gromozeka.domain.model.AgentCatalogSnapshot
import com.gromozeka.domain.model.AgentCatalogSourceAgent
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.repository.AgentCatalogImportRepository
import com.gromozeka.domain.repository.PromptRepository
import com.gromozeka.domain.service.AgentCatalogDiscoveryService
import com.gromozeka.domain.service.AgentCatalogImportService
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.service.WorkspaceDomainService
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service

@Service
class AgentCatalogImportApplicationService(
    private val repository: AgentCatalogImportRepository,
    private val projectService: ProjectDomainService,
    private val workspaceService: WorkspaceDomainService,
    private val promptRepository: PromptRepository,
    private val settingsProvider: SettingsProvider,
) : AgentCatalogImportService, AgentCatalogDiscoveryService {

    override suspend fun report(snapshot: AgentCatalogSnapshot): AgentCatalogImportProposal {
        val validationError = runCatching { validate(snapshot) }
            .exceptionOrNull()
            ?.message
            ?: snapshot.scannerError
        return repository.report(
            AgentCatalogImportProposal(
                projectId = snapshot.projectId,
                workspaceId = snapshot.workspaceId,
                workspaceName = snapshot.workspaceName,
                workerId = snapshot.workerId,
                catalogHash = snapshot.catalogHash,
                prompts = snapshot.prompts,
                agents = snapshot.agents,
                validationError = validationError,
                status = AgentCatalogImportProposal.Status.PENDING,
                detectedAt = snapshot.detectedAt,
            )
        )
    }

    override suspend fun find(
        workspaceId: Workspace.Id,
        workerId: String,
    ): AgentCatalogImportProposal? = repository.find(workspaceId, workerId)

    override suspend fun findPending(projectId: Project.Id): List<AgentCatalogImportProposal> =
        repository.findPending(projectId)

    override suspend fun import(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
    ): AgentCatalogImportResult {
        val proposal = requirePending(workspaceId, workerId, catalogHash)
        check(proposal.validationError == null) {
            "Agent catalog cannot be imported: ${proposal.validationError}"
        }
        validate(proposal.toSnapshot())

        val now = Clock.System.now()
        val promptIds = proposal.prompts.associate { source ->
            source.sourcePath to promptId(proposal.projectId, source.sourcePath)
        }
        val prompts = proposal.prompts.map { source ->
            Prompt(
                id = checkNotNull(promptIds[source.sourcePath]),
                projectId = proposal.projectId,
                name = source.name,
                content = source.content,
                type = Prompt.Type.Project,
                createdAt = now,
                updatedAt = now,
            )
        }
        val agents = proposal.agents.map { source ->
            AgentDefinition(
                id = agentId(proposal.projectId, source.sourcePath),
                projectId = proposal.projectId,
                name = source.name,
                prompts = source.prompts.map { reference ->
                    resolvePromptReference(reference, promptIds)
                },
                runtimeSelection = source.runtimeSelection,
                runtimeOverrides = source.runtimeOverrides,
                tools = source.tools,
                description = source.description,
                type = AgentDefinition.Type.Project,
                createdAt = now,
                updatedAt = now,
            )
        }
        return repository.import(
            workspaceId = workspaceId,
            workerId = workerId,
            catalogHash = catalogHash,
            prompts = prompts,
            agents = agents,
            decidedAt = now,
        )
    }

    override suspend fun skip(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
    ) {
        requirePending(workspaceId, workerId, catalogHash)
        repository.skip(workspaceId, workerId, catalogHash, Clock.System.now())
    }

    override suspend fun acknowledge(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
        status: AgentCatalogImportProposal.Status,
    ) {
        require(status != AgentCatalogImportProposal.Status.PENDING) {
            "Only a completed catalog decision can be acknowledged"
        }
        repository.acknowledge(workspaceId, workerId, catalogHash, status)
    }

    override suspend fun withdraw(
        workspaceId: Workspace.Id,
        workerId: String,
    ) {
        repository.withdraw(workspaceId, workerId)
    }

    private suspend fun requirePending(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
    ): AgentCatalogImportProposal {
        val proposal = repository.find(workspaceId, workerId)
            ?: error("Agent catalog proposal not found for workspace ${workspaceId.value} on worker $workerId")
        check(proposal.catalogHash == catalogHash) {
            "Agent catalog proposal changed; refresh the Runtime panel before deciding"
        }
        check(proposal.status == AgentCatalogImportProposal.Status.PENDING) {
            "Agent catalog proposal is already ${proposal.status.name.lowercase()}"
        }
        return proposal
    }

    private suspend fun validate(snapshot: AgentCatalogSnapshot) {
        snapshot.scannerError?.let(::error)
        require(snapshot.catalogHash.matches(SHA_256)) { "Invalid agent catalog hash" }
        require(snapshot.workerId.isNotBlank()) { "Agent catalog worker id must not be blank" }
        require(snapshot.workerId.length <= MAX_DATABASE_STRING_LENGTH) {
            "Agent catalog worker id is too long"
        }
        require(snapshot.workspaceName.isNotBlank()) { "Agent catalog workspace name must not be blank" }
        require(snapshot.workspaceName.length <= MAX_DATABASE_STRING_LENGTH) {
            "Agent catalog workspace name is too long"
        }
        require(snapshot.prompts.size <= MAX_PROMPTS) { "Agent catalog contains too many prompts" }
        require(snapshot.agents.size <= MAX_AGENTS) { "Agent catalog contains too many agents" }

        val project = projectService.findById(snapshot.projectId)
            ?: error("Agent catalog project not found: ${snapshot.projectId.value}")
        val workspace = workspaceService.findById(snapshot.workspaceId)
            ?: error("Agent catalog workspace not found: ${snapshot.workspaceId.value}")
        require(workspace.projectId == project.id) {
            "Workspace ${workspace.id.value} does not belong to project ${project.id.value}"
        }
        require(workspaceService.findMount(workspace.id, snapshot.workerId) != null) {
            "Worker ${snapshot.workerId} does not mount workspace ${workspace.id.value}"
        }

        val sourcePrompts = snapshot.prompts.associateByUniqueSourcePath("prompt") { it.sourcePath }
        snapshot.agents.associateByUniqueSourcePath("agent") { it.sourcePath }
        snapshot.prompts.forEach { prompt ->
            validateSourcePath(prompt.sourcePath)
            require(prompt.sourcePath.endsWith(".md")) {
                "Project prompt source must be a Markdown file: ${prompt.sourcePath}"
            }
            require(prompt.name.isNotBlank()) { "Project prompt name must not be blank: ${prompt.sourcePath}" }
            require(prompt.name.length <= MAX_DATABASE_STRING_LENGTH) {
                "Project prompt name is too long: ${prompt.sourcePath}"
            }
            require(prompt.content.isNotBlank()) { "Project prompt must not be empty: ${prompt.sourcePath}" }
            require(prompt.content.length <= MAX_PROMPT_CHARACTERS) {
                "Project prompt is too large: ${prompt.sourcePath}"
            }
            promptId(snapshot.projectId, prompt.sourcePath)
        }
        snapshot.agents.forEach { agent ->
            validateAgent(agent, snapshot.projectId, sourcePrompts)
        }
    }

    private suspend fun validateAgent(
        agent: AgentCatalogSourceAgent,
        projectId: Project.Id,
        sourcePrompts: Map<String, *>,
    ) {
        validateSourcePath(agent.sourcePath)
        require(agent.sourcePath.endsWith(".json")) {
            "Project agent source must be a JSON file: ${agent.sourcePath}"
        }
        require(agent.name.isNotBlank()) { "Project agent name must not be blank: ${agent.sourcePath}" }
        require(agent.name.length <= MAX_DATABASE_STRING_LENGTH) {
            "Project agent name is too long: ${agent.sourcePath}"
        }
        require(agent.prompts.isNotEmpty()) { "Project agent must reference at least one prompt: ${agent.sourcePath}" }
        require(agent.prompts.distinct().size == agent.prompts.size) {
            "Project agent contains duplicate prompt references: ${agent.sourcePath}"
        }
        require(agent.tools.all(String::isNotBlank)) {
            "Project agent contains a blank tool name: ${agent.sourcePath}"
        }
        require(agent.tools.distinct().size == agent.tools.size) {
            "Project agent contains duplicate tools: ${agent.sourcePath}"
        }
        require(
            settingsProvider.userProfile.aiSettings.modelConfigurations.any {
                it.id == agent.runtimeSelection.modelConfigurationId
            }
        ) {
            "AI model configuration not found: ${agent.runtimeSelection.modelConfigurationId.value}"
        }
        agent.prompts.forEach { reference ->
            when {
                reference == ENV_PROMPT_ID -> Unit
                reference.startsWith(BUILTIN_PREFIX) -> require(promptRepository.findBuiltinById(Prompt.Id(reference)) != null) {
                    "Builtin prompt not found: $reference"
                }
                reference.startsWith(PROJECT_PREFIX) -> {
                    val sourcePath = reference.removePrefix(PROJECT_PREFIX)
                    require(sourcePath in sourcePrompts) {
                        "Project prompt source not found: $sourcePath"
                    }
                }
                else -> error("Unsupported prompt reference '$reference' in ${agent.sourcePath}")
            }
        }
        agentId(projectId, agent.sourcePath)
    }

    private fun validateSourcePath(sourcePath: String) {
        require(sourcePath.isNotBlank()) { "Agent catalog source path must not be blank" }
        require(sourcePath.length <= MAX_SOURCE_PATH_LENGTH) { "Agent catalog source path is too long: $sourcePath" }
        require('/' !in sourcePath && '\\' !in sourcePath) {
            "Agent catalog source path must be a direct child: $sourcePath"
        }
        require(sourcePath != "." && sourcePath != "..") { "Invalid agent catalog source path: $sourcePath" }
        require(sourcePath.none(Char::isISOControl)) { "Invalid control character in source path" }
    }

    private fun <T> List<T>.associateByUniqueSourcePath(
        kind: String,
        sourcePath: (T) -> String,
    ): Map<String, T> {
        val result = associateBy(sourcePath)
        require(result.size == size) { "Agent catalog contains duplicate $kind source paths" }
        return result
    }

    private fun resolvePromptReference(
        reference: String,
        projectPromptIds: Map<String, Prompt.Id>,
    ): Prompt.Id = when {
        reference == ENV_PROMPT_ID -> Prompt.Id(reference)
        reference.startsWith(BUILTIN_PREFIX) -> Prompt.Id(reference)
        reference.startsWith(PROJECT_PREFIX) -> checkNotNull(projectPromptIds[reference.removePrefix(PROJECT_PREFIX)])
        else -> error("Unsupported prompt reference: $reference")
    }

    private fun promptId(projectId: Project.Id, sourcePath: String): Prompt.Id =
        Prompt.Id("project:${projectId.value}:prompt:$sourcePath").also {
            require(it.value.length <= MAX_PERSISTED_ID_LENGTH) { "Generated prompt id is too long: ${it.value}" }
        }

    private fun agentId(projectId: Project.Id, sourcePath: String): AgentDefinition.Id =
        AgentDefinition.Id("project:${projectId.value}:agent:$sourcePath").also {
            require(it.value.length <= MAX_PERSISTED_ID_LENGTH) { "Generated agent id is too long: ${it.value}" }
        }

    private fun AgentCatalogImportProposal.toSnapshot(): AgentCatalogSnapshot =
        AgentCatalogSnapshot(
            projectId = projectId,
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            workerId = workerId,
            catalogHash = catalogHash,
            prompts = prompts,
            agents = agents,
            detectedAt = detectedAt,
        )

    private companion object {
        const val ENV_PROMPT_ID = "env"
        const val BUILTIN_PREFIX = "builtin:"
        const val PROJECT_PREFIX = "project:"
        const val MAX_PROMPTS = 256
        const val MAX_AGENTS = 128
        const val MAX_PROMPT_CHARACTERS = 1_000_000
        const val MAX_SOURCE_PATH_LENGTH = 160
        const val MAX_PERSISTED_ID_LENGTH = 255
        const val MAX_DATABASE_STRING_LENGTH = 255
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
