package com.gromozeka.worker.runtime

import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.McpServerRefreshPublisher
import com.gromozeka.domain.service.WorkerToolCatalogPublisher
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

open class WorkerGatewayOutbound(
    override val capabilities: Set<ConversationRuntimeCapability>,
) : WorkerToolCatalogPublisher, McpServerRefreshPublisher {
    private val mutex = Mutex()
    private val tools = MutableStateFlow<List<AiToolDescriptor>>(emptyList())
    private var activeOutgoing: SendChannel<WorkerGatewayMessage>? = null
    private val pending = mutableMapOf<String, CompletableDeferred<WorkerGatewayMessage.Response>>()
    private val closedRequests = linkedSetOf<String>()

    fun currentTools(): List<AiToolDescriptor> = tools.value

    suspend fun replaceBeforeReady(tools: List<AiToolDescriptor>) = mutex.withLock {
        validate(tools)
        check(activeOutgoing == null) { "Worker Gateway is already ready" }
        this.tools.value = tools
    }

    suspend fun attach(outgoing: SendChannel<WorkerGatewayMessage>) = mutex.withLock {
        check(activeOutgoing == null) { "Worker Gateway outgoing channel is already attached" }
        activeOutgoing = outgoing
    }

    suspend fun detach(outgoing: SendChannel<WorkerGatewayMessage>) = mutex.withLock {
        if (activeOutgoing === outgoing) {
            activeOutgoing = null
            val error = IllegalStateException("Worker Gateway disconnected before receiving a response")
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
            closedRequests.clear()
        }
    }

    suspend fun execute(
        operation: WorkerGatewayOperation,
        payload: ByteArray,
        timeout: Duration = 30.seconds,
    ): ByteArray {
        val request = WorkerGatewayMessage.Request(uuid7(), operation, payload)
        val response = CompletableDeferred<WorkerGatewayMessage.Response>()
        val outgoing = mutex.withLock {
            val channel = activeOutgoing ?: error("Worker Gateway is offline")
            check(pending.put(request.id, response) == null) { "Duplicate Worker Gateway request id" }
            channel
        }
        try {
            val result = withTimeout(timeout) {
                outgoing.send(request)
                response.await()
            }
            check(result.status == WorkerGatewayMessage.Response.Status.SUCCEEDED) {
                "Server operation failed [${result.errorCode}]: ${result.errorMessage}"
            }
            return requireNotNull(result.payload)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    pending.remove(request.id)
                    if (activeOutgoing === outgoing) {
                        closedRequests.add(request.id)
                        if (closedRequests.size > 2_048) closedRequests.remove(closedRequests.first())
                    }
                }
            }
        }
    }

    suspend fun accept(response: WorkerGatewayMessage.Response): Boolean = mutex.withLock {
        pending[response.requestId]?.complete(response) == true || response.requestId in closedRequests
    }

    override suspend fun updateAdvertisedTools(tools: List<AiToolDescriptor>) {
        validate(tools)
        val outgoing = mutex.withLock {
            this.tools.value = tools
            activeOutgoing ?: error("Worker Gateway is offline; tool catalog update was not delivered")
        }
        outgoing.send(WorkerGatewayMessage.ToolCatalogUpdated(tools))
    }

    override suspend fun publishRefreshAvailable(serverId: McpServerId, expectedRevision: Long) {
        val message = WorkerGatewayMessage.McpServerRefreshAvailable(serverId, expectedRevision)
        val outgoing = mutex.withLock { activeOutgoing } ?: return
        outgoing.send(message)
    }

    private fun validate(tools: List<AiToolDescriptor>) {
        require(tools.isEmpty() || ConversationRuntimeCapability.TOOL_EXECUTION in capabilities) {
            "A Worker advertising tools must declare TOOL_EXECUTION"
        }
        require(tools.all { capabilities.containsAll(it.metadata.requiredRuntimeCapabilities) }) {
            "Worker must declare every capability required by its advertised tools"
        }
        require(tools.map { it.definition.name }.distinct().size == tools.size) {
            "Worker advertised tool names must be unique"
        }
    }
}
