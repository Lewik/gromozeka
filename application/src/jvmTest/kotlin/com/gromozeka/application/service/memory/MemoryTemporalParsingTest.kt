package com.gromozeka.application.service.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.Instant

class MemoryTemporalParsingTest {
    @Test
    fun parsesFullInstantAsIs() {
        assertEquals(
            Instant.parse("2026-05-04T10:15:30Z"),
            "2026-05-04T10:15:30Z".toMemoryInstantOrNull("Asia/Jerusalem"),
        )
    }

    @Test
    fun parsesDateOnlyAsStartOfLocalDay() {
        assertEquals(
            Instant.parse("2026-05-03T21:00:00Z"),
            "2026-05-04".toMemoryInstantOrNull("Asia/Jerusalem"),
        )
    }

    @Test
    fun ignoresInvalidExternalTemporalText() {
        assertNull("next Thursday-ish".toMemoryInstantOrNull("Asia/Jerusalem"))
    }
}
