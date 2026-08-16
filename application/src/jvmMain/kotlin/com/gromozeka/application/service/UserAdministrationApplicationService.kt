package com.gromozeka.application.service

import com.gromozeka.domain.model.LocalPasswordCredential
import com.gromozeka.domain.model.ProjectMembership
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.SecurityAuditRecord
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.repository.ProjectMembershipRepository
import com.gromozeka.domain.service.LastActiveRuntimeOwnerException
import com.gromozeka.domain.service.PasswordHasher
import com.gromozeka.domain.service.SecurityAuditRecorder
import com.gromozeka.domain.service.SoleProjectOwnerException
import com.gromozeka.domain.service.UserAdministrationDeniedException
import com.gromozeka.domain.service.UserAdministrationService
import com.gromozeka.domain.service.DeclarativeStateChangePublisher
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.domain.service.NoOpDeclarativeStateChangePublisher
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class UserAdministrationApplicationService(
    private val identityRepository: IdentityRepository,
    private val projectMembershipRepository: ProjectMembershipRepository,
    private val passwordHasher: PasswordHasher,
    private val securityAuditRecorder: SecurityAuditRecorder,
    private val stateChanges: DeclarativeStateChangePublisher = NoOpDeclarativeStateChangePublisher,
) : UserAdministrationService {
    override suspend fun list(actor: User): List<User> {
        requireOwner(actor)
        return identityRepository.listUsers()
    }

    @Transactional
    override suspend fun create(
        actor: User,
        username: String,
        displayName: String,
        password: CharArray,
        role: User.Role,
    ): User {
        requireOwner(actor)
        val normalizedUsername = LocalIdentityInputPolicy.normalizeUsername(username)
        require(identityRepository.findUserByUsername(normalizedUsername) == null) {
            "Username already exists: $normalizedUsername"
        }
        LocalIdentityInputPolicy.validatePassword(password)
        val now = Clock.System.now()
        val user = User(
            id = User.Id(uuid7()),
            username = normalizedUsername,
            displayName = LocalIdentityInputPolicy.normalizeDisplayName(displayName, normalizedUsername),
            status = User.Status.ACTIVE,
            role = role,
            createdAt = now,
            updatedAt = now,
        )
        val created = identityRepository.createUser(
            user,
            LocalPasswordCredential(
                userId = user.id,
                passwordHash = passwordHasher.hash(password),
                passwordChangedAt = now,
            ),
        )
        securityAuditRecorder.record(
            SecurityAuditRecord(
                actorUserId = actor.id,
                action = SecurityAuditEvent.Action.USER_CREATED,
                targetType = SecurityAuditEvent.TargetType.USER,
                targetId = created.id.value,
                attributes = mapOf(
                    "displayName" to created.displayName,
                    "username" to created.username,
                    "role" to created.role.name,
                ),
            )
        )
        stateChanges.publish(DeclarativeStateKey.users, DeclarativeStateKey.userDirectory)
        return created
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    override suspend fun update(
        actor: User,
        userId: User.Id,
        displayName: String,
        status: User.Status,
        role: User.Role,
    ): User {
        requireOwner(actor)
        val existing = identityRepository.findUserById(userId)
            ?: throw IllegalArgumentException("User not found: ${userId.value}")
        val normalizedDisplayName = LocalIdentityInputPolicy.normalizeDisplayName(
            displayName,
            existing.username,
        )
        if (
            existing.displayName == normalizedDisplayName &&
            existing.status == status &&
            existing.role == role
        ) {
            return existing
        }
        val removesActiveOwner = existing.status == User.Status.ACTIVE &&
            existing.role == User.Role.OWNER &&
            (status != User.Status.ACTIVE || role != User.Role.OWNER)
        if (removesActiveOwner && identityRepository.countActiveOwners() <= 1) {
            throw LastActiveRuntimeOwnerException()
        }
        if (existing.status == User.Status.ACTIVE && status == User.Status.DISABLED) {
            requireUserIsNotSoleProjectOwner(existing)
        }

        val updated = identityRepository.updateUser(
            existing.copy(
                displayName = normalizedDisplayName,
                status = status,
                role = role,
                updatedAt = Clock.System.now(),
            )
        )
        if (existing.role != role || existing.status != status) {
            val now = Clock.System.now()
            identityRepository.revokeAllSessions(userId, now)
            if (status == User.Status.DISABLED) {
                identityRepository.revokeAllPersonalAccessTokens(userId, now)
            }
        }
        securityAuditRecorder.record(
            SecurityAuditRecord(
                actorUserId = actor.id,
                action = SecurityAuditEvent.Action.USER_UPDATED,
                targetType = SecurityAuditEvent.TargetType.USER,
                targetId = updated.id.value,
                attributes = mapOf(
                    "previousDisplayName" to existing.displayName,
                    "displayName" to updated.displayName,
                    "previousRole" to existing.role.name,
                    "role" to updated.role.name,
                    "previousStatus" to existing.status.name,
                    "status" to updated.status.name,
                ),
            )
        )
        stateChanges.publish(DeclarativeStateKey.users, DeclarativeStateKey.userDirectory)
        return updated
    }

    @Transactional
    override suspend fun resetPassword(
        actor: User,
        userId: User.Id,
        password: CharArray,
    ) {
        requireOwner(actor)
        LocalIdentityInputPolicy.validatePassword(password)
        val existing = identityRepository.findUserById(userId)
            ?: throw IllegalArgumentException("User not found: ${userId.value}")
        val now = Clock.System.now()
        identityRepository.updatePasswordCredential(
            LocalPasswordCredential(
                userId = existing.id,
                passwordHash = passwordHasher.hash(password),
                passwordChangedAt = now,
            )
        )
        identityRepository.revokeAllSessions(userId, now)
        identityRepository.revokeAllPersonalAccessTokens(userId, now)
        securityAuditRecorder.record(
            SecurityAuditRecord(
                actorUserId = actor.id,
                action = SecurityAuditEvent.Action.USER_PASSWORD_RESET,
                targetType = SecurityAuditEvent.TargetType.USER,
                targetId = userId.value,
            )
        )
    }

    private suspend fun requireUserIsNotSoleProjectOwner(user: User) {
        val soleOwnedProjectIds = projectMembershipRepository.findByUser(user.id)
            .filter { it.role == ProjectMembership.Role.OWNER }
            .mapNotNull { membership ->
                membership.projectId.takeIf {
                    projectMembershipRepository.countOwners(it) <= 1
                }
            }
            .map { it.value }
        if (soleOwnedProjectIds.isNotEmpty()) {
            throw SoleProjectOwnerException(soleOwnedProjectIds)
        }
    }

    private fun requireOwner(actor: User) {
        if (actor.status != User.Status.ACTIVE || actor.role != User.Role.OWNER) {
            throw UserAdministrationDeniedException()
        }
    }
}
