package com.gromozeka.server

import com.gromozeka.application.service.*
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.time.Clock

/** Real catalog/resolver/executor/request service. Synthetic identity/storage and a wire-level Worker peer. */
internal class UnifiedMcpTestFixture(val provider: AiToolProvider) {
    val now = Clock.System.now()
    val user = testControlMcpCaller().user
    val project = Project(Project.Id("synthetic-project"), "Synthetic", createdAt = now, lastUsedAt = now)
    val agent = AgentDefinition(AgentDefinition.Id("synthetic-agent"), project.id, "Synthetic", emptyList(),
        runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("synthetic-model")),
        type = AgentDefinition.Type.Project, createdAt = now, updatedAt = now)
    val conversation = Conversation(Conversation.Id("synthetic-conversation"), project.id,
        setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(agent.id)),
        currentThread = Conversation.Thread.Id("synthetic-thread"), createdAt = now, updatedAt = now)
    val identity = ConversationRuntimeWorkerIdentity(ConversationRuntimeWorkerId("synthetic-worker"), ConversationRuntimeWorkerSessionId("synthetic-session"))
    val workspace = Workspace(Workspace.Id("synthetic-workspace"), project.id, "Synthetic", Workspace.Kind.FILESYSTEM, now, now)
    val mount = WorkspaceMount(WorkspaceMount.Id("synthetic-mount"), workspace.id, identity.workerId.value, "/synthetic/unused-root", now, now)
    val users = mock<UserDirectoryService>()
    val conversations = mock<ConversationRepository>()
    val projects = mock<ProjectAccessService>()
    val agents = mock<AgentDomainService>()
    val workspaces = mock<WorkspaceDomainService>()
    val workerAccess = mock<WorkerAccessService>()
    val registry = mock<ConversationRuntimeWorkerRegistry>()
    val coordinator = mock<ConversationRuntimeCoordinator>()
    val namedSecrets = mock<NamedSecretApplicationService>()
    var registrations = emptyList<ConversationRuntimeWorkerRegistration>()
    var visibleWorkers = emptyList<WorkerResource>()
    val contractRepository = McpTestContractRepository()
    val catalog = DistributedAiToolCatalog(registry, workspaces, provider, workerAccess, contractRepository)
    val contexts = ToolInvocationContextResolver(users, conversations, projects, agents, workspaces, workerAccess, coordinator)
    val records = TestWorkerRequestRepository()
    val requestAuthorization = WorkerRequestAuthorization { record ->
        val actor = requireNotNull(users.findActiveById(requireNotNull(record.actorUserId)))
        record.projectId?.let { projects.requirePermission(actor.id, it, ProjectPermission.READ) }
        workerAccess.requirePermission(actor, record.workerId, WorkerPermission.USE, record.projectId)
    }
    val requests = WorkerRequestService(records, requestAuthorization)
    val gateway = GatewayWorkerToolExecutionClient(requests, 1800000, mock<SlotApplicationService>())
    val executor = ParallelToolExecutor(provider, AutoApproveToolApprovalService())
    val service = GromozekaMcpToolService(catalog, contexts, users, agents,
        ToolSecretResolutionService(namedSecrets), executor,
        ConversationRuntimeExecutorDescriptor(ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("synthetic-server")),
            setOf(ConversationRuntimeCapability.TOOL_EXECUTION)),
        DefaultConversationRuntimeWorkerTargetResolver(registry), gateway)
    val factory = GromozekaMcpServerFactory(service)
    val received = mutableListOf<WorkerToolExecutionRequest>()
    var workerTools = emptyList<AiToolCallback>()
    private val wireJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    suspend fun initialize(): UnifiedMcpTestFixture {
        for (role in User.Role.entries) {
            val actor = testControlMcpCaller(role).user
            Mockito.`when`(users.findActiveById(actor.id)).thenReturn(actor)
        }
        Mockito.`when`(registry.list()).thenAnswer { registrations }
        Mockito.`when`(registry.find(identity.workerId)).thenAnswer { registrations.find { it.identity.workerId == identity.workerId } }
        for (role in User.Role.entries) {
            Mockito.`when`(workerAccess.listAccessible(testControlMcpCaller(role).user)).thenAnswer { visibleWorkers }
        }
        Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
        Mockito.`when`(agents.findById(agent.id)).thenReturn(agent)
        Mockito.`when`(workspaces.resolveExecution(mount.id)).thenReturn(WorkspaceExecutionContext(project, workspace, mount))
        return this
    }

    fun advertise(vararg tools: AiToolCallback) {
        workerTools = tools.toList()
        registrations = listOf(ConversationRuntimeWorkerRegistration(identity,
            capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION, ConversationRuntimeCapability.LOCAL_AGENT_TOOL),
            tools = tools.map { AiToolDescriptor(it.definition, it.metadata) },
            environmentProfile = mock<WorkerEnvironmentProfile>(), version = "synthetic", startedAt = now, lastHeartbeatAt = now))
        visibleWorkers = listOf(WorkerResource(identity.workerId, "Synthetic", user.id,
            runtimeWideAccess = false, status = WorkerResource.Status.ACTIVE, createdAt = now, updatedAt = now))
    }

    /** No socket/process or model. Actual persisted request -> strict wire -> executor -> strict result -> accept. */
    suspend fun <T> withWorker(block: suspend () -> T): T = coroutineScope {
        val session = WorkerGatewaySession(identity)
        val delivery = launch { requests.deliver(session) }
        val peer = launch {
            val workerProvider = object : AiToolProvider { override fun getTools() = workerTools }
            val workerExecutor = ParallelToolExecutor(workerProvider, AutoApproveToolApprovalService())
            while (isActive) {
                val request = session.outgoingMessages().receive() as WorkerGatewayMessage.Request
                check(request.operation == WorkerGatewayOperation.TOOL_EXECUTION)
                val input = wireJson.decodeFromString<WorkerToolExecutionRequest>(request.payload.decodeToString())
                received += input
                val result = workerExecutor.executeParallel(input.toolCalls, ToolExecutionContext(input.toolContext), null,
                    ConversationRuntimeExecutorIdentity.Worker(identity), input.executionTarget, input.resolvedSecretsByToolCallId)
                val payload = wireJson.encodeToString(WorkerToolExecutionResponse.serializer(),
                    WorkerToolExecutionResponse(result.results, result.returnDirect, result.structuredContentByCallId)).encodeToByteArray()
                requests.accept(identity.workerId, WorkerGatewayMessage.Response(request.id, WorkerGatewayMessage.Response.Status.SUCCEEDED, payload))
            }
        }
        try { withTimeout(10000) { block() } } finally {
            peer.cancelAndJoin(); delivery.cancelAndJoin()
        }
    }

    suspend fun call(name: String, input: String = "{}") = service.call(user.id, name, Json.parseToJsonElement(input).jsonObject)
    companion object {
        inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
    }
}

internal suspend fun testUnifiedMcpFactory(provider: AiToolProvider): GromozekaMcpServerFactory =
    UnifiedMcpTestFixture(provider).initialize().factory

internal class McpTestContractRepository : AiToolContractRepository {
    private val values = linkedMapOf<String, AiToolContract>()
    override suspend fun findAll() = values.values.toList()
    override suspend fun resolveAll(descriptors: Collection<AiToolDescriptor>) = descriptors.distinct().map { descriptor ->
        val fingerprint = descriptor.contractFingerprint()
        values.getOrPut(fingerprint) {
            val variant = values.values.count { it.logicalName == descriptor.definition.name } + 1
            AiToolContract(fingerprint, descriptor.definition.name,
                descriptor.definition.name + if (variant == 1) "" else "__v$variant", variant, descriptor, Clock.System.now())
        }.also { check(it.descriptor == descriptor) }
    }
}

internal fun mcpTestProvider(vararg tools: AiToolCallback) = object : AiToolProvider {
    override fun getTools() = tools.toList()
}

internal fun mcpTestTool(
    name: String,
    scope: AiToolExecutionScope = AiToolExecutionScope.SERVER,
    requirements: AiToolContextRequirements = AiToolContextRequirements(),
    source: String = "gromozeka",
    result: suspend (String, ToolExecutionContext?) -> AiToolCallResult = { _, _ -> AiToolCallResult(listOf(AiToolResult.Text("ok"))) },
): AiToolCallback = object : AiToolCallback {
    override val definition = AiToolDefinition(name, "Synthetic $name", """{"type":"object","properties":{"text":{"type":"string"}},"additionalProperties":false}""", source)
    override val metadata = AiToolMetadata(executionScope = scope, contextRequirements = requirements, logInput = false)
    override fun call(toolInput: String, context: ToolExecutionContext?): String = error("Typed callback expected")
    override suspend fun callResult(toolInput: String, context: ToolExecutionContext?) = result(toolInput, context)
}
