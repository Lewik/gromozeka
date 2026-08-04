package com.gromozeka.server

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.SecurityAuditRecord
import com.gromozeka.domain.repository.WorkerEnrollmentRepository
import com.gromozeka.domain.service.SecurityAuditRecorder
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.remote.protocol.WorkerEnrollmentAvailability
import com.gromozeka.remote.protocol.WorkerEnrollmentBootstrap
import com.gromozeka.remote.protocol.WorkerEnrollmentToken
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

@ConfigurationProperties("gromozeka.worker-enrollment")
data class WorkerEnrollmentProperties(
    val enabled: Boolean = false,
    val tokenTtlMinutes: Long = 15,
    val capabilities: Set<ConversationRuntimeCapability> = setOf(
        ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
        ConversationRuntimeCapability.AUDIO_CAPTURE,
        ConversationRuntimeCapability.TOOL_EXECUTION,
        ConversationRuntimeCapability.LOCAL_AGENT_TOOL,
        ConversationRuntimeCapability.COMPUTER_USE,
    ),
) {
    fun unavailableReason(): String? {
        if (!enabled) return "Worker enrollment is disabled"
        if (tokenTtlMinutes !in 1..60) return "Worker enrollment token TTL must be between 1 and 60 minutes"
        if (capabilities.isEmpty()) return "Worker enrollment capabilities must not be empty"
        return null
    }

    fun bootstrap(
        workerId: String,
        gatewayCredential: String,
    ): WorkerEnrollmentBootstrap =
        WorkerEnrollmentBootstrap(
            workerId = workerId,
            gatewayCredential = gatewayCredential,
            capabilities = capabilities,
        )
}

@Configuration
@EnableConfigurationProperties(WorkerEnrollmentProperties::class)
class WorkerEnrollmentConfiguration {
    @Bean
    fun workerEnrollmentService(
        properties: WorkerEnrollmentProperties,
        repository: WorkerEnrollmentRepository,
        securityAuditRecorder: SecurityAuditRecorder,
    ): WorkerEnrollmentService = WorkerEnrollmentService(
        properties = properties,
        repository = repository,
        securityAuditRecorder = securityAuditRecorder,
    )
}

class WorkerEnrollmentService(
    private val properties: WorkerEnrollmentProperties,
    private val repository: WorkerEnrollmentRepository,
    private val securityAuditRecorder: SecurityAuditRecorder,
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun availability(): WorkerEnrollmentAvailability =
        properties.unavailableReason()?.let {
            WorkerEnrollmentAvailability(available = false, unavailableReason = it)
        } ?: WorkerEnrollmentAvailability(available = true)

    suspend fun create(ownerUserId: User.Id): WorkerEnrollmentToken {
        properties.unavailableReason()?.let { error(it) }
        val tokenBytes = ByteArray(32).also(secureRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val createdAt = clock.instant()
        val expiresAt = createdAt.plus(Duration.ofMinutes(properties.tokenTtlMinutes))
        repository.issue(
            tokenHash = tokenHash(token),
            ownerUserId = ownerUserId,
            createdAt = createdAt.toKotlinx(),
            expiresAt = expiresAt.toKotlinx(),
        )
        securityAuditRecorder.record(
            SecurityAuditRecord(
                actorUserId = ownerUserId,
                action = SecurityAuditEvent.Action.WORKER_ENROLLMENT_CREATED,
                targetType = SecurityAuditEvent.TargetType.RUNTIME,
                targetId = "runtime",
                attributes = mapOf("expiresAt" to expiresAt.toString()),
            )
        )
        return WorkerEnrollmentToken(token = token, expiresAt = expiresAt.toString())
    }

    suspend fun consume(token: String, workerId: String): WorkerEnrollmentBootstrap {
        properties.unavailableReason()?.let { error(it) }
        require(workerId.matches(workerIdPattern)) {
            "Worker ID must start with a letter or digit and contain at most 64 letters, digits, dots, dashes, or underscores"
        }
        require(token.length in 40..128) { "Worker enrollment token is invalid or expired" }

        val gatewayCredential = randomToken()
        val worker = repository.consume(
            tokenHash = tokenHash(token),
            gatewayCredentialHash = tokenHash(gatewayCredential),
            workerId = ConversationRuntimeWorkerId(workerId),
            displayName = workerId,
            consumedAt = clock.instant().toKotlinx(),
        )
        require(worker != null) { "Worker enrollment token is invalid or expired" }
        securityAuditRecorder.record(
            SecurityAuditRecord(
                actorUserId = worker.ownerUserId,
                action = SecurityAuditEvent.Action.WORKER_ENROLLED,
                targetType = SecurityAuditEvent.TargetType.WORKER,
                targetId = worker.id.value,
            )
        )
        return properties.bootstrap(worker.id.value, gatewayCredential)
    }

    private fun randomToken(): String =
        ByteArray(32)
            .also(secureRandom::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun tokenHash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun Instant.toKotlinx(): kotlinx.datetime.Instant =
        kotlinx.datetime.Instant.fromEpochMilliseconds(toEpochMilli())
}

private val workerIdPattern = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,63}""")
