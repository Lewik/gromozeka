package com.gromozeka.server

import com.gromozeka.application.service.WorkspaceTextFileApplicationService
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.WorkspacePathReference
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkerWorkspaceTextFileClient
import com.gromozeka.domain.service.WorkerWorkspaceTextFileReadRequest
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.service.WorkspacePathAccessContext
import com.gromozeka.domain.service.WorkspaceTextFile
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceTextFileApplicationServiceTest {
    @Test
    fun `resolves mount authorizes actor and reads on its exact online Worker`() = runBlocking {
        val now = Instant.parse("2026-08-03T00:00:00Z")
        val project = Project(Project.Id("project-1"), "Project", createdAt = now, lastUsedAt = now)
        val workspace = Workspace(
            Workspace.Id("workspace-1"),
            project.id,
            "Workspace",
            Workspace.Kind.FILESYSTEM,
            now,
            now,
        )
        val mount = WorkspaceMount(
            WorkspaceMount.Id("mount-1"),
            workspace.id,
            "worker-1",
            "/workspace",
            now,
            now,
        )
        val execution = WorkspaceExecutionContext(project, workspace, mount)
        val reference = WorkspacePathReference(mount.id, "docs/memory.md")
        val actor = User(
            id = User.Id("user-1"),
            username = "user",
            displayName = "User",
            status = User.Status.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
        val identity = ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId(mount.workerId),
            sessionId = ConversationRuntimeWorkerSessionId("session-1"),
        )
        val workspaceService = mock<WorkspaceDomainService>()
        val workerAccessService = mock<WorkerAccessService>()
        val userDirectoryService = mock<UserDirectoryService>()
        val targetResolver = mock<ConversationRuntimeWorkerTargetResolver>()
        val workerClient = RecordingWorkerWorkspaceTextFileClient()
        Mockito.`when`(workspaceService.resolveExecution(mount.id)).thenReturn(execution)
        Mockito.`when`(userDirectoryService.findActiveById(actor.id)).thenReturn(actor)
        Mockito.`when`(
            workerAccessService.requirePermission(
                actor,
                identity.workerId,
                WorkerPermission.USE,
                project.id,
            )
        ).thenReturn(mock<WorkerResource>())
        Mockito.`when`(
            targetResolver.requireOnline(identity.workerId, ConversationRuntimeCapability.LOCAL_AGENT_TOOL)
        ).thenReturn(identity)

        val result = WorkspaceTextFileApplicationService(
            workspaceService,
            workerAccessService,
            userDirectoryService,
            targetResolver,
            workerClient,
        ).read(
            reference = reference,
            access = WorkspacePathAccessContext(
                actorUserId = actor.id,
                expectedProjectId = project.id,
            ),
            maxBytes = 10_000,
        )

        assertEquals("content", result.content)
        assertEquals(identity, workerClient.request?.target)
        assertEquals(reference, workerClient.request?.reference)
        assertEquals("/workspace", workerClient.request?.workspaceRootPath)
    }

    private class RecordingWorkerWorkspaceTextFileClient : WorkerWorkspaceTextFileClient {
        var request: WorkerWorkspaceTextFileReadRequest? = null

        override suspend fun read(request: WorkerWorkspaceTextFileReadRequest): WorkspaceTextFile {
            this.request = request
            return WorkspaceTextFile(
                reference = request.reference,
                resolvedPath = "/workspace/docs/memory.md",
                fileName = "memory.md",
                content = "content",
                sizeBytes = 7,
            )
        }
    }

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
