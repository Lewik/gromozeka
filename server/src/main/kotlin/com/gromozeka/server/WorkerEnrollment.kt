package com.gromozeka.server

import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.remote.protocol.WorkerEnrollmentAvailability
import com.gromozeka.remote.protocol.WorkerEnrollmentBootstrap
import com.gromozeka.remote.protocol.WorkerEnrollmentToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val postgresJdbcUrl: String = "",
    val postgresUsername: String = "",
    val postgresPassword: String = "",
    val rabbitmqAddresses: String = "",
    val rabbitmqUsername: String = "",
    val rabbitmqPassword: String = "",
    val capabilities: Set<ConversationRuntimeCapability> = setOf(
        ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
        ConversationRuntimeCapability.TOOL_EXECUTION,
        ConversationRuntimeCapability.LOCAL_AGENT_TOOL,
    ),
) {
    fun unavailableReason(): String? {
        if (!enabled) return "Worker enrollment is disabled"
        if (tokenTtlMinutes !in 1..60) return "Worker enrollment token TTL must be between 1 and 60 minutes"
        val missing = buildList {
            if (postgresJdbcUrl.isBlank()) add("PostgreSQL JDBC URL")
            if (postgresUsername.isBlank()) add("PostgreSQL username")
            if (postgresPassword.isBlank()) add("PostgreSQL password")
            if (rabbitmqAddresses.isBlank()) add("RabbitMQ addresses")
            if (rabbitmqUsername.isBlank()) add("RabbitMQ username")
            if (rabbitmqPassword.isBlank()) add("RabbitMQ password")
            if (capabilities.isEmpty()) add("Worker capabilities")
        }
        return missing.takeIf(List<String>::isNotEmpty)
            ?.joinToString(prefix = "Worker enrollment is missing: ")
    }

    fun bootstrap(workerId: String): WorkerEnrollmentBootstrap =
        WorkerEnrollmentBootstrap(
            workerId = workerId,
            postgresJdbcUrl = postgresJdbcUrl,
            postgresUsername = postgresUsername,
            postgresPassword = postgresPassword,
            rabbitmqAddresses = rabbitmqAddresses,
            rabbitmqUsername = rabbitmqUsername,
            rabbitmqPassword = rabbitmqPassword,
            capabilities = capabilities,
        )
}

@Configuration
@EnableConfigurationProperties(WorkerEnrollmentProperties::class)
class WorkerEnrollmentConfiguration {
    @Bean
    fun workerEnrollmentService(
        properties: WorkerEnrollmentProperties,
    ): WorkerEnrollmentService = WorkerEnrollmentService(properties)
}

class WorkerEnrollmentService(
    private val properties: WorkerEnrollmentProperties,
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val mutex = Mutex()
    private val tokensByHash = mutableMapOf<String, Instant>()

    fun availability(): WorkerEnrollmentAvailability =
        properties.unavailableReason()?.let {
            WorkerEnrollmentAvailability(available = false, unavailableReason = it)
        } ?: WorkerEnrollmentAvailability(available = true)

    suspend fun create(): WorkerEnrollmentToken {
        properties.unavailableReason()?.let { error(it) }
        val tokenBytes = ByteArray(32).also(secureRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val expiresAt = clock.instant().plus(Duration.ofMinutes(properties.tokenTtlMinutes))
        mutex.withLock {
            removeExpiredTokens()
            tokensByHash[tokenHash(token)] = expiresAt
        }
        return WorkerEnrollmentToken(token = token, expiresAt = expiresAt.toString())
    }

    suspend fun consume(token: String, workerId: String): WorkerEnrollmentBootstrap {
        properties.unavailableReason()?.let { error(it) }
        require(workerId.matches(workerIdPattern)) {
            "Worker ID must start with a letter or digit and contain at most 64 letters, digits, dots, dashes, or underscores"
        }
        require(token.length in 40..128) { "Worker enrollment token is invalid or expired" }

        val accepted = mutex.withLock {
            removeExpiredTokens()
            tokensByHash.remove(tokenHash(token)) != null
        }
        require(accepted) { "Worker enrollment token is invalid or expired" }
        return properties.bootstrap(workerId)
    }

    private fun removeExpiredTokens() {
        val now = clock.instant()
        tokensByHash.entries.removeAll { (_, expiresAt) -> !expiresAt.isAfter(now) }
    }

    private fun tokenHash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
}

private val workerIdPattern = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,63}""")
