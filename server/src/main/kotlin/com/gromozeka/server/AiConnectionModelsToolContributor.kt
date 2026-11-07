package com.gromozeka.server

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiConnectionModels
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.requireGatewayModelListing
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.AiConnectionModelsExecutor
import com.gromozeka.domain.service.AiRequestResponseExecutionClient
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.tool.AiToolAccessPolicy
import com.gromozeka.domain.tool.AiToolCallbackContributor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Service
class AiConnectionModelsService(
    private val configuration: AiConfigurationService,
    private val direct: AiConnectionModelsExecutor,
    private val remote: AiRequestResponseExecutionClient,
    private val workers: ConversationRuntimeWorkerTargetResolver,
) {
    data class Inspection(val connectionId: AiConnection.Id, val catalogRevision: Long,
        val executionTarget: AiExecutionTarget, val result: AiConnectionModels)

    suspend fun listModels(connectionId: AiConnection.Id, actorUserId: User.Id): Inspection {
        val deadline = Clock.System.now() + 30.seconds
        configuration.refreshIfChanged()
        val snapshot = configuration.snapshot
        val connection = requireNotNull(snapshot.catalog.connections.singleOrNull { it.id == connectionId }) { "AI connection not found" }
        connection.requireGatewayModelListing()
        val result = when (val target = connection.executionTarget) {
            AiExecutionTarget.Server -> direct.listModels(connection, deadline)
            is AiExecutionTarget.Worker -> remote.listModels(
                workers.requireRegistered(ConversationRuntimeWorkerId(target.workerId), ConversationRuntimeCapability.AI_REQUEST_RESPONSE),
                connection, deadline, actorUserId)
        }
        return Inspection(connection.id, snapshot.revision, connection.executionTarget, result)
    }
}

@Service
internal class AiConnectionModelsToolContributor(
    toolFactory: ServerJsonToolFactory,
    service: AiConnectionModelsService,
) : AiToolCallbackContributor {
    override val callbacks = listOf(toolFactory.create(CONTROL_TOOL_SOURCE,
        name = "grz_ai_connection_models",
        description = "Explicitly check the OpenAI-compatible GET /models endpoint of one saved, enabled SDK HTTP connection with an explicit base URL, on its configured Server or exact Worker. Uses its selected credential, CA, proxy and headers. One bounded GET, no automatic retry, redirects, route fallback, model inference or catalog changes. Returns candidate IDs and metadata only, never inferred capabilities or limits. Create specs and configurations manually through existing catalog tools. This is not a health, authentication-proof or inference-readiness test. Disabled/unsupported connections make no network request.",
        inputSchema = ServerToolSchemas.objectSchema(mapOf("connectionId" to ServerToolSchemas.string("Exact saved AI connection id.")), listOf("connectionId")),
        readOnly = true,
        accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
    ) { input ->
        cancellationSignal.throwIfCancellationRequested()
        val inspection = coroutineScope {
            val operation = async { service.listModels(AiConnection.Id(input.requiredString("connectionId")), user.id) }
            // Synchronous callback callers may communicate cancellation only through the trusted signal.
            val cancellation = launch {
                while (isActive) {
                    try { cancellationSignal.throwIfCancellationRequested() }
                    catch (error: CancellationException) { operation.cancel(error); return@launch }
                    delay(50)
                }
            }
            try { operation.await() } finally { cancellation.cancelAndJoin() }
        }
        buildJsonObject {
            put("connectionId", inspection.connectionId.value)
            put("catalogRevision", inspection.catalogRevision)
            put("executionTarget", serverToolJson.encodeToJsonElement(AiExecutionTarget.serializer(), inspection.executionTarget))
            put("endpoint", "models")
            put("inferenceChecked", false)
            put("catalogChanged", false)
            put("result", serverToolJson.encodeToJsonElement(AiConnectionModels.serializer(), inspection.result))
        }
    })
}
