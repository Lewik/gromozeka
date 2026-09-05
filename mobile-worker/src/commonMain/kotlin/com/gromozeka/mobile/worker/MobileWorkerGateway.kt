package com.gromozeka.mobile.worker

import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerEnvironmentProfile
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.shared.uuid.uuid7
import com.gromozeka.worker.runtime.WorkerGatewayOutbound
import com.gromozeka.worker.runtime.WorkerGatewayRuntime
import com.gromozeka.worker.runtime.WorkerGatewayTransport
import com.gromozeka.worker.runtime.WorkerRequestJournal
import com.gromozeka.worker.runtime.WorkerTool
import com.gromozeka.worker.runtime.WorkerToolRequestHandler
import io.ktor.http.Url
import kotlin.time.Clock

internal class MobileWorkerGatewayEnrollment(
    val serverUrl: String,
    val workerId: String,
    val streamId: String,
    val credential: String,
) {
    val gatewayUrl: String
        get() {
            val url = Url(serverUrl)
            require(url.protocol.name == "https" && url.user == null && url.password == null &&
                url.parameters.isEmpty() && url.fragment.isEmpty() && url.encodedPath in listOf("", "/")) {
                "Mobile Worker Gateway requires an HTTPS Server origin"
            }
            return "wss://${serverUrl.substringAfter("://").trimEnd('/')}/worker/ws"
        }
}

internal enum class MobileWorkerGatewayState { STOPPED, CONNECTING, CONNECTED, RETRYING, FAILED }

internal class MobileWorkerGateway(
    enrollment: MobileWorkerGatewayEnrollment,
    transport: WorkerGatewayTransport,
    journal: WorkerRequestJournal,
    profile: WorkerEnvironmentProfile,
    version: String,
    tools: List<WorkerTool>,
    beforeExecution: suspend () -> Unit,
    onState: (MobileWorkerGatewayState) -> Unit,
    onFailure: (Throwable, Long) -> Unit = { _, _ -> },
) {
    private val workerId = ConversationRuntimeWorkerId(enrollment.workerId)
    private val identity = ConversationRuntimeWorkerIdentity(workerId, ConversationRuntimeWorkerSessionId(uuid7()))
    private val startedAt = Clock.System.now()
    private val capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION)
    private val runtime = WorkerGatewayRuntime(
        transport = transport,
        journal = journal,
        registration = {
            ConversationRuntimeWorkerRegistration(identity, capabilities, tools.map { it.descriptor }, profile,
                version, startedAt, Clock.System.now())
        },
        outbound = WorkerGatewayOutbound(capabilities),
        handler = WorkerToolRequestHandler(workerId, tools, beforeExecution),
        prepare = { welcome ->
            require(welcome.mcpServers.isEmpty()) { "This Worker does not support external MCP servers" }
            WorkerGatewayMessage.Ready(tools.map { it.descriptor })
        },
        updateCatalog = {},
        onConnected = { onState(MobileWorkerGatewayState.CONNECTED) },
        onDisconnected = { onState(MobileWorkerGatewayState.RETRYING) },
        onFailure = { error, attempts -> onState(MobileWorkerGatewayState.RETRYING); onFailure(error, attempts) },
    )

    suspend fun run() = runtime.run()
}
