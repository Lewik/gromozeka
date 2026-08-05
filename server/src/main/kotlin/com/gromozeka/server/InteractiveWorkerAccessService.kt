package com.gromozeka.server

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

internal data class DcvInteractiveWorkerAccessTarget(
    val workerId: ConversationRuntimeWorkerId,
    val serverBaseUrl: String,
    val dcvBaseUrl: String,
    val sessionId: String,
    val username: String,
) {
    val openUrl: String =
        "$serverBaseUrl/api/workers/${workerId.value.urlPathComponent()}/interactive-access"
}

internal data class InteractiveWorkerAccessConfiguration(
    val dcvTarget: DcvInteractiveWorkerAccessTarget?,
) {
    companion object {
        fun fromCurrentEnvironment(): InteractiveWorkerAccessConfiguration {
            val values = dcvSettings.associateWith { setting ->
                System.getProperty(setting.propertyName)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: System.getenv(setting.environmentName)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
            }
            if (values.values.all { it == null }) {
                return InteractiveWorkerAccessConfiguration(null)
            }
            check(values.values.none { it == null }) {
                "DCV interactive access settings must be configured together: " +
                    dcvSettings.joinToString { it.environmentName }
            }
            return InteractiveWorkerAccessConfiguration(
                DcvInteractiveWorkerAccessTarget(
                    workerId = ConversationRuntimeWorkerId(values.getValue(DcvSetting.WORKER_ID)!!),
                    serverBaseUrl = values.getValue(DcvSetting.SERVER_BASE_URL)!!
                        .requireHttpsOrigin(DcvSetting.SERVER_BASE_URL.environmentName),
                    dcvBaseUrl = values.getValue(DcvSetting.DCV_BASE_URL)!!
                        .requireHttpsOrigin(DcvSetting.DCV_BASE_URL.environmentName),
                    sessionId = values.getValue(DcvSetting.SESSION_ID)!!,
                    username = values.getValue(DcvSetting.USERNAME)!!,
                )
            )
        }
    }
}

internal class InteractiveWorkerAccessService(
    private val configuration: InteractiveWorkerAccessConfiguration,
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom(),
    private val grantTtl: Duration = Duration.ofSeconds(60),
    private val maximumPendingGrants: Int = 1024,
) {
    private val pendingGrants = LinkedHashMap<String, PendingGrant>()

    init {
        require(!grantTtl.isNegative && !grantTtl.isZero) { "Interactive access grant TTL must be positive" }
        require(maximumPendingGrants > 0) { "Maximum pending interactive access grants must be positive" }
    }

    val configuredWorkerId: ConversationRuntimeWorkerId?
        get() = configuration.dcvTarget?.workerId

    fun openUrl(workerId: ConversationRuntimeWorkerId): String? =
        configuration.dcvTarget
            ?.takeIf { it.workerId == workerId }
            ?.openUrl

    @Synchronized
    fun issueRedirect(workerId: ConversationRuntimeWorkerId): String {
        val target = configuration.dcvTarget
            ?.takeIf { it.workerId == workerId }
            ?: error("Interactive access is not configured for Worker '${workerId.value}'")
        val now = clock.instant()
        discardExpired(now)
        while (pendingGrants.size >= maximumPendingGrants) {
            pendingGrants.remove(pendingGrants.keys.first())
        }

        val tokenBytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(tokenBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        pendingGrants[token.sha256()] = PendingGrant(now.plus(grantTtl))
        return "${target.dcvBaseUrl}/?authToken=$token#${target.sessionId.urlFragment()}"
    }

    @Synchronized
    fun consumeDcvGrant(sessionId: String?, authenticationToken: String?): String? {
        val target = configuration.dcvTarget ?: return null
        if (sessionId != target.sessionId || authenticationToken.isNullOrBlank()) return null
        if (authenticationToken.length > MAX_TOKEN_LENGTH) return null

        val now = clock.instant()
        discardExpired(now)
        val tokenHash = authenticationToken.sha256()
        val grant = pendingGrants[tokenHash] ?: return null
        if (!pendingGrants.remove(tokenHash, grant)) return null
        return target.username
    }

    private fun discardExpired(now: Instant) {
        pendingGrants.entries.removeIf { !it.value.expiresAt.isAfter(now) }
    }

    private data class PendingGrant(
        val expiresAt: Instant,
    )

    private companion object {
        const val TOKEN_BYTES = 32
        const val MAX_TOKEN_LENGTH = 256
    }
}

@Configuration(proxyBeanMethods = false)
internal class InteractiveWorkerAccessBeans {
    @Bean
    fun interactiveWorkerAccessService(): InteractiveWorkerAccessService =
        InteractiveWorkerAccessService(InteractiveWorkerAccessConfiguration.fromCurrentEnvironment())
}

private enum class DcvSetting(
    val propertyName: String,
    val environmentName: String,
) {
    WORKER_ID("gromozeka.dcv-access.worker-id", "GROMOZEKA_DCV_ACCESS_WORKER_ID"),
    SERVER_BASE_URL("gromozeka.dcv-access.server-base-url", "GROMOZEKA_DCV_ACCESS_SERVER_BASE_URL"),
    DCV_BASE_URL("gromozeka.dcv-access.base-url", "GROMOZEKA_DCV_ACCESS_BASE_URL"),
    SESSION_ID("gromozeka.dcv-access.session-id", "GROMOZEKA_DCV_ACCESS_SESSION_ID"),
    USERNAME("gromozeka.dcv-access.username", "GROMOZEKA_DCV_ACCESS_USERNAME"),
}

private val dcvSettings = DcvSetting.entries

private fun String.requireHttpsOrigin(settingName: String): String {
    val uri = runCatching { URI(this) }
        .getOrElse { throw IllegalArgumentException("$settingName must be a valid HTTPS origin", it) }
    require(uri.scheme.equals("https", ignoreCase = true) && uri.host != null) {
        "$settingName must be an HTTPS origin"
    }
    require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "$settingName must not contain credentials, query, or fragment"
    }
    require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
        "$settingName must not contain a path"
    }
    return URI("https", null, uri.host, uri.port, null, null, null).toString().trimEnd('/')
}

private fun String.urlPathComponent(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

private fun String.urlFragment(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
