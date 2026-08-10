package com.gromozeka.infrastructure.ai.copilot

import com.github.copilot.generated.rpc.AccountQuotaSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import java.time.OffsetDateTime
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubCopilotQuotaMapperTest {
    private val connectionId = AiConnection.Id("copilot")
    private val observedAt = Instant.parse("2026-08-10T00:00:00Z")

    @Test
    fun `mapper prefers request counters`() {
        val snapshot = GitHubCopilotQuotaMapper.map(
            snapshots = mapOf(
                "premium_interactions" to quota(
                    entitlementRequests = 1_000,
                    usedRequests = 250,
                    remainingPercentage = 10.0,
                ),
            ),
            connectionId = connectionId,
            observedAt = observedAt,
        )

        assertFalse(snapshot.unlimited)
        assertEquals(25.0, snapshot.windows.single().usedPercent)
        assertEquals("premium interactions", snapshot.windows.single().displayName)
    }

    @Test
    fun `mapper marks unlimited account`() {
        val snapshot = GitHubCopilotQuotaMapper.map(
            snapshots = mapOf("chat" to quota(unlimited = true)),
            connectionId = connectionId,
            observedAt = observedAt,
        )

        assertTrue(snapshot.unlimited)
        assertTrue(snapshot.windows.isEmpty())
    }

    private fun quota(
        unlimited: Boolean = false,
        entitlementRequests: Long? = null,
        usedRequests: Long? = null,
        remainingPercentage: Double? = null,
    ) = AccountQuotaSnapshot(
        unlimited,
        entitlementRequests,
        usedRequests,
        false,
        remainingPercentage,
        null,
        false,
        OffsetDateTime.parse("2026-09-01T00:00:00Z"),
    )
}
