package com.gromozeka.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserNotificationTest {
    @Test fun publicationValidatesContentAndUtf8Budget() {
        assertEquals(16_384, UserNotificationDraft("Title", "🙂".repeat(4_096)).message.encodeToByteArray().size)
        assertFailsWith<IllegalArgumentException> { UserNotificationDraft("Title", "🙂".repeat(4_097)) }
        assertFailsWith<IllegalArgumentException> { UserNotificationDraft("Title\nother", "body") }
        assertFailsWith<IllegalArgumentException> { UserNotificationDraft(" ", "body") }
        assertFailsWith<IllegalArgumentException> { UserNotificationDraft("x".repeat(201), "body") }
        assertFailsWith<IllegalArgumentException> { UserNotificationDraft("Title", " ") }
        assertFailsWith<IllegalArgumentException> { UserNotificationDraft("Title", "body", sourceKey = " ") }
        assertFailsWith<IllegalArgumentException> { UserNotificationDraft("Title", "body", sourceKey = "x".repeat(257)) }
    }
}
