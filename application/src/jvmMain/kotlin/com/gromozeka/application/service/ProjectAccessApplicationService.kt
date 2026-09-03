package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectMembership
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.SecurityAuditRecord
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.repository.ProjectMembershipRepository
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.SecurityAuditRecorder
import com.gromozeka.domain.service.DeclarativeStateChangePublisher
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.domain.service.NoOpDeclarativeStateChangePublisher
import kotlin.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class ProjectAccessApplicationService(
    private val projectService: ProjectDomainService,
    private val membershipRepository: ProjectMembershipRepository,
    private val conversationRepository: ConversationRepository,
    private val identityRepository: IdentityRepository,
    private val securityAuditRecorder: SecurityAuditRecorder,
    private val stateChanges: DeclarativeStateChangePublisher = NoOpDeclarativeStateChangePublisher,
) : ProjectAccessService {
    @Transactional
    override suspend fun create(
        actorUserId: User.Id,
        name: String,
        description: String?,
        id: Project.Id?,
    ): Project {
        requireActiveUser(actorUserId)
        val project = projectService.create(name, description, id)
        membershipRepository.save(
            ProjectMembership(
                projectId = project.id,
                userId = actorUserId,
                role = ProjectMembership.Role.OWNER,
                createdAt = Clock.System.now(),
                createdByUserId = actorUserId,
            )
        )
        securityAuditRecorder.record(
            SecurityAuditRecord(
                actorUserId = actorUserId,
                action = SecurityAuditEvent.Action.PROJECT_CREATED,
                targetType = SecurityAuditEvent.TargetType.PROJECT,
                targetId = project.id.value,
                projectId = project.id,
            )
        )
        return project
    }

    override suspend fun findById(
        actorUserId: User.Id,
        id: Project.Id,
    ): Project? {
        if (!can(actorUserId, id, ProjectPermission.READ)) return null
        return projectService.findById(id)
    }

    override suspend fun findRecent(
        actorUserId: User.Id,
        limit: Int,
    ): List<Project> {
        require(limit >= 0) { "Project limit must not be negative" }
        val readableIds = readableProjectIds(actorUserId)
        return projectService.findAll()
            .asSequence()
            .filter { it.id in readableIds }
            .take(limit)
            .toList()
    }

    override suspend fun findAll(actorUserId: User.Id): List<Project> {
        val readableIds = readableProjectIds(actorUserId)
        return projectService.findAll().filter { it.id in readableIds }
    }

    @Transactional
    override suspend fun update(
        actorUserId: User.Id,
        id: Project.Id,
        name: String,
        description: String?,
    ): Project {
        requirePermission(actorUserId, id, ProjectPermission.WRITE)
        return projectService.update(id, name, description)
    }

    @Transactional
    override suspend fun delete(
        actorUserId: User.Id,
        id: Project.Id,
    ) {
        requirePermission(actorUserId, id, ProjectPermission.ADMIN)
        projectService.delete(id)
        securityAuditRecorder.record(
            SecurityAuditRecord(
                actorUserId = actorUserId,
                action = SecurityAuditEvent.Action.PROJECT_DELETED,
                targetType = SecurityAuditEvent.TargetType.PROJECT,
                targetId = id.value,
                projectId = id,
            )
        )
    }

    @Transactional
    override suspend fun updateLastUsed(
        actorUserId: User.Id,
        id: Project.Id,
    ): Project? {
        requirePermission(actorUserId, id, ProjectPermission.READ)
        return projectService.updateLastUsed(id)
    }

    override suspend fun requirePermission(
        actorUserId: User.Id,
        projectId: Project.Id,
        permission: ProjectPermission,
    ): ProjectMembership {
        val membership = membershipRepository.find(projectId, actorUserId)
            ?: throw ProjectAccessDeniedException()
        if (!membership.role.allows(permission)) {
            throw ProjectAccessDeniedException()
        }
        return membership
    }

    override suspend fun can(
        actorUserId: User.Id,
        projectId: Project.Id,
        permission: ProjectPermission,
    ): Boolean =
        membershipRepository.find(projectId, actorUserId)
            ?.role
            ?.allows(permission)
            ?: false

    override suspend fun listMemberships(
        actorUserId: User.Id,
        projectId: Project.Id,
    ): List<ProjectMembership> {
        requirePermission(actorUserId, projectId, ProjectPermission.READ)
        return membershipRepository.findByProject(projectId)
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    override suspend fun setMembership(
        actorUserId: User.Id,
        projectId: Project.Id,
        userId: User.Id,
        role: ProjectMembership.Role,
    ): ProjectMembership {
        requirePermission(actorUserId, projectId, ProjectPermission.ADMIN)
        requireActiveUser(userId)
        val existing = membershipRepository.find(projectId, userId)
        if (
            existing?.role == ProjectMembership.Role.OWNER &&
            role != ProjectMembership.Role.OWNER &&
            membershipRepository.countOwners(projectId) == 1L
        ) {
            throw IllegalStateException("A project must have at least one owner")
        }
        if (existing?.role == role) {
            return existing
        }
        val membership = membershipRepository.save(
            existing?.copy(role = role)
                ?: ProjectMembership(
                    projectId = projectId,
                    userId = userId,
                    role = role,
                    createdAt = Clock.System.now(),
                    createdByUserId = actorUserId,
                )
        )
        securityAuditRecorder.record(
            SecurityAuditRecord(
                actorUserId = actorUserId,
                action = SecurityAuditEvent.Action.PROJECT_MEMBERSHIP_SET,
                targetType = SecurityAuditEvent.TargetType.USER,
                targetId = userId.value,
                projectId = projectId,
                attributes = buildMap {
                    existing?.role?.let { put("previousRole", it.name) }
                    put("role", role.name)
                },
            )
        )
        stateChanges.publish(
            DeclarativeStateKey.projects,
            DeclarativeStateKey.projectConversations(projectId),
            DeclarativeStateKey.projectWorkspaces(projectId),
            DeclarativeStateKey.projectAgentSkills(projectId),
            DeclarativeStateKey.projectMemberships(projectId),
            DeclarativeStateKey.agents,
            DeclarativeStateKey.prompts,
            DeclarativeStateKey.workers,
        )
        return membership
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    override suspend fun removeMembership(
        actorUserId: User.Id,
        projectId: Project.Id,
        userId: User.Id,
    ): Boolean {
        requirePermission(actorUserId, projectId, ProjectPermission.ADMIN)
        val existing = membershipRepository.find(projectId, userId) ?: return false
        if (
            existing.role == ProjectMembership.Role.OWNER &&
            membershipRepository.countOwners(projectId) == 1L
        ) {
            throw IllegalStateException("A project must have at least one owner")
        }
        val participant = Conversation.Participant.User(userId)
        val affectedConversations = conversationRepository.findByProject(projectId)
            .filter { participant in it.participants }
        check(affectedConversations.none { conversation ->
            conversation.participants.count { it is Conversation.Participant.User } == 1
        }) {
            "Cannot remove the last user participant from a conversation"
        }
        affectedConversations.forEach { conversation ->
            conversationRepository.updateParticipants(
                conversation.id,
                conversation.participants - participant,
            )
        }
        val removed = membershipRepository.delete(projectId, userId)
        if (removed) {
            securityAuditRecorder.record(
                SecurityAuditRecord(
                    actorUserId = actorUserId,
                    action = SecurityAuditEvent.Action.PROJECT_MEMBERSHIP_REMOVED,
                    targetType = SecurityAuditEvent.TargetType.USER,
                    targetId = userId.value,
                    projectId = projectId,
                    attributes = mapOf("previousRole" to existing.role.name),
                )
            )
            stateChanges.publish(
                DeclarativeStateKey.projects,
                DeclarativeStateKey.projectConversations(projectId),
                DeclarativeStateKey.projectWorkspaces(projectId),
                DeclarativeStateKey.projectAgentSkills(projectId),
                DeclarativeStateKey.projectMemberships(projectId),
                DeclarativeStateKey.agents,
                DeclarativeStateKey.prompts,
                DeclarativeStateKey.workers,
            )
        }
        return removed
    }

    private suspend fun readableProjectIds(actorUserId: User.Id): Set<Project.Id> =
        membershipRepository.findByUser(actorUserId)
            .filter { it.role.allows(ProjectPermission.READ) }
            .mapTo(mutableSetOf(), ProjectMembership::projectId)

    private suspend fun requireActiveUser(userId: User.Id): User =
        identityRepository.findUserById(userId)
            ?.takeIf { it.status == User.Status.ACTIVE }
            ?: throw IllegalArgumentException("Active user not found: ${userId.value}")
}
