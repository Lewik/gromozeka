package com.gromozeka.worker

import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.McpServerRefreshPublisher
import com.gromozeka.domain.service.WorkerToolCatalogPublisher
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import klog.KLoggers
import kotlinx.coroutines.channels.SendChannel
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

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
        }
    }

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
}
