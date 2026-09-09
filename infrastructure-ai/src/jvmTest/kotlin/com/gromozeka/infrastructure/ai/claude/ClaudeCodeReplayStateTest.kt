package com.gromozeka.infrastructure.ai.claude

import kotlinx.serialization.json.*
import kotlin.test.*

class ClaudeCodeReplayStateTest {
    @Test
    fun checkpointRetainsNativeTailAndAttachmentsAndExcludesTheNewAnswer() {
        val state = ClaudeCodeReplayState()
        state.accept(message("old", "user", "discarded history"))
        val image = Json.parseToJsonElement("""{"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGVsbG8="}}""")
        val tail = message("tail", "user", "recent question").let { frame ->
            JsonObject(frame + ("message" to buildJsonObject {
                put("role", "user")
                put("content", JsonArray(listOf(image)))
            }))
        }
        state.accept(tail)
        state.accept(message("answer", "assistant", "recent answer"))
        state.accept(boundary("tail", "answer"))
        state.accept(message("summary", "user", "summary", synthetic = true))
        state.accept(message("new-question", "user", "after policy compaction"))
        state.accept(message("new-answer", "assistant", "current response"))
        state.ensureComplete()

        val checkpoint = ClaudeCodeReplayState.readMessages(requireNotNull(state.checkpointBeforeAssistantResponse()).replayState)
        assertEquals(listOf("summary", "tail", "answer", "new-question"), checkpoint.map { it.getValue("uuid").jsonPrimitive.content })
        assertEquals(tail, checkpoint[1])
        assertFalse(requireNotNull(state.checkpointBeforeAssistantResponse()).replayState.toString().contains("discarded history"))
        assertFalse(requireNotNull(state.checkpointBeforeAssistantResponse()).replayState.toString().contains("current response"))
    }

    @Test
    fun persistedReplayStateSupportsAnotherCompactionAfterProcessRestart() {
        val first = ClaudeCodeReplayState()
        first.accept(message("one", "user", "one"))
        first.accept(message("two", "assistant", "two"))
        val restored = ClaudeCodeReplayState(first.snapshot())
        restored.accept(boundary("two"))
        restored.accept(message("summary", "user", "summarized one", synthetic = true))
        restored.ensureComplete()
        assertEquals(listOf("summary", "two"), ClaudeCodeReplayState.readMessages(restored.snapshot()).map { it.getValue("uuid").jsonPrimitive.content })
    }

    @Test
    fun ignoresInternalCliAttachmentIdsButRejectsMissingSummary() {
        val missingTail = ClaudeCodeReplayState()
        missingTail.accept(boundary("missing"))
        missingTail.accept(message("summary", "user", "summary", synthetic = true))
        assertNotNull(missingTail.checkpointBeforeAssistantResponse())
        val missingSummary = ClaudeCodeReplayState()
        missingSummary.accept(boundary())
        assertFailsWith<IllegalStateException> { missingSummary.ensureComplete() }
        assertNull(missingSummary.checkpointBeforeAssistantResponse())
    }

    @Test
    fun parserKeepsCompactionSummaryAndReplayEventsInOrder() {
        val input = message("input", "user", "prompt")
        val parser = ClaudeCodeResultStreamParser(input)
        val boundary = boundary("input")
        val summary = message("summary", "user", "summary", synthetic = true)
        parser.accept(boundary)
        parser.accept(summary)
        val result = requireNotNull(parser.accept(Json.parseToJsonElement("""{"type":"result","subtype":"success","result":"OK","session_id":"test"}""").jsonObject))
        assertEquals(listOf(input, boundary, summary), result.replayEvents)
    }

    private fun message(id: String, role: String, text: String, synthetic: Boolean = false): JsonObject = buildJsonObject {
        put("type", role)
        put("uuid", id)
        put("isSynthetic", synthetic)
        putJsonObject("message") {
            put("role", role)
            put("content", text)
        }
    }

    private fun boundary(vararg retained: String): JsonObject = buildJsonObject {
        put("type", "system")
        put("subtype", "compact_boundary")
        putJsonObject("compact_metadata") {
            put("trigger", "auto")
            putJsonObject("preserved_messages") {
                put("all_uuids", JsonArray(retained.map(::JsonPrimitive)))
            }
        }
    }
}
