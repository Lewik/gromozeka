package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.WorkerProjectGrant
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.WorkerUserGrant
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.service.WorkerAccessDeniedException
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.CommandMonitorOwnerToolMetadata
import com.gromozeka.domain.tool.CommandTaskOwnerToolMetadata
import com.gromozeka.domain.tool.LocalAgentToolMetadata
import com.gromozeka.domain.tool.WorkerInspectionToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DistributedAiToolRoutingTest {
    private val now = Clock.System.now()
    private val project = project("project-a")
    private val otherProject = project("project-b")
    private val workspaceA = workspace("workspace-a", project.id)
    private val workspaceB = workspace("workspace-b", project.id)
    private val foreignWorkspace = workspace("workspace-foreign", otherProject.id)
    private val workerAccessService = TestProjectWorkerAccessService(
        setOf("worker-a", "worker-b", "foreign-worker")
    )
    private val workspaceTool = AiToolDescriptor(
        definition = AiToolDefinition(
            name = "grz_read_file",
            description = "Read a file.",
            inputSchema = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
        ),
        metadata = LocalAgentToolMetadata,
    )
    private val conversationRuntimeTool = AiToolDescriptor(
        definition = AiToolDefinition(
            name = "activate_test_skill",
            description = "Activate a test skill.",
            inputSchema = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
        ),
        metadata = AiToolMetadata(executionScope = AiToolExecutionScope.CONVERSATION_RUNTIME),
    )
    private val monitorCommandTool = AiToolDescriptor(
        definition = AiToolDefinition(
            name = "grz_monitor_command",
            description = "Monitor a command task.",
            inputSchema = """{"type":"object","properties":{"task_id":{"type":"string"}},"required":["task_id"]}""",
        ),
        metadata = CommandTaskOwnerToolMetadata,
    )
    private val getCommandMonitorTool = AiToolDescriptor(
        definition = AiToolDefinition(
            name = "grz_get_command_monitor",
            description = "Read a command monitor.",
            inputSchema = """{"type":"object","properties":{"monitor_id":{"type":"string"}},"required":["monitor_id"]}""",
        ),
        metadata = CommandMonitorOwnerToolMetadata,
    )
    private val workerEnvironmentTool = AiToolDescriptor(
        definition = AiToolDefinition(
            name = "grz_get_worker_environment",
            description = "Inspect a worker.",
            inputSchema = """{"type":"object","properties":{"executable_names":{"type":"array","items":{"type":"string"}}}}""",
        ),
        metadata = WorkerInspectionToolMetadata,
    )

    @Test
    fun `conversation runtime tools execute on Server without an explicit target`() = runBlocking {
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        val workspaceService = TestWorkspaceDomainService(
            projects = listOf(project),
            workspaces = emptyList(),
            mounts = emptyList(),
        )
        val catalog = distributedCatalog(
            workerRegistry = workerRegistry,
            workspaceService = workspaceService,
            serverTools = listOf(conversationRuntimeTool),
        ).snapshot(project)
        val routing = ConversationRuntimeToolRoutingService(
            runtimeCoordinator = InMemoryConversationRuntimeCoordinator(),
            workspaceService = workspaceService,
            workerAccessService = workerAccessService,
        )
        val call = Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id("activate-skill"),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = conversationRuntimeTool.definition.name,
                input = JsonObject(mapOf("name" to JsonPrimitive("test-skill"))),
            ),
        )

        val accepted = routing.route(
            conversation = conversation(project.id),
            project = project,
            toolCalls = listOf(call),
            catalog = catalog,
        )
        val explicitTargetCall = call.copy(
            call = call.call.copy(
                input = JsonObject(
                    call.call.input.jsonObject + (
                        AI_TOOL_EXECUTION_TARGET_FIELD to buildJsonObject {
                            put(AI_TOOL_EXECUTION_WORKER_ID_FIELD, "worker-a")
                        }
                    )
                )
            )
        )
        val rejected = routing.route(
            conversation = conversation(project.id),
            project = project,
            toolCalls = listOf(explicitTargetCall),
            catalog = catalog,
        )

        assertIs<ConversationRuntimeToolRoutingResult.Accepted>(accepted)
        assertEquals(ConversationRuntimeTaskTarget.Server, accepted.requirements.target)
        assertIs<ConversationRuntimeToolRoutingResult.Rejected>(rejected)
        assertTrue(rejected.errors.single().message.contains("must not declare execution_target"))
    }

    @Test
    fun `worker inspection routes to the exact selected worker`() = runBlocking {
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        registerWorker(workerRegistry, "worker-a", listOf(workerEnvironmentTool))
        registerWorker(workerRegistry, "worker-b", listOf(workerEnvironmentTool))
        val workspaceService = TestWorkspaceDomainService(
            projects = listOf(project),
            workspaces = emptyList(),
            mounts = emptyList(),
        )
        val catalog = distributedCatalog(workerRegistry, workspaceService).snapshot(project)
        val call = Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id("inspect-worker-b"),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = workerEnvironmentTool.definition.name,
                input = buildJsonObject {
                    putJsonObject(AI_TOOL_EXECUTION_TARGET_FIELD) {
                        put(AI_TOOL_EXECUTION_WORKER_ID_FIELD, "worker-b")
                    }
                },
            ),
        )

        val result = ConversationRuntimeToolRoutingService(
            runtimeCoordinator = InMemoryConversationRuntimeCoordinator(),
            workspaceService = workspaceService,
            workerAccessService = workerAccessService,
        ).route(
            conversation = conversation(project.id),
            project = project,
            toolCalls = listOf(call),
            catalog = catalog,
        )

        assertIs<ConversationRuntimeToolRoutingResult.Accepted>(result)
        val target = assertIs<ConversationRuntimeTaskTarget.Worker>(result.requirements.target)
        assertEquals(ConversationRuntimeWorkerId("worker-b"), target.workerId)
        assertEquals(null, target.workspaceMountId)
        val schema = Json.parseToJsonElement(
            catalog.tools.single().definition.inputSchema
        ).jsonObject
        val targetSchema = schema.getValue("properties").jsonObject
            .getValue(AI_TOOL_EXECUTION_TARGET_FIELD).jsonObject
        assertEquals(
            setOf(AI_TOOL_EXECUTION_WORKER_ID_FIELD),
            targetSchema.getValue("properties").jsonObject.keys,
        )
    }

    @Test
    fun `catalog exposes only workspace mounts from current project`() = runBlocking {
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        registerWorker(workerRegistry, "worker-a", listOf(workspaceTool))
        registerWorker(workerRegistry, "worker-b", listOf(workspaceTool))
        registerWorker(workerRegistry, "foreign-worker", listOf(workspaceTool))
        val workspaceService = TestWorkspaceDomainService(
            projects = listOf(project, otherProject),
            workspaces = listOf(workspaceA, workspaceB, foreignWorkspace),
            mounts = listOf(
                mount(workspaceA.id, "worker-a", "/checkout/a"),
                mount(foreignWorkspace.id, "worker-a", "/foreign/also-mounted"),
                mount(workspaceB.id, "worker-b", "/checkout/b"),
                mount(foreignWorkspace.id, "foreign-worker", "/foreign/only"),
            ),
        )

        val snapshot = distributedCatalog(workerRegistry, workspaceService)
            .snapshot(project)

        val entry = snapshot.entries.getValue(workspaceTool.definition.name)
        assertEquals(
            mapOf(
                "worker-a" to setOf("mount-workspace-a-worker-a"),
                "worker-b" to setOf("mount-workspace-b-worker-b"),
            ),
            entry.workers.associate { worker ->
                worker.workerId.value to worker.workspaceMounts.mapTo(mutableSetOf()) { it.id.value }
            },
        )
        assertFalse(snapshot.environmentPrompt.contains("workspace-foreign"))
        assertFalse(snapshot.environmentPrompt.contains("/foreign"))
        assertTrue(snapshot.environmentPrompt.contains("\"os_family\":\"linux\""))
        assertTrue(snapshot.environmentPrompt.contains("\"os_name\":\"Test Linux\""))
        assertTrue(snapshot.environmentPrompt.contains("\"architecture\":\"x86_64\""))
        assertTrue(snapshot.environmentPrompt.contains("\"available_executables\":[\"git\",\"sh\"]"))
        assertTrue(snapshot.environmentPrompt.contains("use grz_get_worker_environment"))

        val schema = Json.parseToJsonElement(snapshot.tools.single().definition.inputSchema).jsonObject
        val targetSchema = schema["properties"]
            ?.jsonObject
            ?.get(AI_TOOL_EXECUTION_TARGET_FIELD)
            ?.jsonObject
            ?: error("Execution target schema is missing")
        assertEquals(
            setOf(AI_TOOL_EXECUTION_WORKSPACE_MOUNT_ID_FIELD),
            targetSchema.getValue("properties").jsonObject.keys,
        )
        assertFalse(targetSchema.toString().contains("mount-workspace-a-worker-a"))
    }

    @Test
    fun `catalog and routing reject workers unavailable to the project`() = runBlocking {
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        registerWorker(workerRegistry, "worker-a", listOf(workerEnvironmentTool))
        registerWorker(workerRegistry, "worker-b", listOf(workerEnvironmentTool))
        val workspaceService = TestWorkspaceDomainService(
            projects = listOf(project),
            workspaces = emptyList(),
            mounts = emptyList(),
        )
        val restrictedWorkerAccess = TestProjectWorkerAccessService(setOf("worker-a"))
        val restrictedCatalog = distributedCatalog(
            workerRegistry = workerRegistry,
            workspaceService = workspaceService,
            workerAccessService = restrictedWorkerAccess,
        ).snapshot(project)

        assertEquals(
            setOf(ConversationRuntimeWorkerId("worker-a")),
            restrictedCatalog.entries
                .getValue(workerEnvironmentTool.definition.name)
                .workers
                .mapTo(mutableSetOf()) { it.workerId },
        )
        assertFalse(restrictedCatalog.environmentPrompt.contains("worker-b"))

        val staleCatalog = distributedCatalog(workerRegistry, workspaceService).snapshot(project)
        val result = ConversationRuntimeToolRoutingService(
            runtimeCoordinator = InMemoryConversationRuntimeCoordinator(),
            workspaceService = workspaceService,
            workerAccessService = restrictedWorkerAccess,
        ).route(
            conversation = conversation(project.id),
            project = project,
            toolCalls = listOf(
                Conversation.Message.ContentItem.ToolCall(
                    id = Conversation.Message.ContentItem.ToolCall.Id("inspect-worker-b"),
                    call = Conversation.Message.ContentItem.ToolCall.Data(
                        name = workerEnvironmentTool.definition.name,
                        input = buildJsonObject {
                            putJsonObject(AI_TOOL_EXECUTION_TARGET_FIELD) {
                                put(AI_TOOL_EXECUTION_WORKER_ID_FIELD, "worker-b")
                            }
                        },
                    ),
                )
            ),
            catalog = staleCatalog,
        )

        assertIs<ConversationRuntimeToolRoutingResult.Rejected>(result)
        assertTrue(result.errors.single().message.contains("not available to project"))
    }

    @Test
    fun `routing rejects a foreign mount and derives worker from an accepted mount`() = runBlocking {
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        registerWorker(workerRegistry, "worker-a", listOf(workspaceTool))
        registerWorker(workerRegistry, "worker-b", listOf(workspaceTool))
        val mountA = mount(workspaceA.id, "worker-a", "/checkout/a")
        val mountB = mount(workspaceB.id, "worker-b", "/checkout/b")
        val foreignMount = mount(foreignWorkspace.id, "worker-a", "/foreign")
        val workspaceService = TestWorkspaceDomainService(
            projects = listOf(project),
            workspaces = listOf(workspaceA, workspaceB, foreignWorkspace),
            mounts = listOf(mountA, mountB, foreignMount),
        )
        val catalog = distributedCatalog(workerRegistry, workspaceService)
            .snapshot(project)
        val routing = ConversationRuntimeToolRoutingService(
            runtimeCoordinator = InMemoryConversationRuntimeCoordinator(),
            workspaceService = workspaceService,
            workerAccessService = workerAccessService,
        )
        val conversation = conversation(project.id)

        val rejected = routing.route(
            conversation = conversation,
            project = project,
            toolCalls = listOf(toolCall(foreignMount.id)),
            catalog = catalog,
        )

        assertIs<ConversationRuntimeToolRoutingResult.Rejected>(rejected)
        assertTrue(rejected.errors.single().message.contains("offline"))

        val accepted = routing.route(
            conversation = conversation,
            project = project,
            toolCalls = listOf(toolCall(mountB.id)),
            catalog = catalog,
        )

        assertIs<ConversationRuntimeToolRoutingResult.Accepted>(accepted)
        val acceptedTarget = assertIs<ConversationRuntimeTaskTarget.Worker>(accepted.requirements.target)
        assertEquals(ConversationRuntimeWorkerId("worker-b"), acceptedTarget.workerId)
        assertEquals(mountB.id, acceptedTarget.workspaceMountId)
    }

    @Test
    fun `command task owner tool routes to the exact task worker and mount`() = runBlocking {
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        registerWorker(workerRegistry, "worker-a", listOf(monitorCommandTool))
        registerWorker(workerRegistry, "worker-b", listOf(monitorCommandTool))
        val mountA = mount(workspaceA.id, "worker-a", "/checkout/a")
        val mountB = mount(workspaceB.id, "worker-b", "/checkout/b")
        val workspaceService = TestWorkspaceDomainService(
            projects = listOf(project),
            workspaces = listOf(workspaceA, workspaceB),
            mounts = listOf(mountA, mountB),
        )
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val conversation = conversation(project.id)
        val task = commandTask(conversation.id, "task-b", mountB, "worker-b")
        coordinator.upsertCommandTask(task)
        val catalog = distributedCatalog(workerRegistry, workspaceService).snapshot(project)
        val routing = ConversationRuntimeToolRoutingService(coordinator, workspaceService, workerAccessService)

        val accepted = routing.route(
            conversation = conversation,
            project = project,
            toolCalls = listOf(ownerToolCall(monitorCommandTool.definition.name, "task_id", task.id.value)),
            catalog = catalog,
        )

        assertIs<ConversationRuntimeToolRoutingResult.Accepted>(accepted)
        val acceptedTarget = assertIs<ConversationRuntimeTaskTarget.Worker>(accepted.requirements.target)
        assertEquals(task.workerId, acceptedTarget.workerId)
        assertEquals(task.workspaceMountId, acceptedTarget.workspaceMountId)
        val schema = Json.parseToJsonElement(
            catalog.tools.single().definition.inputSchema
        ).jsonObject
        assertFalse(AI_TOOL_EXECUTION_TARGET_FIELD in schema.getValue("properties").jsonObject)
    }

    @Test
    fun `command owner tools reject explicit targets instead of allowing reassignment`() = runBlocking {
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        registerWorker(workerRegistry, "worker-b", listOf(monitorCommandTool))
        val mountB = mount(workspaceB.id, "worker-b", "/checkout/b")
        val workspaceService = TestWorkspaceDomainService(
            projects = listOf(project),
            workspaces = listOf(workspaceB),
            mounts = listOf(mountB),
        )
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val conversation = conversation(project.id)
        val task = commandTask(conversation.id, "task-b", mountB, "worker-b")
        coordinator.upsertCommandTask(task)
        val catalog = distributedCatalog(workerRegistry, workspaceService).snapshot(project)
        val baseCall = ownerToolCall(monitorCommandTool.definition.name, "task_id", task.id.value)
        val call = baseCall.copy(
                call = Conversation.Message.ContentItem.ToolCall.Data(
                    name = monitorCommandTool.definition.name,
                    input = JsonObject(
                        baseCall.call.input.jsonObject + (
                            AI_TOOL_EXECUTION_TARGET_FIELD to buildJsonObject {
                                put(AI_TOOL_EXECUTION_WORKSPACE_MOUNT_ID_FIELD, mountB.id.value)
                            }
                        )
                    ),
                ),
            )

        val result = ConversationRuntimeToolRoutingService(coordinator, workspaceService, workerAccessService).route(
            conversation = conversation,
            project = project,
            toolCalls = listOf(call),
            catalog = catalog,
        )

        assertIs<ConversationRuntimeToolRoutingResult.Rejected>(result)
        assertTrue(result.errors.single().message.contains("must not declare execution_target"))
    }

    @Test
    fun `command monitor owner tool routes to its source worker and rejects another conversation`() = runBlocking {
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        registerWorker(workerRegistry, "worker-b", listOf(getCommandMonitorTool))
        val mountB = mount(workspaceB.id, "worker-b", "/checkout/b")
        val workspaceService = TestWorkspaceDomainService(
            projects = listOf(project),
            workspaces = listOf(workspaceB),
            mounts = listOf(mountB),
        )
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val conversation = conversation(project.id)
        val monitor = commandMonitor(conversation.id, "monitor-b", mountB, "worker-b")
        coordinator.synchronizeCommandMonitor(monitor)
        val catalog = distributedCatalog(workerRegistry, workspaceService).snapshot(project)
        val routing = ConversationRuntimeToolRoutingService(coordinator, workspaceService, workerAccessService)
        val call = ownerToolCall(
            getCommandMonitorTool.definition.name,
            "monitor_id",
            monitor.id.value,
        )

        val accepted = routing.route(conversation, project, listOf(call), catalog)
        val rejected = routing.route(
            conversation.copy(id = Conversation.Id("other-conversation")),
            project,
            listOf(call),
            catalog,
        )

        assertIs<ConversationRuntimeToolRoutingResult.Accepted>(accepted)
        val acceptedTarget = assertIs<ConversationRuntimeTaskTarget.Worker>(accepted.requirements.target)
        assertEquals(monitor.workerId, acceptedTarget.workerId)
        assertEquals(monitor.workspaceMountId, acceptedTarget.workspaceMountId)
        assertIs<ConversationRuntimeToolRoutingResult.Rejected>(rejected)
        assertTrue(rejected.errors.single().message.contains("was not found"))
    }

    @Test
    fun `command monitor owner tool fails when its worker is offline`() = runBlocking {
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        registerWorker(workerRegistry, "worker-a", listOf(getCommandMonitorTool))
        val mountA = mount(workspaceA.id, "worker-a", "/checkout/a")
        val mountB = mount(workspaceB.id, "worker-b", "/checkout/b")
        val workspaceService = TestWorkspaceDomainService(
            projects = listOf(project),
            workspaces = listOf(workspaceA, workspaceB),
            mounts = listOf(mountA, mountB),
        )
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val conversation = conversation(project.id)
        val monitor = commandMonitor(conversation.id, "monitor-b", mountB, "worker-b")
        coordinator.synchronizeCommandMonitor(monitor)
        val catalog = distributedCatalog(workerRegistry, workspaceService).snapshot(project)

        val result = ConversationRuntimeToolRoutingService(coordinator, workspaceService, workerAccessService).route(
            conversation = conversation,
            project = project,
            toolCalls = listOf(
                ownerToolCall(
                    getCommandMonitorTool.definition.name,
                    "monitor_id",
                    monitor.id.value,
                )
            ),
            catalog = catalog,
        )

        assertIs<ConversationRuntimeToolRoutingResult.Rejected>(result)
        assertTrue(result.errors.single().message.contains("offline"))
    }

    @Test
    fun `environment revision ignores heartbeats and changes when a worker goes offline`() = runBlocking {
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        registerWorker(workerRegistry, "worker-a", listOf(workspaceTool))
        val workspaceService = TestWorkspaceDomainService(
            projects = listOf(project),
            workspaces = listOf(workspaceA),
            mounts = listOf(mount(workspaceA.id, "worker-a", "/checkout/a")),
        )
        val catalog = distributedCatalog(workerRegistry, workspaceService)

        val online = catalog.snapshot(project)
        val identity = workerRegistry.find(ConversationRuntimeWorkerId("worker-a"))!!.identity
        assertTrue(workerRegistry.heartbeat(identity, Clock.System.now()))
        val afterHeartbeat = catalog.snapshot(project)

        assertEquals(online.environmentRevision, afterHeartbeat.environmentRevision)
        assertEquals(online.environmentPrompt, afterHeartbeat.environmentPrompt)

        assertTrue(workerRegistry.unregister(identity, Clock.System.now()))
        val offline = catalog.snapshot(project)

        assertNotEquals(online.environmentRevision, offline.environmentRevision)
        assertTrue(offline.entries.isEmpty())
        assertTrue(offline.environmentPrompt.contains("\"status\":\"offline\""))
        assertTrue(offline.environmentPrompt.contains("mount-workspace-a-worker-a"))
    }

    private suspend fun registerWorker(
        registry: InMemoryConversationRuntimeWorkerRegistry,
        workerId: String,
        tools: List<AiToolDescriptor>,
    ) {
        val registeredAt = Clock.System.now()
        assertTrue(
            registry.register(
                registration = ConversationRuntimeWorkerRegistration(
                    identity = ConversationRuntimeWorkerIdentity(
                        workerId = ConversationRuntimeWorkerId(workerId),
                        sessionId = ConversationRuntimeWorkerSessionId("session-$workerId"),
                    ),
                    capabilities = setOf(
                        ConversationRuntimeCapability.TOOL_EXECUTION,
                        ConversationRuntimeCapability.LOCAL_AGENT_TOOL,
                    ),
                    tools = tools,
                    environmentProfile = testWorkerEnvironmentProfile(registeredAt),
                    version = "test",
                    startedAt = registeredAt,
                    lastHeartbeatAt = registeredAt,
                ),
                staleBefore = registeredAt,
            )
        )
    }

    private fun distributedCatalog(
        workerRegistry: InMemoryConversationRuntimeWorkerRegistry,
        workspaceService: WorkspaceDomainService,
        serverTools: List<AiToolDescriptor> = emptyList(),
        workerAccessService: WorkerAccessService = this.workerAccessService,
    ): DistributedAiToolCatalog =
        DistributedAiToolCatalog(
            workerRegistry = workerRegistry,
            workspaceService = workspaceService,
            aiToolProvider = object : AiToolProvider {
                override fun getTools(): List<AiToolCallback> =
                    serverTools.map { descriptor ->
                        object : AiToolCallback {
                            override val definition = descriptor.definition
                            override val metadata = descriptor.metadata

                            override fun call(
                                toolInput: String,
                                context: ToolExecutionContext?,
                            ): String = error("Tool execution is outside this routing test")
                        }
                    }
            },
            workerAccessService = workerAccessService,
        )

    private fun project(id: String): Project =
        Project(
            id = Project.Id(id),
            name = id,
            createdAt = now,
            lastUsedAt = now,
        )

    private fun workspace(
        id: String,
        projectId: Project.Id,
    ): Workspace =
        Workspace(
            id = Workspace.Id(id),
            projectId = projectId,
            name = id,
            kind = Workspace.Kind.FILESYSTEM,
            createdAt = now,
            updatedAt = now,
        )

    private fun mount(
        workspaceId: Workspace.Id,
        workerId: String,
        rootPath: String,
    ): WorkspaceMount =
        WorkspaceMount(
            id = WorkspaceMount.Id("mount-${workspaceId.value}-$workerId"),
            workspaceId = workspaceId,
            workerId = workerId,
            rootPath = rootPath,
            createdAt = now,
            updatedAt = now,
        )

    private fun conversation(projectId: Project.Id): Conversation =
        Conversation(
            id = Conversation.Id("conversation-1"),
            projectId = projectId,
            agentDefinitionId = AgentDefinition.Id("agent-1"),
            currentThread = Conversation.Thread.Id("thread-1"),
            createdAt = now,
            updatedAt = now,
        )

    private fun toolCall(mountId: WorkspaceMount.Id): Conversation.Message.ContentItem.ToolCall =
        Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id("call-${mountId.value}"),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = workspaceTool.definition.name,
                input = JsonObject(
                    mapOf(
                        "path" to JsonPrimitive("README.md"),
                        AI_TOOL_EXECUTION_TARGET_FIELD to buildJsonObject {
                            put(AI_TOOL_EXECUTION_WORKSPACE_MOUNT_ID_FIELD, mountId.value)
                        },
                    )
                ),
            ),
        )

    private fun ownerToolCall(
        toolName: String,
        ownerField: String,
        ownerId: String,
    ): Conversation.Message.ContentItem.ToolCall =
        Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id("call-$toolName-$ownerId"),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = toolName,
                input = buildJsonObject { put(ownerField, ownerId) },
            ),
        )

    private fun commandTask(
        conversationId: Conversation.Id,
        id: String,
        mount: WorkspaceMount,
        workerId: String,
    ): CommandTask =
        CommandTask(
            id = CommandTask.Id(id),
            conversationId = conversationId,
            workerId = ConversationRuntimeWorkerId(workerId),
            workspaceMountId = mount.id,
            command = "sleep 60",
            workingDirectory = mount.rootPath,
            status = CommandTask.Status.WORKING,
            processId = 100,
            processStartedAt = now,
            outputFile = "/tmp/$id.log",
            outputBytes = 0,
            createdAt = now,
            updatedAt = now,
        )

    private fun commandMonitor(
        conversationId: Conversation.Id,
        id: String,
        mount: WorkspaceMount,
        workerId: String,
    ): CommandMonitor =
        CommandMonitor(
            id = CommandMonitor.Id(id),
            conversationId = conversationId,
            commandTaskId = CommandTask.Id("source-$id"),
            workerId = ConversationRuntimeWorkerId(workerId),
            workspaceMountId = mount.id,
            filterCommand = "grep ready",
            mode = CommandMonitor.Mode.CONTINUOUS,
            startFrom = CommandMonitor.StartFrom.NOW,
            status = CommandMonitor.Status.WORKING,
            sourceOutputCursor = 0,
            processId = 101,
            processStartedAt = now,
            outputFile = "/tmp/$id.log",
            errorFile = "/tmp/$id.err",
            outputBytes = 0,
            eventOutputCursor = 0,
            createdAt = now,
            updatedAt = now,
        )

    private inner class TestProjectWorkerAccessService(
        workerIds: Set<String>,
    ) : WorkerAccessService {
        private val workers = workerIds.associate { workerId ->
            val id = ConversationRuntimeWorkerId(workerId)
            id to WorkerResource(
                id = id,
                displayName = workerId,
                ownerUserId = User.Id("test-owner"),
                organizationAccess = false,
                status = WorkerResource.Status.ACTIVE,
                createdAt = now,
                updatedAt = now,
            )
        }

        override suspend fun findAccessible(
            actor: User,
            workerId: ConversationRuntimeWorkerId,
            projectId: Project.Id?,
        ): WorkerResource? = workers[workerId]

        override suspend fun listAccessible(actor: User): List<WorkerResource> = workers.values.toList()

        override suspend fun listAvailableToProject(projectId: Project.Id): List<WorkerResource> =
            workers.values.toList()

        override suspend fun requirePermission(
            actor: User,
            workerId: ConversationRuntimeWorkerId,
            permission: WorkerPermission,
            projectId: Project.Id?,
        ): WorkerResource = workers[workerId] ?: throw WorkerAccessDeniedException()

        override suspend fun requireProjectAccess(
            workerId: ConversationRuntimeWorkerId,
            projectId: Project.Id,
        ): WorkerResource = workers[workerId] ?: throw WorkerAccessDeniedException()

        override suspend fun listUserGrants(
            actor: User,
            workerId: ConversationRuntimeWorkerId,
        ): List<WorkerUserGrant> = emptyList()

        override suspend fun grantUser(
            actor: User,
            workerId: ConversationRuntimeWorkerId,
            userId: User.Id,
        ): WorkerUserGrant = error("Worker grants are outside this test")

        override suspend fun revokeUser(
            actor: User,
            workerId: ConversationRuntimeWorkerId,
            userId: User.Id,
        ): Boolean = error("Worker grants are outside this test")

        override suspend fun listProjectGrants(
            actor: User,
            workerId: ConversationRuntimeWorkerId,
        ): List<WorkerProjectGrant> = emptyList()

        override suspend fun grantProject(
            actor: User,
            workerId: ConversationRuntimeWorkerId,
            projectId: Project.Id,
        ): WorkerProjectGrant = error("Worker grants are outside this test")

        override suspend fun revokeProject(
            actor: User,
            workerId: ConversationRuntimeWorkerId,
            projectId: Project.Id,
        ): Boolean = error("Worker grants are outside this test")

        override suspend fun setOrganizationAccess(
            actor: User,
            workerId: ConversationRuntimeWorkerId,
            enabled: Boolean,
        ): WorkerResource = error("Worker access changes are outside this test")

        override suspend fun revokeWorker(
            actor: User,
            workerId: ConversationRuntimeWorkerId,
        ): WorkerResource = error("Worker revocation is outside this test")
    }

    private class TestWorkspaceDomainService(
        projects: List<Project>,
        workspaces: List<Workspace>,
        private val mounts: List<WorkspaceMount>,
    ) : WorkspaceDomainService {
        private val projectsById = projects.associateBy { it.id }
        private val workspacesById = workspaces.associateBy { it.id }

        override suspend fun createFilesystemWorkspace(
            projectId: Project.Id,
            name: String,
            id: Workspace.Id?,
        ): Workspace = error("Workspace creation is outside this test")

        override suspend fun createAndMountFilesystemWorkspace(
            projectId: Project.Id,
            name: String,
            workerId: String,
            rootPath: String,
            workspaceId: Workspace.Id?,
            mountId: WorkspaceMount.Id?,
        ): WorkspaceExecutionContext = error("Workspace creation is outside this test")

        override suspend fun attachFilesystem(
            workspaceId: Workspace.Id,
            workerId: String,
            rootPath: String,
            mountId: WorkspaceMount.Id?,
        ): WorkspaceExecutionContext = error("Workspace attachment is outside this test")

        override suspend fun findById(id: Workspace.Id): Workspace? = workspacesById[id]

        override suspend fun findByProject(projectId: Project.Id): List<Workspace> =
            workspacesById.values.filter { it.projectId == projectId }

        override suspend fun findMount(id: WorkspaceMount.Id): WorkspaceMount? =
            mounts.singleOrNull { it.id == id }

        override suspend fun findMount(
            workspaceId: Workspace.Id,
            workerId: String,
        ): WorkspaceMount? =
            mounts.singleOrNull { it.workspaceId == workspaceId && it.workerId == workerId }

        override suspend fun findMounts(workspaceId: Workspace.Id): List<WorkspaceMount> =
            mounts.filter { it.workspaceId == workspaceId }

        override suspend fun findMountsByWorker(workerId: String): List<WorkspaceMount> =
            mounts.filter { it.workerId == workerId }

        override suspend fun findByWorkerPath(
            projectId: Project.Id,
            workerId: String,
            rootPath: String,
        ): WorkspaceExecutionContext? =
            mounts.singleOrNull {
                workspacesById[it.workspaceId]?.projectId == projectId &&
                    it.workerId == workerId &&
                    it.rootPath == rootPath
            }
                ?.toContext()

        override suspend fun resolveExecution(
            mountId: WorkspaceMount.Id,
        ): WorkspaceExecutionContext =
            findMount(mountId)
                ?.toContext()
                ?: error("Workspace mount '${mountId.value}' does not exist")

        override suspend fun resolveRuntime(
            workspaceId: Workspace.Id,
            workerId: String,
        ): RuntimeEnvironmentContext.WorkspaceBound {
            val workspace = workspacesById[workspaceId]
                ?: error("Workspace '${workspaceId.value}' does not exist")
            return RuntimeEnvironmentContext.WorkspaceBound(
                project = projectsById.getValue(workspace.projectId),
                workspace = workspace,
                workerId = workerId,
                localMount = findMount(workspaceId, workerId),
            )
        }

        private fun WorkspaceMount.toContext(): WorkspaceExecutionContext {
            val workspace = workspacesById.getValue(workspaceId)
            return WorkspaceExecutionContext(
                project = projectsById.getValue(workspace.projectId),
                workspace = workspace,
                mount = this,
            )
        }
    }
}
