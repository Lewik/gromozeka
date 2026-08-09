package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeCodeSubscriptionQuotaParserTest {
    private val connection = AiConnection.ClaudeCode(
        id = AiConnection.Id("claude"),
        displayName = "Claude Code",
        executionTarget = AiExecutionTarget.Worker("worker"),
    )

    @Test
    fun `parser maps common and matching model windows`() {
        val snapshot = ClaudeCodeSubscriptionQuotaParser.parse(
            body = """
                {
                  "five_hour": {"utilization": 21.5, "resets_at": "2026-08-10T05:00:00Z"},
                  "seven_day": {"utilization": 32, "resets_at": "2026-08-17T00:00:00Z"},
                  "seven_day_opus": {"utilization": 48, "resets_at": "2026-08-17T01:00:00Z"},
                  "seven_day_sonnet": null
                }
            """.trimIndent(),
            connection = connection,
            modelId = "claude-opus-4-1",
            observedAt = Instant.parse("2026-08-10T00:00:00Z"),
        )

        assertEquals(listOf("five_hour", "seven_day", "seven_day_opus"), snapshot.windows.map { it.id })
        assertEquals(48.0, snapshot.windows.last().usedPercent)
    }

    @Test
    fun `parser maps matching scoped limit`() {
        val snapshot = ClaudeCodeSubscriptionQuotaParser.parse(
            body = """
                {
                  "five_hour": {"utilization": 10, "resets_at": "2026-08-10T05:00:00Z"},
                  "seven_day": {"utilization": 20, "resets_at": "2026-08-17T00:00:00Z"},
                  "limits": [
                    {
                      "kind": "weekly_scoped",
                      "scope": {"model": {"display_name": "Claude Opus 4.1"}},
                      "percent": 30,
                      "resets_at": "2026-08-17T01:00:00Z"
                    },
                    {
                      "kind": "weekly_scoped",
                      "scope": {"model": {"display_name": "Claude Sonnet 4"}},
                      "percent": 40,
                      "resets_at": "2026-08-17T01:00:00Z"
                    }
                  ]
                }
            """.trimIndent(),
            connection = connection,
            modelId = "claude-opus-4-1",
        )

        assertEquals(listOf("five_hour", "seven_day", "weekly_scoped_0"), snapshot.windows.map { it.id })
    }
}
