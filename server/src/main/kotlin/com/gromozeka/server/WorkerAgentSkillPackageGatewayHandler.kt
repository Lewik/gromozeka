package com.gromozeka.server

import com.gromozeka.application.service.AgentSkillApplicationService
import com.gromozeka.application.service.AgentSkillRuntimeAccess
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.remote.protocol.WorkerAgentSkillImportGatewayCodec
import com.gromozeka.remote.protocol.WorkerAgentSkillPackageGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import org.springframework.stereotype.Service

@Service
class WorkerAgentSkillPackageGatewayHandler(
    private val skillRuntimeAccess: AgentSkillRuntimeAccess,
    private val workspaceDomainService: WorkspaceDomainService,
    private val workerAccessService: WorkerAccessService,
) : WorkerGatewayServerRequestHandler {
    override val operation = WorkerGatewayOperation.AGENT_SKILL_PACKAGE

    override suspend fun execute(
        identity: ConversationRuntimeWorkerIdentity,
        request: WorkerGatewayMessage.Request,
    ): ByteArray {
        require(request.operation == operation) {
            "Worker cannot invoke Server operation ${request.operation}"
        }
        val skillRequest = WorkerAgentSkillPackageGatewayCodec.decodeRequest(request.payload)
        workerAccessService.requireProjectAccess(identity.workerId, skillRequest.projectId)
        skillRequest.workspaceMountId?.let { workspaceMountId ->
            val workspace = workspaceDomainService.resolveExecution(workspaceMountId)
            require(workspace.project.id == skillRequest.projectId) {
                "Workspace mount ${workspaceMountId.value} belongs to another project"
            }
            require(workspace.mount.workerId == identity.workerId.value) {
                "Workspace mount ${workspaceMountId.value} belongs to another Worker"
            }
        }
        val skillPackage = skillRuntimeAccess.resolve(
            context = ToolExecutionContext(
                mapOf(
                    TOOL_CONTEXT_PROJECT_ID to skillRequest.projectId.value,
                    TOOL_CONTEXT_AGENT_DEFINITION_ID to skillRequest.agentDefinitionId.value,
                )
            ),
            skillId = skillRequest.skillId,
            contentHash = skillRequest.contentHash,
        )
        return WorkerAgentSkillPackageGatewayCodec.encodeResult(skillPackage)
    }
}

@Service
class WorkerAgentSkillImportGatewayHandler(
    private val skillService: AgentSkillApplicationService,
    private val agentRepository: AgentRepository,
    private val projectAccessService: ProjectAccessService,
    private val workerAccessService: WorkerAccessService,
) : WorkerGatewayServerRequestHandler {
    override val operation = WorkerGatewayOperation.AGENT_SKILL_IMPORT

    override suspend fun execute(
        identity: ConversationRuntimeWorkerIdentity,
        request: WorkerGatewayMessage.Request,
    ): ByteArray {
        require(request.operation == operation) {
            "Worker cannot invoke Server operation ${request.operation}"
        }
        val importRequest = WorkerAgentSkillImportGatewayCodec.decodeRequest(request.payload)
        workerAccessService.requireProjectAccess(identity.workerId, importRequest.projectId)
        projectAccessService.requirePermission(
            importRequest.actorUserId,
            importRequest.projectId,
            ProjectPermission.WRITE,
        )
        val agent = agentRepository.findById(importRequest.agentDefinitionId)
            ?: error("Agent not found: ${importRequest.agentDefinitionId.value}")
        require(agent.projectId == importRequest.projectId) {
            "Agent ${agent.id.value} does not belong to project ${importRequest.projectId.value}"
        }
        val skill = skillService.importDirectoryPackage(
            projectId = importRequest.projectId,
            source = importRequest.source,
            expectedContentHash = importRequest.expectedContentHash,
            actorUserId = importRequest.actorUserId,
        )
        return WorkerAgentSkillImportGatewayCodec.encodeResult(skill)
    }
}
