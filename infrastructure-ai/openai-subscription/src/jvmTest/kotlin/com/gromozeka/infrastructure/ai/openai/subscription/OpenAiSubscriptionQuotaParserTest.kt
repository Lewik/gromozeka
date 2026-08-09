package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.ai.AiConnection
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OpenAiSubscriptionQuotaParserTest {
    @Test
    fun `parser maps Codex primary and secondary windows`() {
        val connection = AiConnection.OpenAiSubscription(
            id = AiConnection.Id("codex"),
            displayName = "Codex",
            enabled = true,
        )
        val observedAt = Instant.parse("2026-08-10T00:00:00Z")
        val snapshot = OpenAiSubscriptionQuotaParser.parse(
            body = """
                {
                  "plan_type": "pro",
                  "rate_limit": {
                    "allowed": true,
                    "limit_reached": false,
                    "primary_window": {
                      "used_percent": 42,
                      "limit_window_seconds": 18000,
                      "reset_after_seconds": 9000,
                      "reset_at": 1786348800
                    },
                    "secondary_window": {
                      "used_percent": 18,
                      "limit_window_seconds": 604800,
                      "reset_after_seconds": 300000,
                      "reset_at": 1786903200
                    }
                  }
                }
            """.trimIndent(),
            connection = connection,
            observedAt = observedAt,
        )

        assertEquals(connection.id, snapshot.connectionId)
        assertEquals(observedAt, snapshot.observedAt)
        assertFalse(snapshot.usageBlocked)
        assertEquals(listOf("primary", "secondary"), snapshot.windows.map { it.id })
        assertEquals(42.0, snapshot.windows.first().usedPercent)
        assertEquals(18_000_000L, snapshot.windows.first().resetsAt.toEpochMilliseconds() - snapshot.windows.first().startedAt.toEpochMilliseconds())
    }
}
