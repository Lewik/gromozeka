package com.gromozeka.server

import com.gromozeka.domain.service.WorkerWorkspaceFileClient
import com.gromozeka.domain.service.WorkerWorkspaceFileReadRequest
import com.gromozeka.domain.service.WorkspaceFile
import com.gromozeka.domain.service.WorkspacePathAccessContext
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerWorkspaceFileGatewayCodec
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.time.Duration

@Service
@Primary
class GatewayWorkerWorkspaceFileClient(
    private val requests: WorkerRequestService,
    @Value("\${gromozeka.runtime.workspace-file.timeout-millis:120000}")
    timeoutMillis: Long,
) : WorkerWorkspaceFileClient {
    private val timeout = Duration.ofMillis(timeoutMillis)

    init {
        require(timeoutMillis > 0) { "Workspace file timeout must be positive" }
    }

    override suspend fun read(request: WorkerWorkspaceFileReadRequest, access: WorkspacePathAccessContext): WorkspaceFile =
        requests.execute(
            workerId = request.target.workerId,
            operation = WorkerGatewayOperation.WORKSPACE_FILE,
            payload = WorkerWorkspaceFileGatewayCodec.encodeRequest(request),
            policy = com.gromozeka.domain.service.WorkerRequestPolicy(executionTimeoutMillis = timeout.toMillis()),
            actorUserId = access.actorUserId,
            projectId = access.expectedProjectId,
        ).let(WorkerWorkspaceFileGatewayCodec::decodeResult)
}
