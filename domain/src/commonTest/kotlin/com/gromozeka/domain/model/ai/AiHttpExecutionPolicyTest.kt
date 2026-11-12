package com.gromozeka.domain.model.ai

import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AiHttpExecutionPolicyTest {
    @Test
    fun `deadline never extends upstream budget and model timeout is bounded`() {
        val now = Instant.parse("2026-11-07T00:00:00Z")
        assertEquals(now + 600.seconds, AiHttpCallBudget.deadline(null, null, now))
        assertEquals(now + 10.seconds, AiHttpCallBudget.deadline(10, now + 20.seconds, now))
        assertEquals(now + 5.seconds, AiHttpCallBudget.deadline(10, now + 5.seconds, now))
        assertEquals(now - 1.seconds, AiHttpCallBudget.deadline(10, now - 1.seconds, now))
        for (value in listOf(0, -1, 86401)) assertFailsWith<IllegalArgumentException> { AiHttpCallBudget.deadline(value, null, now) }
        assertEquals(now + 86400.seconds, AiHttpCallBudget.deadline(86400, null, now))
    }

    @Test
    fun `HTTP limits round trip and invalid values fail instead of being clamped`() {
        val settings = AiHttpSettings(maxAttempts = 4)
        assertEquals(settings, Json.decodeFromString<AiHttpSettings>(Json.encodeToString(settings)))
        assertEquals(3, Json.decodeFromString<AiHttpSettings>("{}").maxAttempts)
        for (value in listOf(0, -1, 6)) assertFailsWith<IllegalArgumentException> { AiHttpSettings(maxAttempts = value) }
    }

    @Test
    fun `typed failure serializes only allowlisted fields and can represent unknown attempt count`() {
        val failure = AiCallFailure(AiCallFailure.Kind.WORKER_FAILURE, AiCallFailure.Outcome.UNKNOWN, null, workerRequestId = "request-123")
        assertEquals(failure, Json.decodeFromString<AiCallFailure>(Json.encodeToString(failure)))
        assertContains(AiCallException(failure).message.orEmpty(), "Attempts: unknown")
        assertFailsWith<IllegalArgumentException> { failure.copy(workerRequestId = "private\nvalue") }
        assertFailsWith<IllegalArgumentException> { failure.copy(attempts = -1) }
        assertFailsWith<IllegalArgumentException> { failure.copy(httpStatus = 1000) }
        assertFailsWith<IllegalArgumentException> { failure.copy(retryAfterMillis = -1) }
    }
}
