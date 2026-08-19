package com.gromozeka.server

import com.gromozeka.application.service.AgentSkillRuntimeAccess
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID
import com.gromozeka.domain.tool.ToolExecutionContext
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
        val workspace = workspaceDomainService.resolveExecution(skillRequest.workspaceMountId)
        require(workspace.project.id == skillRequest.projectId) {
            "Workspace mount ${skillRequest.workspaceMountId.value} belongs to another project"
        }
        require(workspace.mount.workerId == identity.workerId.value) {
            "Workspace mount ${skillRequest.workspaceMountId.value} belongs to another Worker"
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
