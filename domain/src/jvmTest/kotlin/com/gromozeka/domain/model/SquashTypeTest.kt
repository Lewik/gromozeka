package com.gromozeka.domain.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SquashTypeTest {
    @Test fun writesOnlyCompactWireValues() {
        assertEquals("\"COMPACT\"", Json.encodeToString(SquashType.COMPACT))
        assertFailsWith<SerializationException> { Json.decodeFromString<SquashType>("\"SUMMARIZE\"") }
        assertEquals(
            "\"COMPACT\"",
            Json.encodeToString(Conversation.Message.ContentItem.ContextCompactionResult.Strategy.COMPACT),
        )
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Conversation.Message.ContentItem.ContextCompactionResult.Strategy>("\"SUMMARIZE\"")
        }
    }
}
