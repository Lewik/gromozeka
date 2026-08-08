package com.gromozeka.server

import org.springframework.stereotype.Service
import java.util.Locale
import kotlin.math.ceil
import kotlin.time.Duration.Companion.minutes

@Service
class AuthenticationAttemptLimiter internal constructor(
    private val nanoTime: () -> Long,
) {
    constructor() : this(System::nanoTime)

    private val lock = Any()
    private val failures = LinkedHashMap<String, FailureWindow>(16, 0.75f, true)

    fun retryAfterSeconds(
        remoteAddress: String,
        username: String,
    ): Long? = synchronized(lock) {
        val now = nanoTime()
        removeExpired(now)
        listOf(
            IP_PREFIX + remoteAddress.normalizedRateLimitPart(),
            USER_PREFIX + username.normalizedRateLimitPart(),
        ).mapNotNull { key ->
            failures[key]
                ?.blockedUntilNanos
                ?.takeIf { it > now }
                ?.let { blockedUntil -> nanosToCeilingSeconds(blockedUntil - now) }
        }.maxOrNull()
    }

    fun recordFailure(
        remoteAddress: String,
        username: String,
    ): Unit = synchronized(lock) {
        val now = nanoTime()
        removeExpired(now)
        recordFailure(IP_PREFIX + remoteAddress.normalizedRateLimitPart(), MAX_FAILURES_PER_IP, now)
        recordFailure(USER_PREFIX + username.normalizedRateLimitPart(), MAX_FAILURES_PER_USERNAME, now)
        trimToMaximumSize()
    }

    fun recordSuccess(username: String): Unit = synchronized(lock) {
        failures.remove(USER_PREFIX + username.normalizedRateLimitPart())
    }

    fun deviceConnectionRetryAfterSeconds(remoteAddress: String): Long? = synchronized(lock) {
        val now = nanoTime()
        removeExpired(now)
        failures[DEVICE_CONNECTION_IP_PREFIX + remoteAddress.normalizedRateLimitPart()]
            ?.blockedUntilNanos
            ?.takeIf { it > now }
            ?.let { nanosToCeilingSeconds(it - now) }
    }

    fun recordDeviceConnectionRequest(remoteAddress: String): Unit = synchronized(lock) {
        val now = nanoTime()
        removeExpired(now)
        recordFailure(
            DEVICE_CONNECTION_IP_PREFIX + remoteAddress.normalizedRateLimitPart(),
            MAX_DEVICE_CONNECTION_REQUESTS_PER_IP,
            now,
        )
        trimToMaximumSize()
    }

    private fun recordFailure(
        key: String,
        limit: Int,
        now: Long,
    ) {
        val current = failures[key]
            ?.takeIf { now - it.windowStartedAtNanos < WINDOW_NANOS }
        val failureCount = (current?.failureCount ?: 0) + 1
        failures[key] = FailureWindow(
            windowStartedAtNanos = current?.windowStartedAtNanos ?: now,
            failureCount = failureCount,
            blockedUntilNanos = if (failureCount >= limit) now + LOCKOUT_NANOS else null,
        )
    }

    private fun removeExpired(now: Long) {
        failures.entries.removeAll { (_, window) ->
            val windowExpired = now - window.windowStartedAtNanos >= WINDOW_NANOS
            val lockoutExpired = window.blockedUntilNanos?.let { it <= now } ?: true
            windowExpired && lockoutExpired
        }
    }

    private fun trimToMaximumSize() {
        while (failures.size > MAX_TRACKED_KEYS) {
            failures.entries.iterator().run {
                next()
                remove()
            }
        }
    }

    private data class FailureWindow(
        val windowStartedAtNanos: Long,
        val failureCount: Int,
        val blockedUntilNanos: Long?,
    )

    private companion object {
        const val IP_PREFIX = "ip:"
        const val USER_PREFIX = "user:"
        const val DEVICE_CONNECTION_IP_PREFIX = "device-connection-ip:"
        const val MAX_FAILURES_PER_USERNAME = 8
        const val MAX_FAILURES_PER_IP = 50
        const val MAX_DEVICE_CONNECTION_REQUESTS_PER_IP = 30
        const val MAX_TRACKED_KEYS = 4_096
        val WINDOW_NANOS = 15.minutes.inWholeNanoseconds
        val LOCKOUT_NANOS = 15.minutes.inWholeNanoseconds
    }
}

private fun String.normalizedRateLimitPart(): String =
    trim()
        .lowercase(Locale.ROOT)
        .take(255)
        .ifEmpty { "<empty>" }

private fun nanosToCeilingSeconds(nanos: Long): Long =
    ceil(nanos.toDouble() / 1_000_000_000.0).toLong().coerceAtLeast(1)
