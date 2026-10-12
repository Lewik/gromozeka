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

    @Test fun creationKeysAreInternalBoundedMetadata() {
        ConversationPinLimits.validateCreationKey(null)
        ConversationPinLimits.validateCreationKey("k".repeat(512))
        assertFailsWith<IllegalArgumentException> { ConversationPinLimits.validateCreationKey(" ") }
        assertFailsWith<IllegalArgumentException> { ConversationPinLimits.validateCreationKey("k".repeat(513)) }
    }
}
