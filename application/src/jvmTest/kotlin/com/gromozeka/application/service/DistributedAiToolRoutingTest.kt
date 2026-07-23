package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.LocalAgentToolMetadata
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

    @Test
    fun `conversation runtime tools route to current worker without explicit target`() = runBlocking {
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        registerWorker(workerRegistry, "worker-a", listOf(conversationRuntimeTool))
        registerWorker(workerRegistry, "worker-b", listOf(conversationRuntimeTool))
        val workspaceService = TestWorkspaceDomainService(
            projects = listOf(project),
            workspaces = emptyList(),
            mounts = emptyList(),
        )
        val catalog = DistributedAiToolCatalog(workerRegistry, workspaceService).snapshot(project)
        val routing = ConversationRuntimeToolRoutingService(
            runtimeCoordinator = InMemoryConversationRuntimeCoordinator(),
            workspaceService = workspaceService,
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
            runtimeWorkerId = ConversationRuntimeWorkerId("worker-b"),
        )
        val rejected = routing.route(
            conversation = conversation(project.id),
            project = project,
            toolCalls = listOf(call),
            catalog = catalog,
        )

        assertIs<ConversationRuntimeToolRoutingResult.Accepted>(accepted)
        assertEquals(ConversationRuntimeWorkerId("worker-b"), accepted.requirements.target?.workerId)
        assertIs<ConversationRuntimeToolRoutingResult.Rejected>(rejected)
        assertTrue(rejected.errors.single().message.contains("unavailable"))
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

        val snapshot = DistributedAiToolCatalog(workerRegistry, workspaceService)
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
        val catalog = DistributedAiToolCatalog(workerRegistry, workspaceService)
            .snapshot(project)
        val routing = ConversationRuntimeToolRoutingService(
            runtimeCoordinator = InMemoryConversationRuntimeCoordinator(),
            workspaceService = workspaceService,
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
        assertEquals(ConversationRuntimeWorkerId("worker-b"), accepted.requirements.target?.workerId)
        assertEquals(mountB.id, accepted.requirements.target?.workspaceMountId)
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
        val catalog = DistributedAiToolCatalog(workerRegistry, workspaceService)

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
                        ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                        ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
                    ),
                    tools = tools,
                    version = "test",
                    startedAt = registeredAt,
                    lastHeartbeatAt = registeredAt,
                ),
                staleBefore = registeredAt,
            )
        )
    }

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
            workerId: String,
            rootPath: String,
        ): WorkspaceExecutionContext? =
            mounts.singleOrNull { it.workerId == workerId && it.rootPath == rootPath }
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
