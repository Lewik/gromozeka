package com.gromozeka.server

import com.gromozeka.domain.model.DeviceConnection
import com.gromozeka.domain.model.DeviceConnectionSessionCredential
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.SecurityAuditRecord
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.repository.DeviceConnectionRepository
import com.gromozeka.domain.service.AuthenticationSessionPolicy
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.LocalCredentialVerifier
import com.gromozeka.domain.service.SecurityAuditRecorder
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.remote.protocol.AuthenticationSessionResponse
import com.gromozeka.remote.protocol.DeviceConnectionChallenge
import com.gromozeka.remote.protocol.DeviceConnectionConsumeResponse
import com.gromozeka.remote.protocol.DeviceConnectionPreview
import com.gromozeka.remote.protocol.DeviceConnectionStartRequest
import com.gromozeka.remote.protocol.toAuthenticatedUserView
import com.gromozeka.shared.uuid.uuid7
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.minutes

@ConfigurationProperties("gromozeka.device-connections")
data class DeviceConnectionProperties(
    val requestTtlMinutes: Long = 10,
    val pollIntervalSeconds: Int = 2,
    val resultRecoveryMinutes: Long = 5,
) {
    init {
        require(requestTtlMinutes in 1..30) { "Device connection TTL must be between 1 and 30 minutes" }
        require(pollIntervalSeconds in 1..10) { "Device connection poll interval must be between 1 and 10 seconds" }
        require(resultRecoveryMinutes in 1..30) {
            "Device connection result recovery must be between 1 and 30 minutes"
        }
    }
}

@Configuration
@EnableConfigurationProperties(DeviceConnectionProperties::class)
class DeviceConnectionConfiguration {
    @Bean
    fun deviceConnectionService(
        properties: DeviceConnectionProperties,
        repository: DeviceConnectionRepository,
        credentialVerifier: LocalCredentialVerifier,
        userDirectoryService: UserDirectoryService,
        workerEnrollmentProperties: WorkerEnrollmentProperties,
        securityAuditRecorder: SecurityAuditRecorder,
    ): DeviceConnectionService = DeviceConnectionService(
        properties = properties,
        repository = repository,
        credentialVerifier = credentialVerifier,
        userDirectoryService = userDirectoryService,
        workerEnrollmentProperties = workerEnrollmentProperties,
        securityAuditRecorder = securityAuditRecorder,
    )
}

class DeviceConnectionService(
    private val properties: DeviceConnectionProperties,
    private val repository: DeviceConnectionRepository,
    private val credentialVerifier: LocalCredentialVerifier,
    private val userDirectoryService: UserDirectoryService,
    private val workerEnrollmentProperties: WorkerEnrollmentProperties,
    private val securityAuditRecorder: SecurityAuditRecorder,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    suspend fun create(request: DeviceConnectionStartRequest): DeviceConnectionChallenge {
        val now = clock.instant().toKotlinInstant()
        val deviceLabel = request.deviceLabel.normalized("Device label", MAX_DEVICE_LABEL_LENGTH)
        val platform = request.platform.normalized("Device platform", MAX_PLATFORM_LENGTH)
        require(request.components.isNotEmpty()) { "At least one device component is required" }
        val clientLabel = if (DeviceConnection.Component.CLIENT in request.components) {
            (request.clientLabel ?: deviceLabel).normalized("Client label", AuthenticationSessionPolicy.maxClientLabelLength)
        } else {
            require(request.clientLabel == null) { "Client label requires the Client component" }
            null
        }
        val worker = if (DeviceConnection.Component.WORKER in request.components) {
            workerEnrollmentProperties.unavailableReason()?.let(::error)
            val workerRequest = requireNotNull(request.worker) { "Worker details are required" }
            require(workerRequest.workerId.matches(workerIdPattern)) {
                "Worker ID must start with a letter or digit and contain at most 64 letters, digits, dots, dashes, or underscores"
            }
            DeviceConnection.WorkerRequest(
                workerId = ConversationRuntimeWorkerId(workerRequest.workerId),
                bindToUser = workerRequest.bindToUser,
            )
        } else {
            require(request.worker == null) { "Worker details require the Worker component" }
            null
        }

        repeat(MAX_CREATE_ATTEMPTS) {
            val deviceToken = randomToken()
            val userCode = randomUserCode()
            val connection = DeviceConnection(
                id = DeviceConnection.Id(uuid7()),
                secretHash = hashToken(deviceToken),
                userCode = userCode,
                deviceLabel = deviceLabel,
                platform = platform,
                components = request.components,
                clientLabel = clientLabel,
                worker = worker,
                status = DeviceConnection.Status.PENDING,
                authorizedUserId = null,
                decidedByUserId = null,
                createdAt = now,
                expiresAt = now + properties.requestTtlMinutes.minutes,
                decidedAt = null,
                consumedAt = null,
            )
            if (repository.create(connection)) {
                return DeviceConnectionChallenge(
                    deviceToken = deviceToken,
                    userCode = userCode,
                    verificationPath = "/",
                    verificationPathComplete = "/?connectDevice=$userCode",
                    expiresAt = connection.expiresAt,
                    pollIntervalSeconds = properties.pollIntervalSeconds,
                )
            }
        }
        error("Could not allocate a unique device connection code")
    }

    suspend fun preview(userCode: String): DeviceConnectionPreview =
        repository.findPendingByUserCode(normalizeUserCode(userCode), now())
            ?.toPreview()
            ?: throw InvalidDeviceConnectionException()

    suspend fun approve(
        userCode: String,
        userId: User.Id,
    ): DeviceConnectionPreview {
        val decision = repository.approve(normalizeUserCode(userCode), userId, now())
            ?: throw InvalidDeviceConnectionException()
        check(decision.connection.status == DeviceConnection.Status.APPROVED) {
            "Device connection is no longer pending"
        }
        check(decision.connection.authorizedUserId == userId) {
            "Device connection was approved by another User"
        }
        if (decision.changed) {
            recordDecision(decision.connection, SecurityAuditEvent.Action.DEVICE_CONNECTION_APPROVED, userId)
        }
        return decision.connection.toPreview()
    }

    suspend fun deny(
        userCode: String,
        userId: User.Id,
    ) {
        val decision = repository.deny(normalizeUserCode(userCode), userId, now())
            ?: throw InvalidDeviceConnectionException()
        check(decision.connection.status == DeviceConnection.Status.DENIED) {
            "Device connection is no longer pending"
        }
        if (decision.changed && decision.connection.status == DeviceConnection.Status.DENIED) {
            recordDecision(decision.connection, SecurityAuditEvent.Action.DEVICE_CONNECTION_DENIED, userId)
        }
    }

    suspend fun approveWithPassword(
        deviceToken: String,
        username: String,
        password: CharArray,
    ): DeviceConnectionConsumeOutcome {
        val connection = findByDeviceToken(deviceToken)
        check(connection.status == DeviceConnection.Status.PENDING) {
            "Device connection is no longer pending"
        }
        check(connection.expiresAt > now()) { "Device connection has expired" }
        val user = credentialVerifier.verifyPassword(username, password)
        approve(connection.userCode, user.id)
        return consume(deviceToken)
    }

    suspend fun consume(deviceToken: String): DeviceConnectionConsumeOutcome {
        val connection = findByDeviceToken(deviceToken)
        val now = now()
        if (
            connection.status == DeviceConnection.Status.CONSUMED &&
            connection.consumedAt?.plus(properties.resultRecoveryMinutes.minutes)?.let { it <= now } == true
        ) {
            return DeviceConnectionConsumeOutcome(
                response = DeviceConnectionConsumeResponse(
                    status = DeviceConnectionConsumeResponse.Status.EXPIRED,
                    message = "Device connection result is no longer available",
                )
            )
        }

        val clientToken = if (DeviceConnection.Component.CLIENT in connection.components) {
            deriveCredential(deviceToken, CLIENT_CREDENTIAL_PURPOSE)
        } else {
            null
        }
        val workerToken = if (DeviceConnection.Component.WORKER in connection.components) {
            deriveCredential(deviceToken, WORKER_CREDENTIAL_PURPOSE)
        } else {
            null
        }
        val consumption = repository.consume(
            secretHash = connection.secretHash,
            consumedAt = now,
            sessionCredential = clientToken?.let {
                DeviceConnectionSessionCredential(
                    id = UserSession.Id("device-connection:${connection.id.value}"),
                    tokenHash = hashToken(it),
                    createdAt = now,
                    expiresAt = now + AuthenticationSessionPolicy.lifetime,
                )
            },
            workerCredentialHash = workerToken?.let(::hashToken),
        ) ?: throw InvalidDeviceConnectionException()

        return when (consumption.connection.status) {
            DeviceConnection.Status.PENDING -> DeviceConnectionConsumeOutcome(
                response = DeviceConnectionConsumeResponse(
                    status = DeviceConnectionConsumeResponse.Status.PENDING,
                    retryAfterSeconds = properties.pollIntervalSeconds,
                )
            )

            DeviceConnection.Status.DENIED -> DeviceConnectionConsumeOutcome(
                response = DeviceConnectionConsumeResponse(
                    status = DeviceConnectionConsumeResponse.Status.DENIED,
                    message = "Device connection was denied",
                )
            )

            DeviceConnection.Status.EXPIRED -> DeviceConnectionConsumeOutcome(
                response = DeviceConnectionConsumeResponse(
                    status = DeviceConnectionConsumeResponse.Status.EXPIRED,
                    message = "Device connection has expired",
                )
            )

            DeviceConnection.Status.CONSUMED -> {
                val authorizedUserId = requireNotNull(consumption.connection.authorizedUserId)
                val sessionResponse = consumption.session?.let { session ->
                    val user = requireNotNull(userDirectoryService.findActiveById(authorizedUserId)) {
                        "Device connection User is unavailable"
                    }
                    AuthenticationSessionResponse(
                        user = user.toAuthenticatedUserView(),
                        expiresAt = session.expiresAt,
                    )
                }
                val workerResponse = consumption.worker?.let { worker ->
                    workerEnrollmentProperties.bootstrap(
                        workerId = worker.id.value,
                        gatewayCredential = requireNotNull(workerToken),
                        subjectUserId = worker.subjectUserId,
                    )
                }
                if (consumption.newlyConsumed) {
                    securityAuditRecorder.record(
                        SecurityAuditRecord(
                            actorUserId = authorizedUserId,
                            action = SecurityAuditEvent.Action.DEVICE_CONNECTED,
                            targetType = SecurityAuditEvent.TargetType.DEVICE_CONNECTION,
                            targetId = consumption.connection.id.value,
                            attributes = consumption.connection.auditAttributes(),
                        )
                    )
                }
                DeviceConnectionConsumeOutcome(
                    response = DeviceConnectionConsumeResponse(
                        status = DeviceConnectionConsumeResponse.Status.CONNECTED,
                        session = sessionResponse,
                        worker = workerResponse,
                    ),
                    sessionToken = clientToken,
                )
            }

            DeviceConnection.Status.APPROVED -> error("Approved device connection was not consumed")
        }
    }

    private suspend fun findByDeviceToken(deviceToken: String): DeviceConnection {
        require(deviceToken.length in 40..128) { "Device connection token is invalid" }
        return repository.findBySecretHash(hashToken(deviceToken))
            ?: throw InvalidDeviceConnectionException()
    }

    private suspend fun recordDecision(
        connection: DeviceConnection,
        action: SecurityAuditEvent.Action,
        actorUserId: User.Id,
    ) {
        securityAuditRecorder.record(
            SecurityAuditRecord(
                actorUserId = actorUserId,
                action = action,
                targetType = SecurityAuditEvent.TargetType.DEVICE_CONNECTION,
                targetId = connection.id.value,
                attributes = connection.auditAttributes(),
            )
        )
    }

    private fun DeviceConnection.toPreview(): DeviceConnectionPreview =
        DeviceConnectionPreview(
            deviceLabel = deviceLabel,
            platform = platform,
            components = components,
            workerId = worker?.workerId?.value,
            workerBindsToUser = worker?.bindToUser == true,
            userCode = userCode,
            expiresAt = expiresAt,
        )

    private fun DeviceConnection.auditAttributes(): Map<String, String> = buildMap {
        put("deviceLabel", deviceLabel)
        put("platform", platform)
        put("components", components.sortedBy { it.name }.joinToString(",") { it.name })
        worker?.let {
            put("workerId", it.workerId.value)
            put("workerBindsToUser", it.bindToUser.toString())
        }
    }

    private fun randomToken(): String =
        ByteArray(TOKEN_BYTES)
            .also(secureRandom::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun randomUserCode(): String =
        CharArray(USER_CODE_LENGTH) {
            USER_CODE_ALPHABET[secureRandom.nextInt(USER_CODE_ALPHABET.length)]
        }.concatToString().let { "${it.take(4)}-${it.drop(4)}" }

    private fun deriveCredential(deviceToken: String, purpose: String): String {
        val key = Base64.getUrlDecoder().decode(deviceToken)
        return Mac.getInstance(HMAC_SHA_256)
            .apply { init(SecretKeySpec(key, HMAC_SHA_256)) }
            .doFinal(purpose.encodeToByteArray())
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }

    private fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun normalizeUserCode(value: String): String {
        val compact = value.uppercase().filter(Char::isLetterOrDigit)
        require(compact.length == USER_CODE_LENGTH && compact.all { it in USER_CODE_ALPHABET }) {
            "Device connection code is invalid"
        }
        return "${compact.take(4)}-${compact.drop(4)}"
    }

    private fun String.normalized(label: String, maxLength: Int): String =
        trim().also {
            require(it.isNotEmpty()) { "$label must not be blank" }
            require(it.length <= maxLength) { "$label must contain at most $maxLength characters" }
        }

    private fun now(): kotlin.time.Instant = clock.instant().toKotlinInstant()

    private fun java.time.Instant.toKotlinInstant(): kotlin.time.Instant =
        kotlin.time.Instant.fromEpochMilliseconds(toEpochMilli())

    private companion object {
        const val TOKEN_BYTES = 32
        const val USER_CODE_LENGTH = 8
        const val USER_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val MAX_CREATE_ATTEMPTS = 5
        const val MAX_DEVICE_LABEL_LENGTH = 255
        const val MAX_PLATFORM_LENGTH = 64
        const val HMAC_SHA_256 = "HmacSHA256"
        const val CLIENT_CREDENTIAL_PURPOSE = "gromozeka-device-client-session-v1"
        const val WORKER_CREDENTIAL_PURPOSE = "gromozeka-device-worker-credential-v1"
    }
}

data class DeviceConnectionConsumeOutcome(
    val response: DeviceConnectionConsumeResponse,
    val sessionToken: String? = null,
)

class InvalidDeviceConnectionException : IllegalArgumentException("Device connection is invalid or expired")
