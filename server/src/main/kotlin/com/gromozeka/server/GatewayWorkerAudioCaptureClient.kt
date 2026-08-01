package com.gromozeka.server

import com.gromozeka.domain.service.WorkerAudioCaptureClient
import com.gromozeka.domain.service.WorkerAudioCaptureRequest
import com.gromozeka.domain.service.WorkerAudioCaptureResult
import com.gromozeka.remote.protocol.WorkerAudioCaptureGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Service
@Primary
class GatewayWorkerAudioCaptureClient(
    private val sessionRegistry: WorkerGatewaySessionRegistry,
    @Value("\${gromozeka.runtime.audio-capture.timeout-millis:300000}")
    timeoutMillis: Long,
) : WorkerAudioCaptureClient {
    private val timeout = Duration.ofMillis(timeoutMillis)

    init {
        require(timeoutMillis > 0) { "Worker audio capture timeout must be positive" }
    }

    override suspend fun execute(request: WorkerAudioCaptureRequest): WorkerAudioCaptureResult =
        sessionRegistry.execute(
            target = request.target,
            operation = WorkerGatewayOperation.AUDIO_CAPTURE,
            payload = WorkerAudioCaptureGatewayCodec.encodeRequest(request),
            timeout = timeout,
        ).let(WorkerAudioCaptureGatewayCodec::decodeResult)
}
