package com.gromozeka.server

import com.gromozeka.application.service.InMemoryConversationRuntimeCoordinator
import com.gromozeka.application.service.ServerCommandRuntimeStateService
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorLifecycleEventPublisher
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskLifecycleEventPublisher
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.remote.protocol.WorkerCommandRuntimeGatewayCodec
import com.gromozeka.remote.protocol.WorkerCommandRuntimeRequest
import com.gromozeka.remote.protocol.WorkerCommandRuntimeResponse
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorkerCommandRuntimeGatewayHandlerTest {
    @Test
    fun `artifact download checks conversation ownership and chunk boundaries`() = runBlocking {
        val fixture = fixture()
        val conversations = mock<ConversationRepository>()
        val artifacts = mock<com.gromozeka.application.service.ConversationArtifactApplicationService>()
        val access = mock<WorkerAccessService>()
        val bytes = byteArrayOf(0, -1, 32)
        val artifact = com.gromozeka.domain.model.Artifact(
            id = com.gromozeka.domain.model.Artifact.Id("artifact"), projectId = fixture.conversation.projectId,
            conversationId = fixture.conversation.id, createdByUserId = null, fileName = "data.bin",
            mediaType = "application/octet-stream", sizeBytes = 3,
            source = com.gromozeka.domain.model.Artifact.ContentSource.Managed("a".repeat(64)),
            purpose = com.gromozeka.domain.model.Artifact.Purpose.TOOL_OUTPUT,
            state = com.gromozeka.domain.model.Artifact.State.COMMITTED, createdAt = fixture.now, committedAt = fixture.now,
        )
        Mockito.`when`(conversations.findById(fixture.conversation.id)).thenReturn(fixture.conversation)
        Mockito.`when`(artifacts.find(artifact.id)).thenReturn(artifact)
        Mockito.`when`(artifacts.readChunk(artifact, 0, 1024)).thenReturn(bytes to 3L)
        val handler = WorkerArtifactGatewayHandler(artifacts, conversations, access)
        fun request(offset: Long = 0, limit: Int = 1024) = WorkerGatewayMessage.Request("download", WorkerGatewayOperation.ARTIFACT_CONTENT,
            com.gromozeka.remote.protocol.WorkerArtifactCodec.encodeRequest(com.gromozeka.remote.protocol.WorkerArtifactReadRequest(
                fixture.conversation.id, artifact.id, offset, limit)))
        val response = com.gromozeka.remote.protocol.WorkerArtifactCodec.decodeResponse(handler.execute(fixture.identity, request()))
        kotlin.test.assertContentEquals(bytes, response.content.bytes())
        assertEquals(3L, response.totalBytes)
        Mockito.verify(access).requireProjectAccess(fixture.workerId, fixture.conversation.projectId)
        assertFailsWith<IllegalArgumentException> { handler.execute(fixture.identity, request(-1)) }
        assertFailsWith<IllegalArgumentException> { handler.execute(fixture.identity, request(limit = 1024 * 1024 + 1)) }
        Mockito.`when`(artifacts.find(artifact.id)).thenReturn(artifact.copy(conversationId = Conversation.Id("other")))
        assertFailsWith<IllegalArgumentException> { handler.execute(fixture.identity, request()) }
        Mockito.`when`(artifacts.find(artifact.id)).thenReturn(artifact.copy(state = com.gromozeka.domain.model.Artifact.State.DRAFT, committedAt = null))
        assertFailsWith<IllegalArgumentException> { handler.execute(fixture.identity, request()) }
        Mockito.verify(artifacts).readChunk(artifact, 0, 1024)
    }

    @Test
    fun `database error codes distinguish transient failures from rejected data`() {
        assertEquals("DATABASE_UNAVAILABLE", workerRequestFailureCode(java.sql.SQLException("connection lost", "08006")))
        assertEquals("DATABASE_UNAVAILABLE", workerRequestFailureCode(RuntimeException(java.sql.SQLException("serialization", "40001"))))
        assertEquals("DATABASE_REQUEST_REJECTED", workerRequestFailureCode(java.sql.SQLException("invalid Unicode", "22P05")))
        assertEquals("REQUEST_REJECTED", workerRequestFailureCode(IllegalArgumentException("invalid scope")))
    }
    @Test
    fun `worker stores command state only in its granted execution scope`() = runBlocking {
        val fixture = fixture()
        val task = fixture.commandTask(fixture.workerId)

        val response = fixture.execute(
            WorkerCommandRuntimeRequest.UpsertCommandTask(task)
        )

        assertEquals(
            WorkerCommandRuntimeResponse.CommandTaskUpserted(task, emptyList()),
            response,
        )
        assertEquals(
            task,
            fixture.coordinator.findCommandTask(fixture.conversation.id, task.id),
        )
    }

    @Test
    fun `worker cannot store command state for another worker`() = runBlocking {
        val fixture = fixture()

        val error = assertFailsWith<IllegalArgumentException> {
            fixture.execute(
                WorkerCommandRuntimeRequest.UpsertCommandTask(
                    fixture.commandTask(ConversationRuntimeWorkerId("another-worker"))
                )
            )
        }

        assertEquals("Worker cannot write a command task for another Worker", error.message)
    }

    @Test
    fun `worker cannot read another worker command state`() = runBlocking {
        val fixture = fixture()
        val foreign = fixture.commandTask(ConversationRuntimeWorkerId("another-worker"))
        fixture.coordinator.upsertCommandTask(foreign)

        val response = fixture.execute(
            WorkerCommandRuntimeRequest.FindCommandTask(
                conversationId = fixture.conversation.id,
                taskId = foreign.id,
            )
        )

        assertEquals(
            WorkerCommandRuntimeResponse.CommandTaskResult(null),
            response,
        )
        assertNull((response as WorkerCommandRuntimeResponse.CommandTaskResult).task)
    }

    @Test
    fun `worker completes cancelled command when its clock is behind server`() = runBlocking {
        val fixture = fixture()
        val task = fixture.commandTask(fixture.workerId)
        val cancellationRequestedAt = Instant.parse("2026-07-30T00:00:10Z")
        fixture.coordinator.upsertCommandTask(task)
        fixture.coordinator.requestCommandTaskCancellation(
            fixture.conversation.id,
            task.id,
            cancellationRequestedAt,
        )

        fixture.execute(
            WorkerCommandRuntimeRequest.UpsertCommandTask(
                task.copy(
                    outputBytes = 12,
                    statusMessage = "Command is running",
                )
            )
        )
        val working = requireNotNull(
            fixture.coordinator.findCommandTask(fixture.conversation.id, task.id)
        )
        assertEquals(CommandTask.Status.WORKING, working.status)
        assertEquals("Cancellation requested", working.statusMessage)
        assertEquals(cancellationRequestedAt, working.cancellationRequestedAt)
        assertEquals(cancellationRequestedAt, working.updatedAt)
        val cancelled = task.copy(
            status = CommandTask.Status.CANCELLED,
            statusMessage = "Command was cancelled",
            completedAt = fixture.now,
        )

        val response = fixture.execute(WorkerCommandRuntimeRequest.UpsertCommandTask(cancelled))
        val stored = requireNotNull(
            fixture.coordinator.findCommandTask(fixture.conversation.id, task.id)
        )

        assertEquals(
            WorkerCommandRuntimeResponse.CommandTaskUpserted(stored, emptyList()),
            response,
        )
        assertEquals(CommandTask.Status.CANCELLED, stored.status)
        assertEquals("Command was cancelled", stored.statusMessage)
        assertEquals(cancellationRequestedAt, stored.cancellationRequestedAt)
        assertEquals(cancellationRequestedAt, stored.updatedAt)
    }

    @Test
    fun `worker completes cancelled monitor when its clock is behind server`() = runBlocking {
        val fixture = fixture()
        val source = fixture.commandTask(fixture.workerId)
        val monitor = fixture.commandMonitor(source)
        val cancellationRequestedAt = Instant.parse("2026-07-30T00:00:10Z")
        fixture.coordinator.upsertCommandTask(source)
        fixture.coordinator.synchronizeCommandMonitor(monitor)
        fixture.coordinator.requestCommandMonitorCancellation(
            fixture.conversation.id,
            monitor.id,
            cancellationRequestedAt,
        )
        val cancelled = monitor.copy(
            status = CommandMonitor.Status.CANCELLED,
            statusMessage = "Command monitor was cancelled",
            completedAt = fixture.now,
        )

        val response = fixture.execute(
            WorkerCommandRuntimeRequest.SynchronizeCommandMonitor(cancelled, emptyList())
        )
        val stored = requireNotNull(
            fixture.coordinator.findCommandMonitor(fixture.conversation.id, monitor.id)
        )

        assertEquals(
            WorkerCommandRuntimeResponse.CommandMonitorSynchronized(stored, emptyList()),
            response,
        )
        assertEquals(CommandMonitor.Status.CANCELLED, stored.status)
        assertEquals("Command monitor was cancelled", stored.statusMessage)
        assertEquals(cancellationRequestedAt, stored.cancellationRequestedAt)
        assertEquals(cancellationRequestedAt, stored.updatedAt)
    }

    private suspend fun fixture(): Fixture {
        val workerId = ConversationRuntimeWorkerId("worker-1")
        val now = Instant.parse("2026-07-30T00:00:00Z")
        val project = Project(
            id = Project.Id("project-1"),
            name = "Project",
            createdAt = now,
            lastUsedAt = now,
        )
        val workspace = Workspace(
            id = Workspace.Id("workspace-1"),
            projectId = project.id,
            name = "Workspace",
            kind = Workspace.Kind.FILESYSTEM,
            createdAt = now,
            updatedAt = now,
        )
        val mount = WorkspaceMount(
            id = WorkspaceMount.Id("mount-1"),
            workspaceId = workspace.id,
            workerId = workerId.value,
            rootPath = "/workspace",
            createdAt = now,
            updatedAt = now,
        )
        val conversation = Conversation(
            id = Conversation.Id("conversation-1"),
            projectId = project.id,
            participants = setOf(
                Conversation.Participant.User(User.Id("user-1")),
                Conversation.Participant.Agent(AgentDefinition.Id("agent-1")),
            ),
            currentThread = Conversation.Thread.Id("thread-1"),
            createdAt = now,
            updatedAt = now,
        )
        val conversationRepository = mock<ConversationRepository>()
        val workspaceService = mock<WorkspaceDomainService>()
        val workerAccessService = mock<WorkerAccessService>()
        val worker = WorkerResource(
            id = workerId,
            displayName = "Worker",
            ownerUserId = User.Id("user-1"),
            runtimeWideAccess = false,
            status = WorkerResource.Status.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(conversationRepository.findById(conversation.id)).thenReturn(conversation)
        Mockito.`when`(workspaceService.resolveExecution(mount.id))
            .thenReturn(WorkspaceExecutionContext(project, workspace, mount))
        Mockito.`when`(workerAccessService.requireProjectAccess(workerId, project.id)).thenReturn(worker)
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val state = ServerCommandRuntimeStateService(
            runtimeCoordinator = coordinator,
            runtimeStateSyncService = testConversationRuntimeStateSyncService(coordinator),
            commandTaskLifecycleEventPublisher = CommandTaskLifecycleEventPublisher { },
            commandMonitorLifecycleEventPublisher = CommandMonitorLifecycleEventPublisher { },
        )
        return Fixture(
            workerId = workerId,
            conversation = conversation,
            mount = mount,
            coordinator = coordinator,
            handler = WorkerCommandRuntimeGatewayHandler(
                commandRuntimeStateService = state,
                conversationRepository = conversationRepository,
                workspaceDomainService = workspaceService,
                workerAccessService = workerAccessService,
            ),
            now = now,
        )
    }

    private data class Fixture(
        val workerId: ConversationRuntimeWorkerId,
        val conversation: Conversation,
        val mount: WorkspaceMount,
        val coordinator: InMemoryConversationRuntimeCoordinator,
        val handler: WorkerCommandRuntimeGatewayHandler,
        val now: Instant,
    ) {
        val identity = ConversationRuntimeWorkerIdentity(
            workerId = workerId,
            sessionId = ConversationRuntimeWorkerSessionId("session-1"),
        )

        fun commandTask(owner: ConversationRuntimeWorkerId): CommandTask =
            CommandTask(
                id = CommandTask.Id("task-${owner.value}"),
                conversationId = conversation.id,
                workerId = owner,
                workspaceMountId = mount.id,
                agentDefinitionId = AgentDefinition.Id("agent-1"),
                command = "echo test",
                workingDirectory = mount.rootPath,
                status = CommandTask.Status.WORKING,
                processId = 42,
                processStartedAt = now,
                outputFile = "/tmp/output.log",
                outputBytes = 0,
                createdAt = now,
                updatedAt = now,
            )

        fun commandMonitor(source: CommandTask): CommandMonitor =
            CommandMonitor(
                id = CommandMonitor.Id("monitor-1"),
                conversationId = conversation.id,
                commandTaskId = source.id,
                workerId = source.workerId,
                workspaceMountId = source.workspaceMountId,
                agentDefinitionId = AgentDefinition.Id("agent-1"),
                filterCommand = "grep ready",
                mode = CommandMonitor.Mode.CONTINUOUS,
                startFrom = CommandMonitor.StartFrom.NOW,
                status = CommandMonitor.Status.WORKING,
                sourceOutputCursor = 0,
                processId = 43,
                processStartedAt = now,
                outputFile = "/tmp/monitor.log",
                errorFile = "/tmp/monitor.err",
                outputBytes = 0,
                eventOutputCursor = 0,
                createdAt = now,
                updatedAt = now,
            )

        suspend fun execute(request: WorkerCommandRuntimeRequest): WorkerCommandRuntimeResponse {
            val payload = handler.execute(
                identity = identity,
                request = WorkerGatewayMessage.Request(
                    id = "request-1",
                    operation = WorkerGatewayOperation.COMMAND_RUNTIME_STATE,
                    payload = WorkerCommandRuntimeGatewayCodec.encodeRequest(request),
                ),
            )
            return WorkerCommandRuntimeGatewayCodec.decodeResponse(payload)
        }
    }

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
