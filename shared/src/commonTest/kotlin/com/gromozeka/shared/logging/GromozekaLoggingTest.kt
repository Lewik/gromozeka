package com.gromozeka.shared.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GromozekaLoggingTest {
    @Test
    fun filtersMessagesBelowConfiguredLevel() {
        val events = mutableListOf<GromozekaLogEvent>()
        GromozekaLogging.configure(GromozekaLogSink(events::add), GromozekaLogLevel.WARN)
        val log = GromozekaLogging.logger("test")

        try {
            log.info("ignored")
            log.warn("included")

            assertEquals(listOf("included"), events.map { it.message })
        } finally {
            GromozekaLogging.configure(GromozekaLogSink { }, GromozekaLogLevel.INFO)
        }
    }

    @Test
    fun redactsCommonCredentialShapes() {
        val redacted = redactSensitiveLogData(
            "Bearer abc.def token=secret-value password:guess sk-proj-abcdefghijklmnop " +
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature"
        )

        assertFalse(redacted.contains("abc.def"))
        assertFalse(redacted.contains("secret-value"))
        assertFalse(redacted.contains("guess"))
        assertFalse(redacted.contains("abcdefghijklmnop"))
        assertFalse(redacted.contains("eyJhbGci"))
        assertTrue(redacted.contains("[REDACTED]"))
    }
}
