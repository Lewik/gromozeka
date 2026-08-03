package com.gromozeka.server

import com.gromozeka.domain.service.WorkerWorkspaceTextFileClient
import com.gromozeka.domain.service.WorkerWorkspaceTextFileReadRequest
import com.gromozeka.domain.service.WorkspaceTextFile
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerWorkspaceTextFileGatewayCodec
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.time.Duration

@Service
@Primary
class GatewayWorkerWorkspaceTextFileClient(
    private val sessionRegistry: WorkerGatewaySessionRegistry,
    @Value("\${gromozeka.runtime.workspace-file.timeout-millis:120000}")
    timeoutMillis: Long,
) : WorkerWorkspaceTextFileClient {
    private val timeout = Duration.ofMillis(timeoutMillis)

    init {
        require(timeoutMillis > 0) { "Workspace file timeout must be positive" }
    }

    override suspend fun read(request: WorkerWorkspaceTextFileReadRequest): WorkspaceTextFile =
        sessionRegistry.execute(
            target = request.target,
            operation = WorkerGatewayOperation.WORKSPACE_TEXT_FILE,
            payload = WorkerWorkspaceTextFileGatewayCodec.encodeRequest(request),
            timeout = timeout,
        ).let(WorkerWorkspaceTextFileGatewayCodec::decodeResult)
}
