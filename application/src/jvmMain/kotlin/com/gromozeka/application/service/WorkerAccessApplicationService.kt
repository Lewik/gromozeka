package com.gromozeka.application.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.WorkerProjectGrant
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.WorkerUserGrant
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.repository.ProjectMembershipRepository
import com.gromozeka.domain.repository.WorkerAccessRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.WorkerAccessDeniedException
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkerConnectionRevocationService
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkerAccessApplicationService(
    private val repository: WorkerAccessRepository,
    private val identityRepository: IdentityRepository,
    private val membershipRepository: ProjectMembershipRepository,
    private val projectAccessService: ProjectAccessService,
    private val workerConnectionRevocationService: WorkerConnectionRevocationService,
) : WorkerAccessService {
    override suspend fun findAccessible(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id?,
    ): WorkerResource? =
        repository.findWorker(workerId)
            ?.takeIf { it.status == WorkerResource.Status.ACTIVE }
            ?.takeIf { canUse(actor, it, projectId) }

    override suspend fun listAccessible(actor: User): List<WorkerResource> {
        val directlyGrantedIds = repository.findWorkerIdsGrantedToUser(actor.id)
        val writableProjectIds = membershipRepository.findByUser(actor.id)
            .filter { it.role.allows(ProjectPermission.WRITE) }
            .mapTo(mutableSetOf()) { it.projectId }
        val projectGrantedIds = repository.findWorkerIdsGrantedToProjects(writableProjectIds)
        return repository.listWorkers()
            .filter { it.status == WorkerResource.Status.ACTIVE }
            .filter {
                it.ownerUserId == actor.id ||
                    it.organizationAccess ||
                    it.id in directlyGrantedIds ||
                    it.id in projectGrantedIds
            }
    }

    override suspend fun listAvailableToProject(projectId: Project.Id): List<WorkerResource> {
        val projectGrantedIds = repository.findWorkerIdsGrantedToProjects(setOf(projectId))
        return repository.listWorkers()
            .filter { it.status == WorkerResource.Status.ACTIVE }
            .filter { it.organizationAccess || it.id in projectGrantedIds }
    }

    override suspend fun requirePermission(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        permission: WorkerPermission,
        projectId: Project.Id?,
    ): WorkerResource {
        val worker = repository.findWorker(workerId)
            ?.takeIf { it.status == WorkerResource.Status.ACTIVE }
            ?: throw WorkerAccessDeniedException()
        val allowed = when (permission) {
            WorkerPermission.USE -> canUse(actor, worker, projectId)
            WorkerPermission.MANAGE -> canManage(actor, worker)
        }
        return worker.takeIf { allowed } ?: throw WorkerAccessDeniedException()
    }

    override suspend fun requireProjectAccess(
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id,
    ): WorkerResource {
        val worker = repository.findWorker(workerId)
            ?.takeIf { it.status == WorkerResource.Status.ACTIVE }
            ?: throw WorkerAccessDeniedException()
        if (!worker.organizationAccess && repository.findProjectGrant(workerId, projectId) == null) {
            throw WorkerAccessDeniedException()
        }
        return worker
    }

    override suspend fun listUserGrants(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
    ): List<WorkerUserGrant> {
        requirePermission(actor, workerId, WorkerPermission.MANAGE)
        return repository.listUserGrants(workerId)
    }

    @Transactional
    override suspend fun grantUser(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        userId: User.Id,
    ): WorkerUserGrant {
        val worker = requirePermission(actor, workerId, WorkerPermission.MANAGE)
        require(userId != worker.ownerUserId) { "Worker owner already has access" }
        requireActiveUser(userId)
        return repository.findUserGrant(workerId, userId)
            ?: repository.saveUserGrant(
                WorkerUserGrant(
                    workerId = workerId,
                    userId = userId,
                    createdAt = Clock.System.now(),
                    createdByUserId = actor.id,
                )
            )
    }

    @Transactional
    override suspend fun revokeUser(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        userId: User.Id,
    ): Boolean {
        requirePermission(actor, workerId, WorkerPermission.MANAGE)
        return repository.deleteUserGrant(workerId, userId)
    }

    override suspend fun listProjectGrants(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
    ): List<WorkerProjectGrant> {
        requirePermission(actor, workerId, WorkerPermission.MANAGE)
        return repository.listProjectGrants(workerId)
    }

    @Transactional
    override suspend fun grantProject(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id,
    ): WorkerProjectGrant {
        requirePermission(actor, workerId, WorkerPermission.MANAGE)
        projectAccessService.requirePermission(actor.id, projectId, ProjectPermission.ADMIN)
        return repository.findProjectGrant(workerId, projectId)
            ?: repository.saveProjectGrant(
                WorkerProjectGrant(
                    workerId = workerId,
                    projectId = projectId,
                    createdAt = Clock.System.now(),
                    createdByUserId = actor.id,
                )
            )
    }

    @Transactional
    override suspend fun revokeProject(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        projectId: Project.Id,
    ): Boolean {
        requirePermission(actor, workerId, WorkerPermission.MANAGE)
        return repository.deleteProjectGrant(workerId, projectId)
    }

    @Transactional
    override suspend fun setOrganizationAccess(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
        enabled: Boolean,
    ): WorkerResource {
        val worker = requirePermission(actor, workerId, WorkerPermission.MANAGE)
        if (worker.organizationAccess == enabled) {
            return worker
        }
        return repository.saveWorker(
            worker.copy(
                organizationAccess = enabled,
                updatedAt = Clock.System.now(),
            )
        )
    }

    @Transactional
    override suspend fun revokeWorker(
        actor: User,
        workerId: ConversationRuntimeWorkerId,
    ): WorkerResource {
        val worker = requirePermission(actor, workerId, WorkerPermission.MANAGE)
        val revoked = repository.saveWorker(
            worker.copy(
                status = WorkerResource.Status.REVOKED,
                updatedAt = Clock.System.now(),
            )
        )
        workerConnectionRevocationService.disconnectRevokedWorker(workerId)
        return revoked
    }

    private suspend fun canUse(
        actor: User,
        worker: WorkerResource,
        projectId: Project.Id?,
    ): Boolean {
        if (projectId != null) {
            return projectAccessService.can(actor.id, projectId, ProjectPermission.WRITE) &&
                (
                    worker.organizationAccess ||
                        repository.findProjectGrant(worker.id, projectId) != null
                    )
        }
        if (worker.ownerUserId == actor.id || worker.organizationAccess) {
            return true
        }
        if (repository.findUserGrant(worker.id, actor.id) != null) {
            return true
        }
        return false
    }

    private fun canManage(actor: User, worker: WorkerResource): Boolean =
        actor.id == worker.ownerUserId || actor.role == User.Role.OWNER

    private suspend fun requireActiveUser(userId: User.Id): User =
        identityRepository.findUserById(userId)
            ?.takeIf { it.status == User.Status.ACTIVE }
            ?: throw IllegalArgumentException("Active user not found: ${userId.value}")
}
