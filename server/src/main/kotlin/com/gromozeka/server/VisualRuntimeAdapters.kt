package com.gromozeka.server

import com.gromozeka.application.service.VisualApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.domain.visual.*
import com.gromozeka.remote.protocol.*
import com.gromozeka.shared.uuid.uuid7
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Service
class GatewayVisualCommandRuntime(
    private val requests: WorkerRequestService,
    private val workspaces: WorkspaceDomainService,
    private val workers: ConversationRuntimeWorkerRegistry,
    private val workerAccess: WorkerAccessService,
    private val commandState: CommandRuntimeStateService,
    private val coordinator: ConversationRuntimeCoordinator,
    private val conversations: ConversationDomainService,
    private val secrets: com.gromozeka.application.service.NamedSecretApplicationService,
) : VisualCommandRuntime {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override suspend fun prepare(actor: User, conversation: Conversation, spec: VisualHandlerSpec): VisualHandler {
        require(spec.command.isNotBlank() && spec.command.encodeToByteArray().size <= 65_536) { "Handler command must contain 1..65536 bytes" }
        val workspace = workspaces.resolveExecution(spec.workspaceMountId)
        require(workspace.project.id == conversation.projectId) { "Handler mount must belong to the conversation project" }
        val workerId = ConversationRuntimeWorkerId(workspace.mount.workerId)
        workerAccess.requirePermission(actor, workerId, WorkerPermission.USE, conversation.projectId)
        workerAccess.requireProjectAccess(workerId, conversation.projectId)
        val worker = requireNotNull(workers.find(workerId)) { "Handler Worker is not registered" }
        require(worker.isOnline(Clock.System.now() - 30.seconds)) { "Handler Worker is offline" }
        require(worker.tools.any { it.definition.name == "grz_execute_command" || it.definition.name.startsWith("grz_execute_command__v") }) { "Handler Worker does not support managed commands" }
        return VisualHandler(spec, worker.identity, uuid7())
    }

    override suspend fun start(actor: User, visual: Visual): CommandTask {
        val handler = requireNotNull(visual.handler)
        val workspace = workspaces.resolveExecution(handler.spec.workspaceMountId)
        val conversation = requireNotNull(conversations.findById(visual.conversationId))
        require(workspace.project.id == conversation.projectId && workspace.mount.workerId == handler.worker.workerId.value) { "Handler workspace binding changed" }
        val context = buildMap {
            put(TOOL_CONTEXT_CONVERSATION_ID, visual.conversationId.value)
            put(TOOL_CONTEXT_PROJECT_ID, conversation.projectId.value)
            put(TOOL_CONTEXT_USER_ID, actor.id.value)
            put(TOOL_CONTEXT_WORKSPACE_ID, workspace.workspace.id.value)
            put(TOOL_CONTEXT_WORKSPACE_MOUNT_ID, workspace.mount.id.value)
            put(TOOL_CONTEXT_WORKSPACE_ROOT_PATH, workspace.mount.rootPath)
            put(TOOL_CONTEXT_WORKER_ID, handler.worker.workerId.value)
            put(TOOL_CONTEXT_VISUAL_ID, visual.id)
            put(TOOL_CONTEXT_ORIGINAL_COMMAND, handler.spec.command)
            visual.agentDefinitionId?.let { put(TOOL_CONTEXT_AGENT_DEFINITION_ID, it.value) }
        }
        val names = NamedSecret.namesInText(handler.spec.command)
        val values = if (names.isEmpty()) emptyMap() else secrets.resolve(actor.id, names)
        val prepared = com.gromozeka.application.service.SecretArgumentSubstitutor().prepare(
            com.gromozeka.domain.tool.filesystem.GRZ_EXECUTE_COMMAND_TOOL_NAME,
            buildJsonObject { put("command", handler.spec.command) }.toString(), values,
            isWindows = workers.find(handler.worker.workerId)?.environmentProfile?.operatingSystem?.family == WorkerOperatingSystem.Family.WINDOWS,
        )
        val command = json.parseToJsonElement(prepared.arguments).jsonObject.getValue("command").jsonPrimitive.content
        return requireNotNull(execute(actor, conversation.projectId, VisualWorkerCommand(
            VisualWorkerCommand.Operation.START, visual.id, visual.conversationId, handler.generation,
            handler.worker, workspace = workspace, spec = handler.spec.copy(command = command), toolContext = context,
            secretEnvironment = prepared.secretEnvironment,
        )).task) { "Worker did not return a managed handler task" }
    }

    override suspend fun sendInput(actor: User, visual: Visual, input: String) {
        val handler = requireNotNull(visual.handler)
        require(input.encodeToByteArray().size <= MAX_COMMAND_INPUT_BYTES) { "Visual action exceeds command stdin limit" }
        val projectId = requireNotNull(conversations.findById(visual.conversationId)).projectId
        execute(actor, projectId, VisualWorkerCommand(
            VisualWorkerCommand.Operation.INPUT, visual.id, visual.conversationId, handler.generation,
            handler.worker, taskId = requireNotNull(handler.taskId) { "Handler is still starting" }, input = input,
        ))
    }

    override suspend fun cancel(visual: Visual) {
        val handler = visual.handler ?: return
        // Runtime cancellation is durable even when the owning Worker is temporarily offline.
        handler.taskId?.let { taskId ->
            coordinator.requestCommandTaskCancellation(visual.conversationId, taskId, Clock.System.now())
        }
        val worker = workers.find(handler.worker.workerId)
        if (worker?.identity != handler.worker || !worker.isOnline(Clock.System.now() - 30.seconds)) return
        val projectId = conversations.findById(visual.conversationId)?.projectId
        execute(null, projectId, VisualWorkerCommand(
            VisualWorkerCommand.Operation.CANCEL, visual.id, visual.conversationId, handler.generation,
            handler.worker, taskId = handler.taskId,
        ))
    }

    override suspend fun failure(visual: Visual): String? {
        val handler = visual.handler ?: return null
        val worker = workers.find(handler.worker.workerId)
        if (worker != null && worker.identity != handler.worker) {
            return "Handler Worker restarted. This visual is stopped; handlers are never automatically restarted."
        }
        // Gateway disconnects (including a Server restart) are not Worker process exits.
        // Only a new Worker session or a terminal owned task proves the handler is gone.
        val task = handler.taskId?.let { commandState.findCommandTask(visual.conversationId, it) }
        if (task?.isTerminal == true && task.completedAt?.let { Clock.System.now() - it > 15.seconds } == true) {
            return "Handler stopped without a final visual update${task.exitCode?.let { " (exit $it)" }.orEmpty()}."
        }
        return null
    }

    private suspend fun execute(actor: User?, projectId: Project.Id?, request: VisualWorkerCommand): VisualWorkerResult {
        val worker = workers.find(request.worker.workerId)
        require(worker?.identity == request.worker && worker.isOnline(Clock.System.now() - 30.seconds)) { "Handler Worker session changed or is offline" }
        val bytes = requests.execute(
            workerId = request.worker.workerId, operation = WorkerGatewayOperation.VISUAL_COMMAND,
            payload = json.encodeToString(request).encodeToByteArray(),
            policy = WorkerRequestPolicy(deliveryTtlMillis = 10_000, executionTimeoutMillis = 60_000, waitTimeoutMillis = 75_000),
            actorUserId = actor?.id, projectId = projectId,
        )
        return json.decodeFromString(bytes.decodeToString())
    }
}

@Service
class ConversationVisualInteractionDelivery(
    private val ingress: ConversationRuntimeIngressService,
) : VisualInteractionDelivery {
    override suspend fun send(actor: User, visual: Visual, interaction: Conversation.Message.ContentItem.VisualInteraction): Boolean {
        val eventId = interaction.eventId
        val message = Conversation.Message(
            id = Conversation.Message.Id("visual:${visual.id}:$eventId"),
            conversationId = visual.conversationId,
            role = Conversation.Message.Role.USER,
            author = Conversation.Message.Author.User(actor.id, actor.displayName),
            content = listOf(interaction),
            providerMetadata = buildJsonObject { put("inputSource", "visual_action"); put("visualId", visual.id) },
            createdAt = Clock.System.now(),
        )
        require(actor.canUseAi) { "AI access is not allowed for this user" }
        val agentId = visual.agentDefinitionId ?: error("No agent owns this Visual. Create it from the intended agent or attach a command handler.")
        return ingress.enqueueAgentInvocation(actor, visual.conversationId, message, agentId, QueuedMessagePlacement.AFTER_TOOL_RESULT)
    }
}

@Service
class ClientVisualHighlightDelivery(private val clients: ClientPresentationRegistry) : VisualHighlightDelivery {
    override suspend fun send(userId: User.Id, command: VisualHighlightCommand): Int =
        clients.presentVisualHighlight(userId, command)
}

@Service
class VisualOutputGatewayHandler(
    private val visuals: VisualApplicationService,
    private val workers: ConversationRuntimeWorkerRegistry,
) : WorkerGatewayServerRequestHandler {
    override val operation = WorkerGatewayOperation.VISUAL_OUTPUT
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override suspend fun execute(identity: ConversationRuntimeWorkerIdentity, request: WorkerGatewayMessage.Request): ByteArray {
        require(workers.find(identity.workerId)?.identity == identity) { "Stale visual output Worker session" }
        require(request.payload.size <= 131_072) { "Visual output request is too large" }
        val output = json.decodeFromString<VisualWorkerOutput>(request.payload.decodeToString(throwOnInvalidSequence = true))
        val active = visuals.acceptOutput(identity, output.visualId, output.conversationId, output.generation,
            output.task, output.records, output.finished)
        return json.encodeToString(VisualWorkerOutputResult(active)).encodeToByteArray()
    }
}
