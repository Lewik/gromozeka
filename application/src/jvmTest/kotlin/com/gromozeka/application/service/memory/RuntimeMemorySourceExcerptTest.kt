package com.gromozeka.application.service.memory

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeMemorySourceExcerptTest {
    @Test
    fun prefersDenseQueryWindowOverFirstWeakTermOccurrence() {
        val earlyAdvice = "Retailer coupons can be useful. ".repeat(120)
        val targetSentence = "I redeemed a $5 coupon on coffee creamer at Target last Sunday."
        val source = earlyAdvice + "\n" + targetSentence + "\n" + "Generic shopping chatter. ".repeat(120)

        val excerpt = RuntimeMemorySourceExcerpt.queryFocused(
            text = source,
            query = "Where did I redeem a $5 coupon on coffee creamer?",
            maxChars = 1_000,
        )

        assertTrue(excerpt.contains(targetSentence))
        assertFalse(excerpt.startsWith("Retailer coupons can be useful. Retailer coupons can be useful."))
    }
}
