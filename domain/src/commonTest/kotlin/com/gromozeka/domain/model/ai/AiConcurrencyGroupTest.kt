package com.gromozeka.domain.model.ai

import kotlinx.serialization.json.Json
import kotlin.test.*

class AiConcurrencyGroupTest {
    @Test
    fun `new connections explicitly select default and the initial group has capacity four`() {
        val connection = Json.decodeFromString<AiConnection>("""{"connectionKind":"openai_api","id":"test","displayName":"Test"}""")
        assertEquals(AiConcurrencyGroup.DEFAULT_ID, connection.concurrencyGroupId)
        assertEquals(4, AiConcurrencyGroup.DEFAULT.maxConcurrentCalls)
    }

    @Test
    fun `concurrency limits round trip and unlimited is explicit`() {
        for (limit in listOf(null, 1, 7, 64)) {
            val group = AiConcurrencyGroup(AiConcurrencyGroup.Id("shared"), "Shared", limit)
            assertEquals(group, Json.decodeFromString<AiConcurrencyGroup>(Json.encodeToString(group)))
        }
        for (limit in listOf(-1, 0, 65)) assertFailsWith<IllegalArgumentException> {
            AiConcurrencyGroup(AiConcurrencyGroup.DEFAULT_ID, "Invalid", limit)
        }
        assertFailsWith<IllegalArgumentException> { AiConcurrencyGroup(AiConcurrencyGroup.DEFAULT_ID, " ") }
        for (id in listOf("", "has space", "line\nbreak", "/path")) assertFailsWith<IllegalArgumentException> {
            AiConcurrencyGroup.Id(id)
        }
    }
}
