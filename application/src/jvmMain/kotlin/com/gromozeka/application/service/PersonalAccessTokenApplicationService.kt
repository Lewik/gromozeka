package com.gromozeka.application.service

import com.gromozeka.domain.model.AuthenticatedPersonalAccessToken
import com.gromozeka.domain.model.IssuedPersonalAccessToken
import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.SecurityAuditRecord
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.service.PersonalAccessTokenService
import com.gromozeka.domain.service.SecurityAuditRecorder
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

@Service
class PersonalAccessTokenApplicationService(
    private val identityRepository: IdentityRepository,
    private val securityAuditRecorder: SecurityAuditRecorder,
) : PersonalAccessTokenService {
    private val secureRandom = SecureRandom()

    override suspend fun issue(
        userId: User.Id,
        name: String,
        scopes: Set<PersonalAccessToken.Scope>,
        expiresAt: Instant?,
    ): IssuedPersonalAccessToken {
        val user = identityRepository.findUserById(userId)
            ?.takeIf { it.status == User.Status.ACTIVE }
            ?: error("Active user not found: ${userId.value}")
        val normalizedName = name.trim()
        require(normalizedName.length in TOKEN_NAME_LENGTH) {
            "Token name must contain ${TOKEN_NAME_LENGTH.first} to ${TOKEN_NAME_LENGTH.last} characters"
        }
        require(scopes.isNotEmpty()) { "Personal access token must have at least one scope" }

        val now = Clock.System.now()
        require(expiresAt == null || expiresAt > now) {
            "Personal access token expiration must be in the future"
        }
        require(expiresAt == null || expiresAt <= now + MAX_TOKEN_LIFETIME) {
            "Personal access token lifetime must not exceed ${MAX_TOKEN_LIFETIME.inWholeDays} days"
        }
        check(identityRepository.countActivePersonalAccessTokens(user.id, now) < MAX_ACTIVE_TOKENS_PER_USER) {
            "Active personal access token limit reached"
        }

        val rawToken = TOKEN_PREFIX + ByteArray(TOKEN_BYTES)
            .also(secureRandom::nextBytes)
            .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
        val token = PersonalAccessToken(
            id = PersonalAccessToken.Id(uuid7()),
            userId = user.id,
            name = normalizedName,
            tokenHash = hashToken(rawToken),
            tokenPrefix = rawToken.take(DISPLAY_PREFIX_LENGTH),
            scopes = scopes,
            createdAt = now,
            expiresAt = expiresAt,
            lastUsedAt = null,
            revokedAt = null,
        )
        identityRepository.createPersonalAccessToken(token)
        securityAuditRecorder.record(
            SecurityAuditRecord(
                actorUserId = user.id,
                action = SecurityAuditEvent.Action.PERSONAL_ACCESS_TOKEN_ISSUED,
                targetType = SecurityAuditEvent.TargetType.PERSONAL_ACCESS_TOKEN,
                targetId = token.id.value,
                attributes = mapOf(
                    "name" to token.name,
                    "scopes" to token.scopes.map { it.name }.sorted().joinToString(","),
                ),
            )
        )
        return IssuedPersonalAccessToken(token, rawToken)
    }

    override suspend fun list(userId: User.Id): List<PersonalAccessToken> =
        identityRepository.listPersonalAccessTokens(userId)

    override suspend fun revoke(
        userId: User.Id,
        tokenId: PersonalAccessToken.Id,
    ): Boolean {
        val revoked = identityRepository.revokePersonalAccessToken(userId, tokenId, Clock.System.now())
        if (revoked) {
            securityAuditRecorder.record(
                SecurityAuditRecord(
                    actorUserId = userId,
                    action = SecurityAuditEvent.Action.PERSONAL_ACCESS_TOKEN_REVOKED,
                    targetType = SecurityAuditEvent.TargetType.PERSONAL_ACCESS_TOKEN,
                    targetId = tokenId.value,
                )
            )
        }
        return revoked
    }

    override suspend fun authenticate(
        rawToken: String,
        requiredScope: PersonalAccessToken.Scope,
    ): AuthenticatedPersonalAccessToken? {
        if (!rawToken.startsWith(TOKEN_PREFIX) || rawToken.length > MAX_RAW_TOKEN_LENGTH) return null
        val token = identityRepository.findPersonalAccessTokenByHash(hashToken(rawToken)) ?: return null
        val now = Clock.System.now()
        if (token.isRevoked || token.expiresAt?.let { it <= now } == true || requiredScope !in token.scopes) {
            return null
        }
        val user = identityRepository.findUserById(token.userId)
            ?.takeIf { it.status == User.Status.ACTIVE }
            ?: return null
        val lastUsedAt = token.lastUsedAt
        if (lastUsedAt == null || lastUsedAt < now - LAST_USED_TOUCH_INTERVAL) {
            identityRepository.touchPersonalAccessToken(token.id, now)
        }
        return AuthenticatedPersonalAccessToken(user, token)
    }

    private fun hashToken(token: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
        )

    private companion object {
        const val TOKEN_PREFIX = "grz_pat_"
        const val TOKEN_BYTES = 32
        const val DISPLAY_PREFIX_LENGTH = 20
        const val MAX_RAW_TOKEN_LENGTH = 128
        const val MAX_ACTIVE_TOKENS_PER_USER = 32
        val TOKEN_NAME_LENGTH = 1..128
        val MAX_TOKEN_LIFETIME = 3_650.days
        val LAST_USED_TOUCH_INTERVAL = 5.minutes
    }
}
