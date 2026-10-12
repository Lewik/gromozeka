package com.gromozeka.domain.model

import kotlin.test.*

class ConversationPinTest {
    @Test fun textLimitsUseKotlinLengthWithoutTrimming() {
        for (text in listOf("", " ", "\t\r\n", "\u00a0")) assertFailsWith<IllegalArgumentException> { ConversationPinLimits.validateText(text) }
        val text = "  שלום\nКириллица 👩🏽‍💻 100%\r\n"
        ConversationPinLimits.validateText(text)
        ConversationPinLimits.validateText("😀".repeat(16_000))
        assertFailsWith<ConversationPinLimitException> { ConversationPinLimits.validateText("😀".repeat(16_000) + "a") }
        assertFailsWith<ConversationPinLimitException> { ConversationPinLimits.validateText("x".repeat(32_001)) }
    }

    @Test fun loneHighSurrogateIsRejectedWithoutContent() {
        val error = assertFailsWith<IllegalArgumentException> { ConversationPinLimits.validateText("PRIVATE_TEXT_\uD800") }
        assertFalse(error.toString().contains("PRIVATE_TEXT")); assertNull(error.cause)
    }

    @Test fun loneLowSurrogateIsRejectedWithoutContent() {
        val error = assertFailsWith<IllegalArgumentException> { ConversationPinLimits.validateText("PRIVATE_TEXT_\uDC00") }
        assertFalse(error.toString().contains("PRIVATE_TEXT")); assertNull(error.cause)
    }

    @Test fun malformedCreationKeyIsRejectedWithoutContent() {
        for (value in listOf("PRIVATE_KEY_\uD800", "PRIVATE_KEY_\uDC00", "\uD800x", "\uDC00\uD800")) {
            val error = assertFailsWith<IllegalArgumentException> { ConversationPinLimits.validateCreationKey(value) }
            assertFalse(error.toString().contains("PRIVATE_KEY")); assertNull(error.cause)
        }
    }

    @Test fun validSurrogatePairsAndUnicodeRemainLiteralCodeUnits() {
        for (value in listOf("\uD800\uDC00", "\uDBFF\uDFFF", "😀", "👩🏽‍💻", "א\u0301\r\n  ")) {
            ConversationPinLimits.validateText(value)
            ConversationPinLimits.validateCreationKey(value)
        }
        assertEquals(2, "\uD800\uDC00".length)
        ConversationPinLimits.validateText("\uD800\uDC00".repeat(16_000))
        assertFailsWith<ConversationPinLimitException> { ConversationPinLimits.validateText("\uD800\uDC00".repeat(16_000) + "x") }
    }

    @Test fun creationKeysAreInternalBoundedMetadata() {
        ConversationPinLimits.validateCreationKey(null)
        ConversationPinLimits.validateCreationKey("k".repeat(512))
        assertFailsWith<IllegalArgumentException> { ConversationPinLimits.validateCreationKey(" ") }
        assertFailsWith<IllegalArgumentException> { ConversationPinLimits.validateCreationKey("k".repeat(513)) }
    }
}
