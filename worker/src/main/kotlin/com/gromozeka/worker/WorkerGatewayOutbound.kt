package com.gromozeka.worker

import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.McpServerRefreshPublisher
import com.gromozeka.domain.service.WorkerToolCatalogPublisher
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeout
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Service
@Primary
class WorkerGatewayOutbound(
    descriptor: ConversationRuntimeWorkerDescriptor,
) : WorkerToolCatalogPublisher, McpServerRefreshPublisher {
    private val log = KLoggers.logger(this)
    override val capabilities = descriptor.capabilities

    @Volatile
    private var currentTools = descriptor.tools

    @Volatile
    private var activeOutgoing: SendChannel<WorkerGatewayMessage>? = null

    private val pending = ConcurrentHashMap<String, CompletableDeferred<WorkerGatewayMessage.Response>>()

    fun currentTools(): List<AiToolDescriptor> = currentTools

    fun replaceBeforeReady(tools: List<AiToolDescriptor>) {
        validate(tools)
        check(activeOutgoing == null) { "Worker Gateway is already ready" }
        currentTools = tools
    }

    fun attach(outgoing: SendChannel<WorkerGatewayMessage>) {
        check(activeOutgoing == null) { "Worker Gateway outgoing channel is already attached" }
        activeOutgoing = outgoing
    }

    fun detach(outgoing: SendChannel<WorkerGatewayMessage>) {
        if (activeOutgoing === outgoing) {
            activeOutgoing = null
            val error = IllegalStateException("Worker Gateway disconnected before receiving a response")
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
        }
    }

    suspend fun execute(
        operation: WorkerGatewayOperation,
        payload: ByteArray,
        timeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    ): ByteArray {
        val outgoing = activeOutgoing ?: error("Worker Gateway is offline")
        val request = WorkerGatewayMessage.Request(
            id = uuid7(),
            operation = operation,
            payload = payload,
        )
        val response = CompletableDeferred<WorkerGatewayMessage.Response>()
        check(pending.putIfAbsent(request.id, response) == null) {
            "Duplicate Worker Gateway request id: ${request.id}"
        }
        try {
            outgoing.send(request)
            val result = try {
                withTimeout(timeout) {
                    response.await()
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                throw IllegalStateException(
                    "Worker Gateway request ${request.id} failed before a response was received",
                    error,
                )
            }
            if (result.status == WorkerGatewayMessage.Response.Status.FAILED) {
                error("Server operation failed [${result.errorCode}]: ${result.errorMessage}")
            }
            return requireNotNull(result.payload)
        } finally {
            pending.remove(request.id, response)
        }
    }

    fun accept(response: WorkerGatewayMessage.Response): Boolean =
        pending[response.requestId]?.complete(response) == true

    override suspend fun updateAdvertisedTools(tools: List<AiToolDescriptor>) {
        validate(tools)
        currentTools = tools
        activeOutgoing?.send(WorkerGatewayMessage.ToolCatalogUpdated(tools))
            ?: error("Worker Gateway is offline; tool catalog update was not delivered")
    }

    override suspend fun publishRefreshAvailable(
        serverId: McpServerId,
        expectedRevision: Long,
    ) {
        val outgoing = activeOutgoing
        if (outgoing == null) {
            log.warn {
                "MCP refresh notification deferred until reconnect: server=${serverId.value} " +
                    "revision=$expectedRevision"
            }
            return
        }
        outgoing.send(
            WorkerGatewayMessage.McpServerRefreshAvailable(
                serverId = serverId,
                expectedRevision = expectedRevision,
            )
        )
    }

    private fun validate(tools: List<AiToolDescriptor>) {
        require(tools.all { capabilities.containsAll(it.metadata.requiredRuntimeCapabilities) }) {
            "Worker must declare every capability required by its advertised tools"
        }
        require(tools.map { it.definition.name }.distinct().size == tools.size) {
            "Worker advertised tool names must be unique"
        }
    }

    private companion object {
        val DEFAULT_REQUEST_TIMEOUT = 30.seconds
    }
}
