package com.gromozeka.application.service

import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.IssuedUserSession
import com.gromozeka.domain.model.LocalPasswordCredential
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.SecurityAuditRecord
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.repository.ProjectMembershipRepository
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.AuthenticationSessionPolicy
import com.gromozeka.domain.service.FirstUserBootstrapToken
import com.gromozeka.domain.service.LocalCredentialVerifier
import com.gromozeka.domain.service.PasswordHasher
import com.gromozeka.domain.service.SecurityAuditRecorder
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

@Service
class AuthenticationApplicationService(
    private val identityRepository: IdentityRepository,
    private val projectMembershipRepository: ProjectMembershipRepository,
    private val passwordHasher: PasswordHasher,
    private val bootstrapToken: FirstUserBootstrapToken,
    private val securityAuditRecorder: SecurityAuditRecorder,
) : AuthenticationService, LocalCredentialVerifier {
    private val secureRandom = SecureRandom()
    private val dummyPasswordHash = passwordHasher.hash(DUMMY_PASSWORD.toCharArray())

    override suspend fun hasUsers(): Boolean =
        identityRepository.countUsers() > 0

    @Transactional
    override suspend fun createFirstUser(
        bootstrapToken: String,
        username: String,
        displayName: String,
        password: CharArray,
        clientLabel: String?,
    ): IssuedUserSession {
        check(!hasUsers()) { "The first user has already been created" }
        val normalizedUsername = LocalIdentityInputPolicy.normalizeUsername(username)
        val normalizedDisplayName = LocalIdentityInputPolicy.normalizeDisplayName(displayName, normalizedUsername)
        LocalIdentityInputPolicy.validatePassword(password)
        check(this.bootstrapToken.consume(bootstrapToken)) { "Invalid or expired bootstrap token" }

        val now = Clock.System.now()
        val user = User(
            id = User.Id(uuid7()),
            username = normalizedUsername,
            displayName = normalizedDisplayName,
            status = User.Status.ACTIVE,
            role = User.Role.OWNER,
            createdAt = now,
            updatedAt = now,
        )
        identityRepository.createUser(
            user,
            LocalPasswordCredential(
                userId = user.id,
                passwordHash = passwordHasher.hash(password),
                passwordChangedAt = now,
            ),
        )
        projectMembershipRepository.assignUnownedProjectsToFirstOwner(user.id, now)
        this.bootstrapToken.disable()
        val session = issueSession(user, clientLabel, now)
        securityAuditRecorder.record(
            SecurityAuditRecord(
                actorUserId = user.id,
                action = SecurityAuditEvent.Action.RUNTIME_BOOTSTRAPPED,
                targetType = SecurityAuditEvent.TargetType.RUNTIME,
                targetId = "runtime",
                attributes = mapOf("ownerUsername" to user.username),
            )
        )
        return session
    }

    override suspend fun login(
        username: String,
        password: CharArray,
        clientLabel: String?,
    ): IssuedUserSession =
        issueSession(
            user = verifyPassword(username, password),
            clientLabel = clientLabel,
            now = Clock.System.now(),
        )

    override suspend fun verifyPassword(
        username: String,
        password: CharArray,
    ): User {
        val normalizedUsername = LocalIdentityInputPolicy.normalizeUsername(username)
        val user = identityRepository.findUserByUsername(normalizedUsername)
        val credential = user?.let { identityRepository.findPasswordCredential(it.id) }
        val passwordMatches = passwordHasher.verify(
            password,
            credential?.passwordHash ?: dummyPasswordHash,
        )
        if (
            user == null ||
            user.status != User.Status.ACTIVE ||
            credential == null ||
            !passwordMatches
        ) {
            throw AuthenticationRejectedException()
        }

        if (passwordHasher.needsRehash(credential.passwordHash)) {
            identityRepository.updatePasswordCredential(
                credential.copy(
                    passwordHash = passwordHasher.hash(password),
                    passwordChangedAt = Clock.System.now(),
                )
            )
        }
        return user
    }

    override suspend fun authenticate(sessionToken: String): AuthenticatedUser? {
        if (sessionToken.isBlank()) return null
        val now = Clock.System.now()
        val session = identityRepository.findSessionByTokenHash(hashToken(sessionToken)) ?: return null
        if (session.isRevoked || session.expiresAt <= now) return null
        val user = identityRepository.findUserById(session.userId)
            ?.takeIf { it.status == User.Status.ACTIVE }
            ?: return null

        if (session.lastSeenAt < now - AuthenticationSessionPolicy.touchInterval) {
            identityRepository.touchSession(session.id, now)
        }
        return AuthenticatedUser(user, session.id)
    }

    override suspend fun logout(sessionToken: String) {
        if (sessionToken.isBlank()) return
        identityRepository.findSessionByTokenHash(hashToken(sessionToken))
            ?.let { identityRepository.revokeSession(it.id, Clock.System.now()) }
    }

    override suspend fun revokeAllSessions(userId: User.Id) {
        identityRepository.revokeAllSessions(userId, Clock.System.now())
    }

    private suspend fun issueSession(
        user: User,
        clientLabel: String?,
        now: kotlinx.datetime.Instant,
    ): IssuedUserSession {
        val rawToken = ByteArray(SESSION_TOKEN_BYTES)
            .also(secureRandom::nextBytes)
            .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
        val session = UserSession(
            id = UserSession.Id(uuid7()),
            userId = user.id,
            tokenHash = hashToken(rawToken),
            createdAt = now,
            lastSeenAt = now,
            expiresAt = now + AuthenticationSessionPolicy.lifetime,
            revokedAt = null,
            clientLabel = clientLabel
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.take(AuthenticationSessionPolicy.maxClientLabelLength),
        )
        identityRepository.createSession(session)
        return IssuedUserSession(
            user = user,
            sessionId = session.id,
            token = rawToken,
            expiresAt = session.expiresAt,
        )
    }

    private fun hashToken(token: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
        )

    private companion object {
        const val SESSION_TOKEN_BYTES = 32
        const val DUMMY_PASSWORD = "gromozeka-authentication-timing-padding"
    }
}

class AuthenticationRejectedException : RuntimeException("Invalid username or password")
