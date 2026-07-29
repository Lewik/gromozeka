package com.gromozeka.application.service

import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.IssuedUserSession
import com.gromozeka.domain.model.LocalPasswordCredential
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.FirstUserBootstrapToken
import com.gromozeka.domain.service.PasswordHasher
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.Locale
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

@Service
class AuthenticationApplicationService(
    private val identityRepository: IdentityRepository,
    private val passwordHasher: PasswordHasher,
    private val bootstrapToken: FirstUserBootstrapToken,
) : AuthenticationService {
    private val secureRandom = SecureRandom()
    private val dummyPasswordHash = passwordHasher.hash(DUMMY_PASSWORD.toCharArray())

    override suspend fun hasUsers(): Boolean =
        identityRepository.countUsers() > 0

    override suspend fun createFirstUser(
        bootstrapToken: String,
        username: String,
        displayName: String,
        password: CharArray,
        clientLabel: String?,
    ): IssuedUserSession {
        check(!hasUsers()) { "The first user has already been created" }
        val normalizedUsername = normalizeUsername(username)
        val normalizedDisplayName = normalizeDisplayName(displayName, normalizedUsername)
        validatePassword(password)
        check(this.bootstrapToken.consume(bootstrapToken)) { "Invalid or expired bootstrap token" }

        val now = Clock.System.now()
        val user = User(
            id = User.Id(uuid7()),
            username = normalizedUsername,
            displayName = normalizedDisplayName,
            status = User.Status.ACTIVE,
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
        this.bootstrapToken.disable()
        return issueSession(user, clientLabel, now)
    }

    override suspend fun login(
        username: String,
        password: CharArray,
        clientLabel: String?,
    ): IssuedUserSession {
        val normalizedUsername = normalizeUsername(username)
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
        return issueSession(user, clientLabel, Clock.System.now())
    }

    override suspend fun authenticate(sessionToken: String): AuthenticatedUser? {
        if (sessionToken.isBlank()) return null
        val now = Clock.System.now()
        val session = identityRepository.findSessionByTokenHash(hashToken(sessionToken)) ?: return null
        if (session.isRevoked || session.expiresAt <= now) return null
        val user = identityRepository.findUserById(session.userId)
            ?.takeIf { it.status == User.Status.ACTIVE }
            ?: return null

        if (session.lastSeenAt < now - SESSION_TOUCH_INTERVAL) {
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
            expiresAt = now + SESSION_LIFETIME,
            revokedAt = null,
            clientLabel = clientLabel?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_CLIENT_LABEL_LENGTH),
        )
        identityRepository.createSession(session)
        return IssuedUserSession(
            user = user,
            sessionId = session.id,
            token = rawToken,
            expiresAt = session.expiresAt,
        )
    }

    private fun normalizeUsername(username: String): String {
        val normalized = username.trim().lowercase(Locale.ROOT)
        require(normalized.length in USERNAME_LENGTH) {
            "Username must contain ${USERNAME_LENGTH.first} to ${USERNAME_LENGTH.last} characters"
        }
        require(normalized.matches(USERNAME_PATTERN)) {
            "Username may contain lowercase letters, numbers, dots, underscores, and hyphens"
        }
        return normalized
    }

    private fun normalizeDisplayName(displayName: String, fallback: String): String =
        displayName.trim()
            .ifEmpty { fallback }
            .also {
                require(it.length <= MAX_DISPLAY_NAME_LENGTH) {
                    "Display name must not exceed $MAX_DISPLAY_NAME_LENGTH characters"
                }
            }

    private fun validatePassword(password: CharArray) {
        require(password.size in PASSWORD_LENGTH) {
            "Password must contain ${PASSWORD_LENGTH.first} to ${PASSWORD_LENGTH.last} characters"
        }
    }

    private fun hashToken(token: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
        )

    private companion object {
        val USERNAME_LENGTH = 3..128
        val PASSWORD_LENGTH = 12..1024
        val USERNAME_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")
        const val MAX_DISPLAY_NAME_LENGTH = 255
        const val MAX_CLIENT_LABEL_LENGTH = 255
        const val SESSION_TOKEN_BYTES = 32
        val SESSION_LIFETIME = 30.days
        val SESSION_TOUCH_INTERVAL = 5.minutes
        const val DUMMY_PASSWORD = "gromozeka-authentication-timing-padding"
    }
}

class AuthenticationRejectedException : RuntimeException("Invalid username or password")
