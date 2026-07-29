package com.gromozeka.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class AuthenticationAttemptLimiterTest {
    private var now = 0L
    private val limiter = AuthenticationAttemptLimiter { now }

    @Test
    fun `username is temporarily blocked after repeated failures`() {
        repeat(8) {
            assertNull(limiter.retryAfterSeconds("192.0.2.1", "owner"))
            limiter.recordFailure("192.0.2.1", "owner")
        }

        assertEquals(15.minutes.inWholeSeconds, limiter.retryAfterSeconds("192.0.2.2", "OWNER"))
        assertNull(limiter.retryAfterSeconds("192.0.2.2", "another-user"))
    }

    @Test
    fun `successful login clears username failures`() {
        repeat(8) {
            limiter.recordFailure("192.0.2.1", "owner")
        }

        limiter.recordSuccess("OWNER")

        assertNull(limiter.retryAfterSeconds("192.0.2.2", "owner"))
    }

    @Test
    fun `remote address is blocked across usernames`() {
        repeat(50) { index ->
            limiter.recordFailure("192.0.2.1", "user-$index")
        }

        assertTrue(limiter.retryAfterSeconds("192.0.2.1", "new-user")!! > 0)
        assertNull(limiter.retryAfterSeconds("192.0.2.2", "new-user"))
    }

    @Test
    fun `expired failure window is discarded`() {
        repeat(8) {
            limiter.recordFailure("192.0.2.1", "owner")
        }
        now += 16.minutes.inWholeNanoseconds

        assertNull(limiter.retryAfterSeconds("192.0.2.1", "owner"))
    }
}
