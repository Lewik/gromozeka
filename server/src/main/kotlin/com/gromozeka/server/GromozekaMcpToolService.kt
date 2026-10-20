package com.gromozeka.server

import com.gromozeka.application.service.DistributedAiToolCatalog
import com.gromozeka.application.service.AuthenticatedAiTool
import com.gromozeka.application.service.ParallelToolExecutor
import com.gromozeka.application.service.ToolInvocationContextResolver
import com.gromozeka.application.service.ToolSecretResolutionService
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service
import java.util.Base64

/** HTTP adapter over the native catalog/dispatch, not an Engine turn or another tool registry. */
@Service
internal class GromozekaMcpToolService(
    private val catalog: DistributedAiToolCatalog,
    private val contexts: ToolInvocationContextResolver,
    private val users: UserDirectoryService,
    private val agents: AgentDomainService,
    private val secrets: ToolSecretResolutionService,
    private val executor: ParallelToolExecutor,
    private val server: ConversationRuntimeExecutorDescriptor,
    private val targets: ConversationRuntimeWorkerTargetResolver,
    private val workers: WorkerToolExecutionClient,
) {
    suspend fun list(userId: User.Id): List<AiToolDefinition> = currentTools(userId).map { entry ->
        entry.descriptor.withInvocationContext(AiToolContextBindings(setOf(AiToolContextField.USER)))
            .definition.copy(
                name = entry.contract.modelName,
                description = entry.contract.descriptor.definition.description +
                    if (entry.workerIds.isEmpty()) "" else "\nCompatible Workers: " +
                        entry.workerIds.map { it.value }.sorted().joinToString(", ") +
                        ". Select an exact Worker or workspace mount; no automatic fallback.",
            )
    }

    suspend fun call(userId: User.Id, name: String, arguments: JsonObject): AiToolCallResult = try {
        currentCoroutineContext().ensureActive()
        require("_context" !in arguments) { "Untrusted context is not accepted" }
        // Fresh identity, advertisements and contract: a listed tool is not a durable permission grant.
        val actor = requireNotNull(users.findActiveById(userId)) { "User unavailable" }
        val entry = catalog.authenticatedTools(actor, ::isMcpExposed).singleOrNull { it.contract.modelName == name }
            ?: error("Tool unavailable")
        if (entry.descriptor.metadata.accessPolicy == AiToolAccessPolicy.SERVER_OWNER && actor.role != User.Role.OWNER) {
            failure("forbidden", "Server owner permission is required")
        } else {
            val callId = ContentItem.ToolCall.Id(uuid7())
            val resolved = contexts.resolve(entry.descriptor, arguments, actor.id,
                ToolExecutionContext(mapOf("toolCallId" to callId.value)))
            resolved.context.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID)?.let { id ->
                val agent = requireNotNull(agents.findById(AgentDefinition.Id(id))) { "Agent unavailable" }
                require(agent.toolAccess.allows(entry.contract)) { "Tool unavailable to selected agent" }
            }
            val executionContext = resolved.context.withValue(
                com.gromozeka.application.service.EXPECTED_TOOL_FINGERPRINTS_CONTEXT_KEY,
                buildJsonObject { put(callId.value, entry.descriptor.contractFingerprint()) }.toString(),
            )
            val target = resolved.target
            if (target is ConversationRuntimeTaskTarget.Worker) {
                require(target.workerId in entry.workerIds) { "Worker does not advertise this exact tool contract" }
            }
            val toolCall = ContentItem.ToolCall(callId,
                ContentItem.ToolCall.Data(entry.contract.logicalName, resolved.input.arguments))
            // Resolve secrets only after resource authorization and exact route/policy validation.
            val secretValues = secrets.resolve(actor.id, listOf(toolCall))
            val outcome = when (target) {
                ConversationRuntimeTaskTarget.Server -> {
                    check(server.identity is ConversationRuntimeExecutorIdentity.Server)
                    val result = executor.executeParallel(listOf(toolCall), executionContext, null,
                        server.identity, target, secretValues)
                    WorkerToolExecutionResult(result.results, result.returnDirect, result.structuredContentByCallId)
                }
                is ConversationRuntimeTaskTarget.Worker -> workers.execute(
                    targets.requireRegistered(target.workerId, ConversationRuntimeCapability.TOOL_EXECUTION),
                    target, listOf(toolCall), executionContext, secretValues,
                )
            }
            currentCoroutineContext().ensureActive()
            val result = outcome.results.single()
            check(result.toolUseId == callId && result.toolName == entry.contract.logicalName) { "Tool result identity mismatch" }
            AiToolCallResult(
                content = result.result.map { item -> when (item) {
                    is ContentItem.ToolResult.Data.Text -> AiToolResult.Text(item.content)
                    is ContentItem.ToolResult.Data.Base64Data -> AiToolResult.Binary(
                        Base64.getDecoder().decode(item.data), item.fileName ?: "tool-result", item.mediaType.value)
                    is ContentItem.ToolResult.Data.UrlData -> AiToolResult.Text(item.url)
                    is ContentItem.ToolResult.Data.ArtifactData -> AiToolResult.Text(Json.encodeToString(ContentItem.ToolResult.Data.serializer(), item))
                } },
                structuredContent = outcome.structuredContentByCallId[callId.value],
                isError = result.isError,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (pending: WorkerRequestPendingException) {
        val status = buildJsonObject {
            put("status", "PENDING"); put("request_id", pending.requestId)
            put("message", "Waiting ended, not execution. Inspect this request with grz_worker_request_get or cancel it with grz_worker_request_cancel; do not resubmit the action.")
        }
        AiToolCallResult(content = listOf(AiToolResult.Text(status.toString())), structuredContent = status)
    } catch (_: Exception) {
        failure("invocation_failed", "Tool unavailable, invalid invocation context, or operation failed")
    }

    private suspend fun currentTools(userId: User.Id): List<AuthenticatedAiTool> =
        catalog.authenticatedTools(requireNotNull(users.findActiveById(userId)) { "User unavailable" }, ::isMcpExposed)

    private fun failure(code: String, message: String): AiToolCallResult {
        val value = buildJsonObject {
            put("success", false)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        }
        return AiToolCallResult(content = listOf(AiToolResult.Text(value.toString())), structuredContent = value, isError = true)
    }
}

/** The retired memory-specific HTTP adapter/configuration cannot reopen publication. Native memory is untouched. */
internal fun isMcpExposed(descriptor: AiToolDescriptor): Boolean =
    !(descriptor.definition.source == "gromozeka" && descriptor.definition.name.startsWith("memory_")) &&
        AiToolContextField.MEMORY_NAMESPACE !in descriptor.metadata.contextRequirements.fields
