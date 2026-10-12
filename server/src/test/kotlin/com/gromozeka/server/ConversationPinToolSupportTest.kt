package com.gromozeka.server

import com.gromozeka.domain.model.*
import com.gromozeka.server.ConversationPinToolSupport.Operation
import kotlinx.serialization.json.*
import java.util.Base64
import kotlin.test.*
import kotlin.time.Instant

class ConversationPinToolSupportTest {
    private val target = Conversation.Id("synthetic-target")
    private val now = Instant.fromEpochMilliseconds(1)
    private fun pin(id: String, ordinal: Long, text: String = "literal pin") = ConversationPin(
        ConversationPin.Id(id), target, text, ordinal, 1, now, now,
    )
    private fun json(vararg entries: Pair<String, JsonElement>) = JsonObject(mapOf(*entries)).toString()
    private fun rejected(operation: Operation, raw: String) {
        val error = assertFailsWith<IllegalArgumentException> { ConversationPinToolSupport.parse(operation, raw) }
        assertEquals("Invalid conversation pin arguments (PIN_INVALID_ARGUMENT).", error.message)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        assertFalse(error.stackTraceToString().contains("PRIVATE_INPUT_SENTINEL"))
    }

    @Test fun `five schemas keep model input narrow and do not expose authority provenance or dedup keys`() {
        assertEquals(setOf("grz_conversation_pin_list", "grz_conversation_pin_get", "grz_conversation_pin_create",
            "grz_conversation_pin_update", "grz_conversation_pin_delete"), Operation.entries.map { it.toolName }.toSet())
        for (operation in Operation.entries) {
            val schema = Json.parseToJsonElement(ConversationPinToolSupport.schema(operation)).jsonObject
            assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean)
            assertEquals(operation.keys, schema.getValue("properties").jsonObject.keys)
            assertEquals(operation.required, schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertTrue(operation.keys.intersect(setOf("actor", "thread", "agent", "project", "source_ref", "creationKey", "creation_key")).isEmpty())
            assertEquals("string", schema.getValue("properties").jsonObject.getValue("conversation_id").jsonObject.getValue("type").jsonPrimitive.content)
        }
        assertFalse("pin_id" in Operation.CREATE.keys)
    }

    @Test fun `only missing conversation selector means current`() {
        assertNull(ConversationPinToolSupport.parse(Operation.LIST, "{}").conversationId)
        for (value in listOf(JsonNull, JsonPrimitive(""), JsonPrimitive(" "), JsonPrimitive("x y"), JsonPrimitive(1), JsonPrimitive(false), JsonArray(emptyList()))) {
            rejected(Operation.LIST, json("conversation_id" to value))
        }
        assertEquals("a".repeat(128), ConversationPinToolSupport.parse(Operation.LIST, json("conversation_id" to JsonPrimitive("a".repeat(128)))).conversationId)
        rejected(Operation.LIST, json("conversation_id" to JsonPrimitive("a".repeat(129))))
    }

    @Test fun `required fields foreign authority and invalid JSON fail without exposing input`() {
        for (raw in listOf("null", "[]", "{PRIVATE_INPUT_SENTINEL", "{\"unexpected\":\"PRIVATE_INPUT_SENTINEL\"}")) rejected(Operation.LIST, raw)
        for (operation in Operation.entries.filter { it != Operation.LIST }) rejected(operation, "{}")
        for (name in listOf("pin_id", "creationKey", "creation_key", "actor", "thread", "agent", "project", "source_ref", "message_id", "execution_target")) {
            rejected(Operation.CREATE, json("text" to JsonPrimitive("literal"), name to JsonPrimitive("PRIVATE_INPUT_SENTINEL")))
        }
    }

    @Test fun `text limits use Kotlin length and retain whitespace verbatim`() {
        val literal = " \n\t A\uD83D\uDE80 \t\n "
        assertEquals(literal, ConversationPinToolSupport.parse(Operation.CREATE, json("text" to JsonPrimitive(literal))).text)
        for (text in listOf("x".repeat(ConversationPinLimits.MAX_TEXT_LENGTH), "\uD83D\uDE80".repeat(ConversationPinLimits.MAX_TEXT_LENGTH / 2))) {
            assertEquals(text, ConversationPinToolSupport.parse(Operation.CREATE, json("text" to JsonPrimitive(text))).text)
        }
        for (text in listOf("", " \t\n", "\u2003", "x".repeat(ConversationPinLimits.MAX_TEXT_LENGTH + 1), "\uD83D\uDE80".repeat(ConversationPinLimits.MAX_TEXT_LENGTH / 2 + 1))) {
            rejected(Operation.CREATE, json("text" to JsonPrimitive(text)))
        }
        rejected(Operation.CREATE, json("text" to JsonNull))
    }

    @Test fun `revision is a positive JSON long without coercion`() {
        for (value in listOf(JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(1.5), JsonPrimitive("1"), JsonNull, Json.parseToJsonElement("9223372036854775808"))) {
            rejected(Operation.DELETE, json("pin_id" to JsonPrimitive("pin-a"), "expected_revision" to value))
        }
        val arguments = ConversationPinToolSupport.parse(Operation.UPDATE, json("pin_id" to JsonPrimitive("pin-a"),
            "expected_revision" to JsonPrimitive(Long.MAX_VALUE), "text" to JsonPrimitive("updated")))
        assertEquals(Long.MAX_VALUE, arguments.revision)
    }

    @Test fun `page limits and cursor encoding are bounded and strict`() {
        assertEquals(50, ConversationPinToolSupport.parse(Operation.LIST, "{}").limit)
        for (value in listOf(JsonPrimitive(0), JsonPrimitive(101), JsonPrimitive(1.5), JsonPrimitive("50"), JsonNull)) rejected(Operation.LIST, json("limit" to value))
        for (value in listOf("", "a=".repeat(20), "A".repeat(513), "PRIVATE_INPUT_SENTINEL")) rejected(Operation.LIST, json("cursor" to JsonPrimitive(value)))
        val malformedPayloads = listOf("[]", "[2,\"synthetic-target\",1,\"pin-a\"]", "[1,\"synthetic-target\",\"1\",\"pin-a\"]", "[1,\"\",1,\"pin-a\"]")
        for (payload in malformedPayloads) rejected(Operation.LIST, json("cursor" to JsonPrimitive(Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()))))
        rejected(Operation.LIST, json("cursor" to JsonPrimitive(Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(-1)))))
        val original = pin("pin-a", Long.MAX_VALUE)
        val cursor = ConversationPinToolSupport.encodeCursor(original)
        assertTrue(cursor.length <= ConversationPinToolSupport.CURSOR_LIMIT)
        val decoded = assertNotNull(ConversationPinToolSupport.parse(Operation.LIST, json("cursor" to JsonPrimitive(cursor))).cursor)
        assertEquals(target.value, decoded.conversationId)
        assertEquals(original.ordinal, decoded.ordinal)
        assertEquals(original.id.value, decoded.pinId)
    }

    @Test fun `list is stable ordered metadata and bounded previews with target-bound cursors`() {
        val first = pin("pin-a", 1, "long".repeat(100))
        val second = pin("pin-b", 1)
        val third = pin("pin-c", 2)
        val arguments = ConversationPinToolSupport.parse(Operation.LIST, "{\"limit\":2}")
        val page = ConversationPinToolSupport.page(target, listOf(third, second, first), arguments)
        val items = page.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("pin-a", "pin-b"), items.map { it.getValue("pin_id").jsonPrimitive.content })
        assertTrue(items.none { "text" in it || "source" in it })
        assertTrue(items.all { it.getValue("preview").jsonPrimitive.content.length <= 256 })
        val cursor = page.getValue("next_cursor")
        val next = ConversationPinToolSupport.parse(Operation.LIST, json("cursor" to cursor))
        // Deletion of the cursor pin does not turn keyset pagination into an index offset or lookup.
        val tail = ConversationPinToolSupport.page(target, listOf(first, third), next)
        assertEquals("pin-c", tail.getValue("items").jsonArray.single().jsonObject.getValue("pin_id").jsonPrimitive.content)
        assertEquals(JsonNull, tail.getValue("next_cursor"))
        assertFailsWith<IllegalArgumentException> { ConversationPinToolSupport.requireCursorTarget(next.cursor, Conversation.Id("other")) }
    }

    @Test fun `foreign rows and oversized collections are not partially exposed`() {
        val foreign = pin("foreign", 1, "PRIVATE_INPUT_SENTINEL").copy(conversationId = Conversation.Id("other"))
        val error = assertFailsWith<IllegalStateException> { ConversationPinToolSupport.page(target, listOf(foreign), ConversationPinToolSupport.Arguments(null)) }
        assertEquals("Conversation pin operation failed (PIN_OPERATION_FAILED).", error.message)
        assertNull(error.cause)
        assertFailsWith<IllegalStateException> { ConversationPinToolSupport.page(target,
            (1..101).map { pin("pin-$it", it.toLong()) }, ConversationPinToolSupport.Arguments(null)) }
    }

    @Test fun `preview never splits a surrogate pair at the boundary`() {
        assertEquals("x".repeat(255), ConversationPinToolSupport.preview("x".repeat(255) + "\uD83D\uDE80"))
        assertEquals("x".repeat(254) + "\uD83D\uDE80", ConversationPinToolSupport.preview("x".repeat(254) + "\uD83D\uDE80tail"))
        assertEquals(" \n\uD83D\uDE80 ", ConversationPinToolSupport.preview(" \n\uD83D\uDE80 "))
    }

    @Test fun `creation identity is stable bounded and entirely endpoint actor call scoped`() {
        val endpoint = AgentEndpoint(Conversation.Id("source"), Conversation.Thread.Id("thread"), AgentDefinition.Id("agent"))
        val actor = User.Id("actor")
        val key = ConversationPinToolSupport.creationKey(endpoint, actor, "call")
        assertEquals(key, ConversationPinToolSupport.creationKey(endpoint, actor, "call"))
        assertEquals(76, key.length)
        assertTrue(Regex("pin-tool-v1:[0-9a-f]{64}").matches(key))
        for (changed in listOf(endpoint.copy(conversationId = Conversation.Id("other")), endpoint.copy(threadId = Conversation.Thread.Id("other")), endpoint.copy(agentId = AgentDefinition.Id("other")))) {
            assertNotEquals(key, ConversationPinToolSupport.creationKey(changed, actor, "call"))
        }
        assertNotEquals(key, ConversationPinToolSupport.creationKey(endpoint, User.Id("other"), "call"))
        assertNotEquals(key, ConversationPinToolSupport.creationKey(endpoint, actor, "other"))
        for (missing in listOf(null, "", " ")) assertFailsWith<IllegalStateException> { ConversationPinToolSupport.creationKey(endpoint, actor, missing) }
    }
}
