package com.gromozeka.worker

import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.service.AgentSkillPackageClient
import com.gromozeka.domain.service.AgentSkillPackageRequest
import com.gromozeka.remote.protocol.WorkerAgentSkillPackageGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import kotlin.time.Duration.Companion.minutes
import org.springframework.stereotype.Service

@Service
class WorkerGatewayAgentSkillPackageClient(
    private val outbound: WorkerGatewayOutbound,
) : AgentSkillPackageClient {
    override suspend fun fetch(request: AgentSkillPackageRequest): AgentSkillPackage =
        WorkerAgentSkillPackageGatewayCodec.decodeResult(
            outbound.execute(
                operation = WorkerGatewayOperation.AGENT_SKILL_PACKAGE,
                payload = WorkerAgentSkillPackageGatewayCodec.encodeRequest(request),
                timeout = 2.minutes,
            )
        )
}
