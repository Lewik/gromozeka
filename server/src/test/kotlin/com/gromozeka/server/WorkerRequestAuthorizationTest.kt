package com.gromozeka.server

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.repository.ProjectRepository
import com.gromozeka.domain.repository.WorkerAccessRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.StoredWorkerRequest
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.service.WorkerAccessDeniedException
import com.gromozeka.domain.service.WorkerAccessService
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class WorkerRequestAuthorizationTest {
    private val workers = mock<WorkerAccessRepository>()
    private val access = mock<WorkerAccessService>()
    private val users = mock<UserDirectoryService>()
    private val projectAccess = mock<ProjectAccessService>()
    private val projects = mock<ProjectRepository>()
    private val authorization = DefaultWorkerRequestAuthorization(workers, access, users, projectAccess, projects)
    private val now = Clock.System.now()
    private val record = StoredWorkerRequest("request", ConversationRuntimeWorkerId("worker"), byteArrayOf(), now, now + 30.seconds)

    private suspend fun activeWorker() {
        val worker = mock<WorkerResource>()
        Mockito.`when`(worker.status).thenReturn(WorkerResource.Status.ACTIVE)
        Mockito.`when`(workers.findWorker(record.workerId)).thenReturn(worker)
    }

    @Test
    fun `deleted project denies queued system requests too`() = runBlocking {
        activeWorker()
        assertFailsWith<ProjectAccessDeniedException> {
            authorization.requireAccess(record.copy(projectId = Project.Id("deleted-project")))
        }
        Mockito.verifyNoInteractions(access)
    }

    @Test
    fun `missing worker and deactivated author are denied`() = runBlocking {
        assertFailsWith<WorkerAccessDeniedException> { authorization.requireAccess(record) }
        activeWorker()
        assertFailsWith<WorkerAccessDeniedException> { authorization.requireAccess(record.copy(actorUserId = User.Id("inactive"))) }
        Mockito.verifyNoInteractions(access)
    }

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
