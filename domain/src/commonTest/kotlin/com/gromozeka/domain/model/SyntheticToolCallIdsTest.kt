package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.projectedMessages
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class SyntheticToolCallIdsTest {
    @Test
    fun hashUsesTheFullStableKeyAndNineBase62Characters() {
        assertEquals("WX5ArDHS7", syntheticToolCallId("cmd_example_status").value)
        assertEquals("ctdKgKMeb", syntheticToolCallId("monitor_example_portion1_status").value)
        assertEquals("PXpTMISAj", syntheticToolCallId("пример").value)
        assertFailsWith<IllegalArgumentException> { syntheticToolCallId(" ") }
        val ids = (0 until 10_000).map { syntheticToolCallId("monitor:event-$it:terminal").value }
        assertTrue(ids.all { Regex("[A-Za-z0-9]{9}").matches(it) })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun oldPairsAreNormalizedOnlyInTheProviderViewAndRemainStableOnExtension() {
        val history = pair("legacy", "cmd_example_status")
        val saved = Json.encodeToString(history)
        val projected = request(history).projectedMessages()
        assertEquals(syntheticToolCallId("cmd_example_status"), callId(projected))
        assertEquals(callId(projected), resultId(projected))
        assertEquals(projected, request(projected).projectedMessages(), "Double projection must be idempotent")
        val extended = request(history + pair("next", "monitor_example_portion1_status")).projectedMessages()
        assertEquals(projected, extended.take(projected.size), "Unrelated calls must not change the existing prefix")
        assertEquals(history.map { it.id }, projected.map { it.id })
        assertEquals(saved, Json.encodeToString(history), "Persisted message JSON must stay unchanged")
    }

    @Test
    fun legacyNormalizationSurvivesBranchAndSelectiveReplayInvalidation() {
        val pair = pair("legacy", "cmd_example_status")
        val summary = pair.first().copy(id = Message.Id("summary"), providerMetadata = JsonObject(emptyMap()),
            content = listOf(ContentItem.ContextCompactionResult(
                payload = ContentItem.ContextCompactionResult.Payload.ReadableSummary("summary"),
                origin = ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                coverage = ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES,
                invalidatedReplayMessageIds = listOf(pair.first().id),
            )))
        for (history in listOf(pair, pair + summary)) {
            val request = AiRuntimeRequest(emptyList(), history, options = AiRuntimeOptions(toolContext = mapOf("threadId" to "branch")))
            val projected = request.projectedMessages()
            assertEquals(syntheticToolCallId("cmd_example_status"), callId(projected))
            assertEquals(callId(projected), resultId(projected))
            assertEquals(projected, request.copy(messages = projected).projectedMessages())
            assertEquals("cmd_example_status", callId(history).value)
        }
    }

    @Test
    fun newPairsAndProviderGeneratedCallsKeepTheirIds() {
        val short = syntheticToolCallId("cmd_example_status").value
        val generated = pair("new", short)
        val native = pair("native", "call_provider_generated_long_id", kind = null)
        val result = request(generated + native).projectedMessages()
        assertEquals(generated + native, result)
        val routingError = pair("routing", "call_real_model_id", kind = "tool_routing_error")
        assertEquals(routingError, request(routingError).projectedMessages())
    }

    @Test
    fun collisionsWithUnmodifiedModelIdsFailInsteadOfMergingPairs() {
        val old = pair("legacy", "cmd_example_status")
        val native = pair("native", syntheticToolCallId("cmd_example_status").value, kind = null)
        assertFailsWith<IllegalStateException> { request(old + native).projectedMessages() }
        val short = syntheticToolCallId("new-key").value
        assertFailsWith<IllegalStateException> {
            request(pair("first", short) + pair("second", short)).projectedMessages()
        }
        assertFailsWith<IllegalStateException> {
            request(pair("synthetic", short) + pair("native", short, kind = null)).projectedMessages()
        }
    }

    @Test
    fun monitorAndCommandLegacyIdsUseTheSameContract() {
        for (kind in listOf("command_task_completion", "command_monitor_update", "memory")) {
            val input = pair(kind, "long-legacy-id:with:punctuation", kind)
            val projected = request(input).projectedMessages()
            assertTrue(Regex("[A-Za-z0-9]{9}").matches(callId(projected).value))
            assertEquals(callId(projected), resultId(projected))
        }
    }

    private fun request(messages: List<Message>) = AiRuntimeRequest(emptyList(), messages)
    private fun callId(messages: List<Message>) = (messages[0].content.single() as ContentItem.ToolCall).id
    private fun resultId(messages: List<Message>) = (messages[1].content.single() as ContentItem.ToolResult).toolUseId

    private fun pair(prefix: String, id: String, kind: String? = "command_task_completion"): List<Message> {
        val callId = ContentItem.ToolCall.Id(id)
        val metadata = if (kind == null) JsonObject(emptyMap()) else buildJsonObject {
            put("synthetic", true); put("syntheticKind", kind)
        }
        val call = Message(Message.Id("$prefix:call"), Conversation.Id("conversation"), role = Message.Role.ASSISTANT,
            content = listOf(ContentItem.ToolCall(callId, ContentItem.ToolCall.Data("tool", JsonObject(emptyMap())))),
            providerMetadata = metadata, createdAt = Instant.fromEpochMilliseconds(0))
        return listOf(call, call.copy(id = Message.Id("$prefix:result"), role = Message.Role.USER,
            content = listOf(ContentItem.ToolResult(callId, "tool", listOf(ContentItem.ToolResult.Data.Text("done"))))))
    }
}
