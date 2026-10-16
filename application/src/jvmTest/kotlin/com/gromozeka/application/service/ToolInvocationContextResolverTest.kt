package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.domain.tool.AiToolContextField.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class ToolInvocationContextResolverTest {
    @Test fun renameDerivesProjectAndThreadWithoutChoosingAgentOrMount() = runBlocking<Unit> {
        val f = fixture()
        val resolved = f.resolve(f.rename, """{"display_name":"Renamed","_grz":{"conversation_id":"c"}}""")
        assertEquals("c", resolved.context.getString(TOOL_CONTEXT_CONVERSATION_ID))
        assertEquals("p", resolved.context.getString(TOOL_CONTEXT_PROJECT_ID))
        assertEquals("thread", resolved.context.getString(TOOL_CONTEXT_THREAD_ID))
        assertEquals("user", resolved.context.getString(TOOL_CONTEXT_USER_ID))
        assertNull(resolved.context.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID))
        assertNull(resolved.context.getString(TOOL_CONTEXT_WORKSPACE_MOUNT_ID))
        assertEquals(ConversationRuntimeTaskTarget.Server, resolved.target)
        assertEquals("""{"display_name":"Renamed"}""", resolved.input.arguments.toString())
        Mockito.verify(f.projects).requirePermission(f.user.id, f.project.id, ProjectPermission.READ)
        Mockito.verifyNoInteractions(f.workspaces, f.workers, f.agents, f.coordinator)
    }

    @Test fun fileResolvesExactMountWorkerRootAndBothAccessBoundaries() = runBlocking<Unit> {
        val f = fixture()
        val resolved = f.resolve(f.file, """{"file_path":"README.md","_grz":{"workspace_mount_id":"mount","wait_timeout_seconds":1}}""")
        val target = assertIs<ConversationRuntimeTaskTarget.Worker>(resolved.target)
        assertEquals("worker", target.workerId.value)
        assertEquals(f.mount.id, target.workspaceMountId)
        assertEquals(1000L, target.requestPolicy?.waitTimeoutMillis)
        assertEquals("/synthetic/root", resolved.context.getString(TOOL_CONTEXT_WORKSPACE_ROOT_PATH))
        assertEquals("p", resolved.context.getString(TOOL_CONTEXT_PROJECT_ID))
        assertNull(resolved.context.getString(TOOL_CONTEXT_CONVERSATION_ID))
        Mockito.verify(f.workspaces, Mockito.times(1)).resolveExecution(f.mount.id)
        Mockito.verify(f.projects).requirePermission(f.user.id, f.project.id, ProjectPermission.WRITE)
        Mockito.verify(f.workers).requirePermission(f.user, target.workerId, WorkerPermission.USE, f.project.id)
        Mockito.verify(f.workers).requireProjectAccess(target.workerId, f.project.id)
        Mockito.verifyNoInteractions(f.conversations, f.agents, f.coordinator)
    }

    @Test fun globalControlRequiresOwnerButNoResourceContext() = runBlocking<Unit> {
        val f = fixture()
        val tool = f.rename.copy(metadata = AiToolMetadata(executionScope = AiToolExecutionScope.SERVER,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER).withContextRequirements(USER))
        assertFailsWith<IllegalArgumentException> { f.resolve(tool, "{}") }
        Mockito.`when`(f.users.findActiveById(f.user.id)).thenReturn(f.user.copy(role = User.Role.OWNER))
        val resolved = f.resolve(tool, "{}")
        assertEquals(mapOf(TOOL_CONTEXT_USER_ID to "user"), resolved.context.getContext())
        f.resolve(tool, "{}", ToolExecutionContext(mapOf(TOOL_CONTEXT_CONVERSATION_ID to "irrelevant", TOOL_CONTEXT_PROJECT_ID to "irrelevant", TOOL_CONTEXT_AGENT_DEFINITION_ID to "irrelevant")))
        Mockito.verifyNoInteractions(f.conversations, f.projects, f.agents, f.workers, f.workspaces, f.coordinator)
    }

    @Test fun unboundSelectorsCannotOverrideAuthenticatedOrBoundContext() = runBlocking<Unit> {
        val f = fixture()
        assertFailsWith<IllegalArgumentException> { f.resolve(f.rename, """{"_grz":{"user_id":"owner","conversation_id":"c"}}""") }
        assertFailsWith<IllegalArgumentException> { f.resolve(f.rename, "{}", ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to "other"))) }
        assertFailsWith<IllegalArgumentException> { f.resolve(f.rename, """{"_grz":{"conversation_id":"other"}}""", ToolExecutionContext(mapOf(TOOL_CONTEXT_CONVERSATION_ID to "c"))) }
        assertFailsWith<IllegalArgumentException> { f.resolve(f.rename, """{"_grz":{"conversation_id":"c"}}""", ToolExecutionContext(mapOf(TOOL_CONTEXT_PROJECT_ID to "foreign"))) }
        assertFailsWith<IllegalArgumentException> { f.resolve(f.file, """{"_grz":{"workspace_mount_id":"mount"}}""", ToolExecutionContext(mapOf(TOOL_CONTEXT_PROJECT_ID to "foreign"))) }
    }

    @Test fun missingOrRevokedAccessDoesNotProducePreparedInvocation() = runBlocking<Unit> {
        val f = fixture()
        Mockito.`when`(f.users.findActiveById(f.user.id)).thenReturn(null)
        assertFailsWith<IllegalStateException> { f.resolve(f.rename, """{"_grz":{"conversation_id":"c"}}""") }
        Mockito.verifyNoInteractions(f.conversations, f.projects)
        Mockito.`when`(f.users.findActiveById(f.user.id)).thenReturn(f.user)
        Mockito.`when`(f.conversations.findById(f.conversation.id)).thenReturn(f.conversation.copy(
            participants = setOf(Conversation.Participant.User(User.Id("not-caller")))))
        assertFailsWith<IllegalArgumentException> { f.resolve(f.rename, """{"_grz":{"conversation_id":"c"}}""") }
        Mockito.`when`(f.conversations.findById(f.conversation.id)).thenReturn(f.conversation)
        Mockito.`when`(f.projects.requirePermission(f.user.id, f.project.id, ProjectPermission.READ)).thenThrow(ProjectAccessDeniedException())
        assertFailsWith<ProjectAccessDeniedException> { f.resolve(f.rename, """{"_grz":{"conversation_id":"c"}}""") }
    }

    @Test fun selectedAgentMustExistAndParticipateAndNoAgentIsGuessed() = runBlocking<Unit> {
        val f = fixture()
        val tool = f.rename.copy(metadata = f.rename.metadata.withContextRequirements(USER, CONVERSATION, AGENT, THREAD))
        assertFailsWith<IllegalArgumentException> { f.resolve(tool, """{"_grz":{"conversation_id":"c"}}""") }
        val resolved = f.resolve(tool, """{"_grz":{"conversation_id":"c","agent_id":"agent"}}""")
        assertEquals("agent", resolved.context.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID))
        Mockito.`when`(f.conversations.findById(f.conversation.id)).thenReturn(f.conversation.copy(participants = setOf(Conversation.Participant.User(f.user.id))))
        assertFailsWith<IllegalArgumentException> { f.resolve(tool, """{"_grz":{"conversation_id":"c","agent_id":"agent"}}""") }
        Mockito.`when`(f.conversations.findById(f.conversation.id)).thenReturn(f.conversation)
        Mockito.`when`(f.agents.findById(f.agent.id)).thenReturn(f.agent.copy(projectId = Project.Id("foreign")))
        assertFailsWith<IllegalArgumentException> { f.resolve(tool, """{"_grz":{"conversation_id":"c","agent_id":"agent"}}""") }
    }

    @Test fun noImplicitMountNamespaceOrCallIdentityAndStaleThreadFailsClosed() = runBlocking<Unit> {
        val f = fixture()
        assertFails { f.resolve(f.file, "{}", ToolExecutionContext(mapOf(TOOL_CONTEXT_CONVERSATION_ID to "c"))) }
        for (field in listOf(MEMORY_NAMESPACE, TOOL_CALL_ID)) {
            val tool = f.rename.copy(metadata = f.rename.metadata.withContextRequirements(USER, CONVERSATION, field))
            assertFailsWith<IllegalArgumentException> { f.resolve(tool, """{"_grz":{"conversation_id":"c"}}""") }
        }
        assertFailsWith<IllegalArgumentException> { f.resolve(f.rename, "{}", ToolExecutionContext(mapOf(TOOL_CONTEXT_CONVERSATION_ID to "c", TOOL_CONTEXT_THREAD_ID to "stale"))) }
    }

    @Test fun taskOwnerResolutionUsesTaskIdentityAndRejectsRetargeting() = runBlocking<Unit> {
        val f = fixture()
        val tool = f.rename.copy(metadata = AiToolMetadata(executionScope = AiToolExecutionScope.COMMAND_TASK_OWNER)
            .withContextRequirements(USER, CONVERSATION))
        val task = CommandTask(CommandTask.Id("task"), f.conversation.id, ConversationRuntimeWorkerId("worker"), f.mount.id,
            command = "synthetic", workingDirectory = f.mount.rootPath, status = CommandTask.Status.COMPLETED,
            processId = 1, processStartedAt = f.now, outputFile = "/synthetic/result", outputBytes = 0,
            createdAt = f.now, updatedAt = f.now)
        Mockito.`when`(f.coordinator.findCommandTask(f.conversation.id, task.id)).thenReturn(task)
        val resolved = f.resolve(tool, """{"task_id":"task","_grz":{"conversation_id":"c"}}""")
        assertEquals(ConversationRuntimeTaskTarget.Worker(task.workerId, task.workspaceMountId), resolved.target)
        assertFailsWith<IllegalArgumentException> { f.resolve(tool, """{"task_id":"task","_grz":{"conversation_id":"c","workspace_mount_id":"other"}}""") }
        Mockito.`when`(f.coordinator.findCommandTask(f.conversation.id, task.id)).thenReturn(null)
        assertFailsWith<IllegalStateException> { f.resolve(tool, """{"task_id":"task","_grz":{"conversation_id":"c"}}""") }
    }

    @Test fun resolutionPreservesCancellation() = runBlocking<Unit> {
        val f = fixture()
        val cancelled = CancellationException("synthetic")
        val binding = ToolExecutionContext(cancellationSignal = ToolCancellationSignal { throw cancelled })
        assertSame(cancelled, assertFailsWith<CancellationException> { f.resolve(f.rename, "{}", binding) })
        Mockito.verifyNoInteractions(f.users, f.conversations, f.workspaces)
    }

    private suspend fun fixture() = Fixture().apply {
        Mockito.`when`(users.findActiveById(user.id)).thenReturn(user)
        Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
        Mockito.`when`(workspaces.resolveExecution(mount.id)).thenReturn(WorkspaceExecutionContext(project, workspace, mount))
        Mockito.`when`(agents.findById(agent.id)).thenReturn(agent)
    }
    private class Fixture {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val user = User(User.Id("user"), displayName = "Synthetic", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
        val project = Project(Project.Id("p"), "Synthetic", createdAt = now, lastUsedAt = now)
        val agent = AgentDefinition(AgentDefinition.Id("agent"), project.id, "Synthetic", emptyList(), runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("synthetic")), type = AgentDefinition.Type.Project, createdAt = now, updatedAt = now)
        val conversation = Conversation(Conversation.Id("c"), project.id, setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(agent.id)), currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)
        val workspace = Workspace(Workspace.Id("workspace"), project.id, "Synthetic", Workspace.Kind.FILESYSTEM, now, now)
        val mount = WorkspaceMount(WorkspaceMount.Id("mount"), workspace.id, "worker", "/synthetic/root", now, now)
        val users = Mockito.mock(UserDirectoryService::class.java)
        val conversations = Mockito.mock(ConversationRepository::class.java)
        val projects = Mockito.mock(ProjectAccessService::class.java)
        val agents = Mockito.mock(AgentDomainService::class.java)
        val workspaces = Mockito.mock(WorkspaceDomainService::class.java)
        val workers = Mockito.mock(WorkerAccessService::class.java)
        val coordinator = Mockito.mock(ConversationRuntimeCoordinator::class.java)
        val service = ToolInvocationContextResolver(users, conversations, projects, agents, workspaces, workers, coordinator)
        val rename = AiToolDescriptor(AiToolDefinition("grz_conversation_rename", "Rename", "{}"), AiToolMetadata(executionScope = AiToolExecutionScope.SERVER).withContextRequirements(USER, CONVERSATION))
        val file = rename.copy(definition = rename.definition.copy(name = "grz_read_file"), metadata = AiToolMetadata(executionScope = AiToolExecutionScope.WORKSPACE))
        suspend fun resolve(tool: AiToolDescriptor, input: String, bindings: ToolExecutionContext = ToolExecutionContext()) =
            service.resolve(tool, Json.parseToJsonElement(input), user.id, bindings)
    }
}
