package com.gromozeka.application.service.memory

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeMemorySourceExcerptTest {
    @Test
    fun keepsCompleteSmallSourceWhenFullTextThresholdAllowsIt() {
        val source = buildString {
            appendLine("1. Dialogue")
            appendLine("2. Monologue")
            appendLine("3. Sound effects")
            append("extra context ".repeat(120))
        }

        val excerpt = RuntimeMemorySourceExcerpt.queryFocused(
            text = source,
            query = "What was the 3rd item?",
            maxChars = 200,
            fullTextMaxChars = 2_000,
        )

        assertTrue(excerpt.contains("1. Dialogue"), excerpt)
        assertTrue(excerpt.contains("2. Monologue"), excerpt)
        assertTrue(excerpt.contains("3. Sound effects"), excerpt)
        assertFalse(excerpt.contains("[matching excerpts]"), excerpt)
        assertFalse(excerpt.contains("[truncated"), excerpt)
    }

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
    fun keepsRareShortTermMatchForBedtimeQuestions() {
        val targetSentence = "user: I didn't get to bed until 2 AM last Wednesday, which made Thursday morning a struggle."
        val source = """
            Session date: 2023/05/25 (Thu) 13:47
            $targetSentence
            assistant: ${"Healthy breakfast and meal prep advice. ".repeat(120)}
            user: ${"I need more healthy dinner recipes and snack ideas. ".repeat(80)}
            assistant: ${"Mason jar salads, dried fruits, and bulk protein prep can help. ".repeat(120)}
        """.trimIndent()

        val excerpt = RuntimeMemorySourceExcerpt.queryFocused(
            text = source,
            query = "What time did I go to bed on the day before the appointment?",
            maxChars = 1_200,
        )

        assertTrue(excerpt.contains(targetSentence), excerpt)
        assertTrue(excerpt.contains("2 AM"), excerpt)
    }

    @Test
    fun keepsBedtimeAnswerWhenFallbackQueryContainsCompetingDateTerms() {
        val targetSentence =
            "user: I'm feeling a bit sluggish today and I think it's because I didn't get to bed until 2 AM last Wednesday, which made Thursday morning a struggle."
        val source = """
            LongMemEval past chat session.
            Session date: 2023/05/25 (Thu) 13:47
            Transcript:
            $targetSentence
            assistant: ${"Healthy breakfast and meal prep advice can help with energy throughout the day. ".repeat(100)}
            user: ${"I need quick lunches, snacks, healthy dinner recipes, and hydration options. ".repeat(80)}
            assistant: ${"Mason Jar Salads, dried fruits, trail mix, meal prep labels, and coconut water. ".repeat(100)}
        """.trimIndent()

        val excerpt = RuntimeMemorySourceExcerpt.queryFocused(
            text = source,
            query = """
                doctor's appointment went to bed day before time
                LongMemEval recall target.
                Current date: 2023/05/27 (Sat) 18:41
                Question: What time did I go to bed on the day before I had a doctor's appointment?
                doctor's appointment date and went to bed time day before appointment
            """.trimIndent(),
            maxChars = 4_000,
        )

        assertTrue(excerpt.contains(targetSentence), excerpt)
        assertTrue(excerpt.contains("2 AM"), excerpt)
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
