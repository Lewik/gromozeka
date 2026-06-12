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

    @Test
    fun keepsAnswerNearQueryPhraseInsideLongRecommendationList() {
        val earlyContext = """
            user: Please suggest some family-friendly activities to do in Orlando.
            assistant: ${"Orlando theme parks and family activities. ".repeat(80)}
            user: Can you recommend any places to eat in Orlando that are family-friendly?
            assistant: ${"Family-friendly Orlando dining option. ".repeat(80)}
        """.trimIndent()
        val targetRecommendation =
            "1. The Sugar Factory - A sweet shop located at Icon Park that offers specialty drinks and giant milkshakes."
        val source = """
            $earlyContext
            user: Do you have any recommendations for a fun dessert spot that my family can check out after dinner?
            assistant: Absolutely! Here are some fun dessert spots:

            $targetRecommendation

            2. Wondermade - A gourmet marshmallow shop located in Sanford.
            3. Gideon's Bakehouse - A bakery located at Disney Springs.
            4. Kelly's Homemade Ice Cream - A small-batch ice cream shop located in Orlando.
            ${"Other Orlando dessert and activity chatter. ".repeat(120)}
        """.trimIndent()

        val excerpt = RuntimeMemorySourceExcerpt.queryFocused(
            text = source,
            query = "Orlando unique dessert shop giant milkshakes talked about last time",
            maxChars = 1_400,
        )

        assertTrue(excerpt.contains("The Sugar Factory"), excerpt)
        assertTrue(excerpt.contains("Icon Park"), excerpt)
        assertTrue(excerpt.contains("giant milkshakes"), excerpt)
        assertFalse(excerpt.startsWith("user: Please suggest some family-friendly activities"))
    }

    @Test
    fun doesNotExpandSingleLineMatchesBackToDocumentStart() {
        val source = "${"early unrelated text ".repeat(250)}target answer lives near the matched phrase and should survive excerpting ${"late unrelated text ".repeat(250)}"

        val excerpt = RuntimeMemorySourceExcerpt.queryFocused(
            text = source,
            query = "matched phrase target answer",
            maxChars = 1_200,
        )

        assertTrue(excerpt.contains("target answer"), excerpt)
        assertTrue(excerpt.contains("matched phrase"), excerpt)
        assertFalse(excerpt.startsWith("early unrelated text early unrelated text early unrelated text"))
    }
}
