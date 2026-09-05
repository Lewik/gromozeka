package com.gromozeka.server

import com.gromozeka.domain.service.WorkerControlClient
import com.gromozeka.domain.service.WorkerControlRequest
import com.gromozeka.domain.service.WorkerControlResult
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.time.Duration

@Service
@Primary
class GatewayWorkerControlClient(
    private val requests: WorkerRequestService,
    @Value("\${gromozeka.runtime.worker-control.timeout-millis:120000}")
    timeoutMillis: Long,
) : WorkerControlClient {
    private val timeout = Duration.ofMillis(timeoutMillis)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    init {
        require(timeoutMillis > 0) { "Worker control timeout must be positive" }
    }

    override suspend fun execute(request: WorkerControlRequest): WorkerControlResult {
        val response = requests.execute(
            workerId = request.target.workerId,
            operation = WorkerGatewayOperation.WORKER_CONTROL,
            payload = json.encodeToString(request).encodeToByteArray(),
            policy = com.gromozeka.domain.service.WorkerRequestPolicy(executionTimeoutMillis = timeout.toMillis()),
        )
        return json.decodeFromString(response.decodeToString())
    }
}
