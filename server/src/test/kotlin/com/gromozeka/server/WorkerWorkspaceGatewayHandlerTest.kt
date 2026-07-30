package com.gromozeka.server

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerWorkspaceGatewayCodec
import com.gromozeka.remote.protocol.WorkerWorkspaceRequest
import com.gromozeka.remote.protocol.WorkerWorkspaceResponse
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerWorkspaceGatewayHandlerTest {
    @Test
    fun `create uses authenticated worker identity`() = runBlocking {
        val fixture = fixture()
        Mockito.`when`(
            fixture.workspaceService.createAndMountFilesystemWorkspace(
                projectId = fixture.project.id,
                name = "Checkout",
                workerId = fixture.workerId.value,
                rootPath = "/work/checkout",
            )
        ).thenReturn(fixture.execution)

        val response = fixture.execute(
            WorkerWorkspaceRequest.CreateAndMountFilesystem(
                projectId = fixture.project.id,
                name = "Checkout",
                rootPath = "/work/checkout",
            )
        )

        assertEquals(
            WorkerWorkspaceResponse.ExecutionContextResult(fixture.execution),
            response,
        )
    }

    @Test
    fun `attach rejects workspace from another project`() = runBlocking {
        val fixture = fixture()
        val otherProjectWorkspace = fixture.workspace.copy(
            projectId = Project.Id("project-2"),
        )
        Mockito.`when`(fixture.workspaceService.findById(otherProjectWorkspace.id))
            .thenReturn(otherProjectWorkspace)

        val error = assertFailsWith<IllegalArgumentException> {
            fixture.execute(
                WorkerWorkspaceRequest.AttachFilesystem(
                    projectId = fixture.project.id,
                    workspaceId = otherProjectWorkspace.id,
                    rootPath = "/work/checkout",
                )
            )
        }

        assertEquals(
            "Workspace ${otherProjectWorkspace.id.value} does not belong to project ${fixture.project.id.value}",
            error.message,
        )
    }

    @Test
    fun `mount listing returns only authenticated worker mounts`() = runBlocking {
        val fixture = fixture()
        val foreignMount = fixture.mount.copy(
            id = WorkspaceMount.Id("mount-2"),
            workerId = "worker-2",
        )
        Mockito.`when`(fixture.workspaceService.findByProject(fixture.project.id))
            .thenReturn(listOf(fixture.workspace))
        Mockito.`when`(fixture.workspaceService.findMounts(fixture.workspace.id))
            .thenReturn(listOf(fixture.mount, foreignMount))

        val response = fixture.execute(
            WorkerWorkspaceRequest.FindProjectMounts(fixture.project.id)
        )

        assertEquals(
            WorkerWorkspaceResponse.MountsResult(listOf(fixture.mount)),
            response,
        )
    }

    private suspend fun fixture(): Fixture {
        val now = Instant.parse("2026-07-30T00:00:00Z")
        val workerId = ConversationRuntimeWorkerId("worker-1")
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
            rootPath = "/work/checkout",
            createdAt = now,
            updatedAt = now,
        )
        val worker = WorkerResource(
            id = workerId,
            displayName = "Worker",
            ownerUserId = User.Id("user-1"),
            organizationAccess = false,
            status = WorkerResource.Status.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
        val workspaceService = mock<WorkspaceDomainService>()
        val workerAccessService = mock<WorkerAccessService>()
        Mockito.`when`(workerAccessService.requireProjectAccess(workerId, project.id))
            .thenReturn(worker)
        return Fixture(
            workerId = workerId,
            project = project,
            workspace = workspace,
            mount = mount,
            execution = WorkspaceExecutionContext(project, workspace, mount),
            workspaceService = workspaceService,
            handler = WorkerWorkspaceGatewayHandler(workspaceService, workerAccessService),
        )
    }

    private data class Fixture(
        val workerId: ConversationRuntimeWorkerId,
        val project: Project,
        val workspace: Workspace,
        val mount: WorkspaceMount,
        val execution: WorkspaceExecutionContext,
        val workspaceService: WorkspaceDomainService,
        val handler: WorkerWorkspaceGatewayHandler,
    ) {
        private val identity = ConversationRuntimeWorkerIdentity(
            workerId = workerId,
            sessionId = ConversationRuntimeWorkerSessionId("session-1"),
        )

        suspend fun execute(request: WorkerWorkspaceRequest): WorkerWorkspaceResponse {
            val payload = handler.execute(
                identity = identity,
                request = WorkerGatewayMessage.Request(
                    id = "request-1",
                    operation = WorkerGatewayOperation.WORKSPACE_STATE,
                    payload = WorkerWorkspaceGatewayCodec.encodeRequest(request),
                ),
            )
            return WorkerWorkspaceGatewayCodec.decodeResponse(payload)
        }
    }

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
