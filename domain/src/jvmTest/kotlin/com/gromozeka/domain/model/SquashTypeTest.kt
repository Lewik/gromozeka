package com.gromozeka.domain.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SquashTypeTest {
    @Test fun acceptsLegacySummarizeValueAndWritesCompact() {
        assertEquals(SquashType.COMPACT, Json.decodeFromString<SquashType>("\"SUMMARIZE\""))
        assertEquals("\"COMPACT\"", Json.encodeToString(SquashType.COMPACT))
    }
}
