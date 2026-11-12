package com.gromozeka.domain.model.ai

import kotlinx.serialization.json.Json
import kotlin.test.*

class AiTpmPolicyTest {
    @Test fun groupsHaveExplicitKeysNoImplicitParallelismAndNoInvalidCapacity() {
        val group = AiTpmGroup(AiTpmGroup.Id("id"), "account/model", "Group", 1000)
        assertFalse(group.allowParallelRequests)
        assertEquals(1.0, group.quotaUsageFraction)
        assertEquals(60, group.renewalPeriodSeconds)
        assertEquals(group, Json.decodeFromString<AiTpmGroup>(Json.encodeToString(AiTpmGroup.serializer(), group)))
        assertFailsWith<IllegalArgumentException> { group.copy(tokenLimit = 0) }
        assertFailsWith<IllegalArgumentException> { group.copy(renewalPeriodSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { group.copy(quotaUsageFraction = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { group.copy(quotaUsageFraction = 0.00001) }
        assertFailsWith<IllegalArgumentException> { group.copy(quotaUsageFraction = 1.01) }
        assertFailsWith<IllegalArgumentException> { group.copy(key = " key") }
        assertFailsWith<IllegalArgumentException> { group.copy(key = "bad\nkey") }
    }

    @Test fun negativeFeedbackIsDebtNotInvalidData() {
        assertEquals(-100.0, AiTpmFeedback(-100.0).remaining)
        assertFailsWith<IllegalArgumentException> { AiTpmFeedback(Double.NEGATIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { AiTpmFeedback(retryAfterMillis = -1) }
        assertFailsWith<IllegalArgumentException> { AiQuotaValueSource.Header("invalid\nheader") }
        assertFailsWith<IllegalArgumentException> { AiQuotaValueSource.Body("$.remaining") }
    }
}
