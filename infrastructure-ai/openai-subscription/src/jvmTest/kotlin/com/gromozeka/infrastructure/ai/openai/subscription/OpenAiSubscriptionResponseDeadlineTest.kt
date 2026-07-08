package com.gromozeka.infrastructure.ai.openai.subscription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiSubscriptionResponseDeadlineTest {
    @Test
    fun expiresByWallClockTime() {
        var now = 1_000L
        val deadline = OpenAiSubscriptionResponseDeadline.after(timeoutMs = 5_000L) { now }

        assertEquals(5_000L, deadline.remainingMs())

        now = 6_001L

        assertTrue(deadline.remainingMs() <= 0L)
    }

    @Test
    fun coercesNonPositiveTimeoutToOneMillisecond() {
        var now = 10L
        val deadline = OpenAiSubscriptionResponseDeadline.after(timeoutMs = 0L) { now }

        assertEquals(1L, deadline.remainingMs())

        now = 11L

        assertEquals(0L, deadline.remainingMs())
    }

    @Test
    fun saturatesDeadlineOnOverflow() {
        var now = Long.MAX_VALUE - 10L
        val deadline = OpenAiSubscriptionResponseDeadline.after(timeoutMs = 100L) { now }

        assertEquals(10L, deadline.remainingMs())
    }
}
