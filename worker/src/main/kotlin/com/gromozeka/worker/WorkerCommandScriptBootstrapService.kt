package com.gromozeka.worker

import com.gromozeka.domain.service.CommandScriptBootstrap
import com.gromozeka.domain.service.CommandScriptBootstrapService
import com.gromozeka.domain.service.CommandScriptTokens
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskUpsertResult
import com.gromozeka.remote.protocol.WorkerCommandRuntimeGatewayCodec
import com.gromozeka.remote.protocol.WorkerCommandRuntimeRequest
import com.gromozeka.remote.protocol.WorkerCommandRuntimeResponse
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import java.net.URI
import java.nio.file.Path

@Service
@EnableConfigurationProperties(WorkerGatewayProperties::class)
class WorkerCommandScriptBootstrapService(
    private val properties: WorkerGatewayProperties,
    private val outbound: WorkerGatewayOutbound,
) : CommandScriptBootstrapService {
    override fun create(): CommandScriptBootstrap {
        val gateway = URI(workerGatewayWebSocketUrl(properties.serverUrl))
        val endpoint = URI(if (gateway.scheme == "wss") "https" else "http", null,
            gateway.host, gateway.port, "/mcp", null, null).toString()
        val ca = properties.caCertificatePath?.trim()?.takeIf(String::isNotEmpty)
            ?.let { Path.of(it).toAbsolutePath().normalize().toString() }
        return CommandScriptBootstrap(endpoint, ca)
    }

    override suspend fun register(task: CommandTask, tokenHash: String): CommandTaskUpsertResult {
        CommandScriptTokens.requireValidHash(tokenHash)
        val response = WorkerCommandRuntimeGatewayCodec.decodeResponse(outbound.execute(
            operation = WorkerGatewayOperation.COMMAND_RUNTIME_STATE,
            payload = WorkerCommandRuntimeGatewayCodec.encodeRequest(
                WorkerCommandRuntimeRequest.RegisterCommandTask(task, tokenHash)),
        ))
        check(response is WorkerCommandRuntimeResponse.CommandTaskUpserted) { "Unexpected command registration response" }
        return CommandTaskUpsertResult(response.task, response.evictedTasks)
    }
}
