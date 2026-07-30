package com.gromozeka.application.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectMembership
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.WorkerProjectGrant
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.WorkerUserGrant
import com.gromozeka.domain.repository.WorkerAccessRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.WorkerAccessDeniedException
import com.gromozeka.domain.service.WorkerConnectionRevocationService
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkerAccessApplicationServiceTest {
    private val owner = workerUser("owner")
    private val sharedUser = workerUser("shared")
    private val projectUser = workerUser("project-user")
    private val outsider = workerUser("outsider")
    private val serverOwner = workerUser("server-owner", User.Role.OWNER)
    private val project = workerProject("project-1")
    private val identityRepository = FakeIdentityRepository().apply {
        users += listOf(owner, sharedUser, projectUser, outsider, serverOwner)
    }
    private val membershipRepository = FakeProjectMembershipRepository().apply {
        runBlocking {
            save(projectMembership(owner, ProjectMembership.Role.OWNER))
            save(projectMembership(sharedUser, ProjectMembership.Role.EDITOR))
            save(projectMembership(projectUser, ProjectMembership.Role.EDITOR))
        }
    }
    private val repository = FakeWorkerAccessRepository()
    private val connectionRevocationService = FakeWorkerConnectionRevocationService()
    private val projectAccessService = ProjectAccessApplicationService(
        projectService = WorkerTestProjectService(project),
        membershipRepository = membershipRepository,
        identityRepository = identityRepository,
    )
    private val service = WorkerAccessApplicationService(
        repository = repository,
        identityRepository = identityRepository,
        membershipRepository = membershipRepository,
        projectAccessService = projectAccessService,
        workerConnectionRevocationService = connectionRevocationService,
    )
    private val worker = workerResource(owner)

    @Test
    fun `worker access composes owner user project and organization grants`() = runBlocking {
        repository.saveWorker(worker)

        assertNotNull(service.findAccessible(owner, worker.id))
        assertNull(service.findAccessible(sharedUser, worker.id))
        assertNull(service.findAccessible(projectUser, worker.id, project.id))

        service.grantUser(owner, worker.id, sharedUser.id)
        assertNull(service.findAccessible(sharedUser, worker.id, project.id))

        service.grantProject(owner, worker.id, project.id)

        assertNotNull(service.findAccessible(sharedUser, worker.id))
        assertNotNull(service.findAccessible(sharedUser, worker.id, project.id))
        assertNotNull(service.findAccessible(projectUser, worker.id, project.id))
        assertEquals(setOf(worker.id), service.listAccessible(projectUser).mapTo(mutableSetOf()) { it.id })
        assertNull(service.findAccessible(outsider, worker.id, project.id))

        service.setRuntimeWideAccess(owner, worker.id, true)

        assertNotNull(service.findAccessible(outsider, worker.id))
        Unit
    }

    @Test
    fun `only worker owner or server owner can manage access`() = runBlocking {
        repository.saveWorker(worker)
        service.grantUser(owner, worker.id, sharedUser.id)

        assertFailsWith<WorkerAccessDeniedException> {
            service.setRuntimeWideAccess(sharedUser, worker.id, true)
        }
        assertNotNull(
            service.requirePermission(serverOwner, worker.id, WorkerPermission.MANAGE)
        )

        service.revokeWorker(serverOwner, worker.id)

        assertEquals(listOf(worker.id), connectionRevocationService.revokedWorkerIds)
        assertFailsWith<WorkerAccessDeniedException> {
            service.requirePermission(owner, worker.id, WorkerPermission.USE)
        }
        Unit
    }

    private fun projectMembership(
        user: User,
        role: ProjectMembership.Role,
    ): ProjectMembership =
        ProjectMembership(
            projectId = project.id,
            userId = user.id,
            role = role,
            createdAt = Clock.System.now(),
            createdByUserId = owner.id,
        )
}

private class FakeWorkerConnectionRevocationService : WorkerConnectionRevocationService {
    val revokedWorkerIds = mutableListOf<ConversationRuntimeWorkerId>()

    override fun disconnectRevokedWorker(workerId: ConversationRuntimeWorkerId) {
        revokedWorkerIds += workerId
    }
}

private class FakeWorkerAccessRepository : WorkerAccessRepository {
    private val workers = linkedMapOf<ConversationRuntimeWorkerId, WorkerResource>()
    private val userGrants = linkedMapOf<Pair<ConversationRuntimeWorkerId, User.Id>, WorkerUserGrant>()
    private val projectGrants = linkedMapOf<Pair<ConversationRuntimeWorkerId, Project.Id>, WorkerProjectGrant>()

    override suspend fun findWorker(workerId: ConversationRuntimeWorkerId): WorkerResource? = workers[workerId]

    override suspend fun listWorkers(): List<WorkerResource> = workers.values.toList()

    override suspend fun saveWorker(worker: WorkerResource): WorkerResource =
        worker.also { workers[it.id] = it }

    override suspend fun findUserGrant(
        workerId: ConversationRuntimeWorkerId,
        userId: User.Id,
    ): WorkerUserGrant? = userGrants[workerId to userId]

    override suspend fun listUserGrants(workerId: ConversationRuntimeWorkerId): List<WorkerUserGrant> =
        userGrants.values.filter { it.workerId == workerId }

    override suspend fun findWorkerIdsGrantedToUser(userId: User.Id): Set<ConversationRuntimeWorkerId> =
        userGrants.values
            .filter { it.userId == userId }
            .mapTo(mutableSetOf()) { it.workerId }

    override suspend fun saveUserGrant(grant: WorkerUserGrant): WorkerUserGrant =
        grant.also { userGrants[it.workerId to it.userId] = it }

    override suspend fun deleteUserGrant(
        workerId: ConversationRuntimeWorkerId,
        userId: User.Id,
    ): Boolean = userGrants.remove(workerId to userId) != null

    override suspend fun findProjectGrant(
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id,
    ): WorkerProjectGrant? = projectGrants[workerId to projectId]

    override suspend fun listProjectGrants(workerId: ConversationRuntimeWorkerId): List<WorkerProjectGrant> =
        projectGrants.values.filter { it.workerId == workerId }

    override suspend fun findWorkerIdsGrantedToProjects(
        projectIds: Set<Project.Id>,
    ): Set<ConversationRuntimeWorkerId> =
        projectGrants.values
            .filter { it.projectId in projectIds }
            .mapTo(mutableSetOf()) { it.workerId }

    override suspend fun saveProjectGrant(grant: WorkerProjectGrant): WorkerProjectGrant =
        grant.also { projectGrants[it.workerId to it.projectId] = it }

    override suspend fun deleteProjectGrant(
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id,
    ): Boolean = projectGrants.remove(workerId to projectId) != null
}

private class WorkerTestProjectService(
    project: Project,
) : ProjectDomainService {
    private val projects = mutableMapOf(project.id to project)

    override suspend fun create(
        name: String,
        description: String?,
        id: Project.Id?,
    ): Project = error("Not used")

    override suspend fun findById(id: Project.Id): Project? = projects[id]

    override suspend fun findRecent(limit: Int): List<Project> = projects.values.take(limit)

    override suspend fun findAll(): List<Project> = projects.values.toList()

    override suspend fun update(
        id: Project.Id,
        name: String,
        description: String?,
    ): Project = error("Not used")

    override suspend fun delete(id: Project.Id) {
        projects.remove(id)
    }

    override suspend fun updateLastUsed(id: Project.Id): Project? = projects[id]
}

private fun workerUser(
    id: String,
    role: User.Role = User.Role.MEMBER,
): User {
    val now = Clock.System.now()
    return User(
        id = User.Id(id),
        username = id,
        displayName = id,
        status = User.Status.ACTIVE,
        role = role,
        createdAt = now,
        updatedAt = now,
    )
}

private fun workerProject(id: String): Project {
    val now = Clock.System.now()
    return Project(
        id = Project.Id(id),
        name = id,
        createdAt = now,
        lastUsedAt = now,
    )
}

private fun workerResource(owner: User): WorkerResource {
    val now = Clock.System.now()
    return WorkerResource(
        id = ConversationRuntimeWorkerId("worker-1"),
        displayName = "Worker 1",
        ownerUserId = owner.id,
        runtimeWideAccess = false,
        status = WorkerResource.Status.ACTIVE,
        createdAt = now,
        updatedAt = now,
    )
}
