package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.AI_PROVIDER_MANAGED_TOOL_METADATA_KEY
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ProviderManagedToolSequenceTest {
    @Test
    fun completedProviderCallsCanInterleaveWithApplicationToolsWithoutRepair() {
        val messages = listOf(call("web", true), call("file", false), result("file", false), result("web", true))
        val fixed = fixer().buildFixedMessageSequence(messages, conversationId)
        assertEquals(messages, fixed.messages)
        assertEquals(0, fixed.addedResults)
        assertEquals(0, fixed.convertedResults)
        assertEquals(0, fixed.mergedToolCallMessages)
    }

    @Test
    fun interruptedProviderCallGetsOneErrorResultAndRemainsStableOnLaterTurns() {
        val messages = listOf(call("web", true), message("user", Conversation.Message.Role.USER, ContentItem.UserMessage("Continue")))
        val fixed = fixer().buildFixedMessageSequence(messages, conversationId)
        assertEquals(1, fixed.addedResults)
        assertEquals(true, (fixed.messages[1].content.single() as ContentItem.ToolResult).isError)
        val repeated = fixer().buildFixedMessageSequence(fixed.messages, conversationId)
        assertEquals(fixed.messages, repeated.messages)
        assertEquals(0, repeated.addedResults)
        assertEquals(0, repeated.convertedResults)
    }

    private fun fixer() = ToolCallSequenceFixerService(unused(), unused(), unused(), unused(), unused())
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
        error("Sequence calculation must not access persistence")
    } as T

    private fun call(id: String, managed: Boolean) = message(id, Conversation.Message.Role.ASSISTANT,
        ContentItem.ToolCall(ContentItem.ToolCall.Id(id), ContentItem.ToolCall.Data(id, JsonObject(emptyMap()))), managed)
    private fun result(id: String, managed: Boolean) = message("$id-result",
        if (managed) Conversation.Message.Role.ASSISTANT else Conversation.Message.Role.USER,
        ContentItem.ToolResult(ContentItem.ToolCall.Id(id), id, listOf(ContentItem.ToolResult.Data.Text("Result"))), managed)
    private fun message(id: String, role: Conversation.Message.Role, item: ContentItem, managed: Boolean = false) = Conversation.Message(
        id = Conversation.Message.Id(id), conversationId = conversationId, role = role, content = listOf(item), createdAt = Clock.System.now(),
        providerMetadata = JsonObject(if (managed) mapOf(AI_PROVIDER_MANAGED_TOOL_METADATA_KEY to JsonPrimitive(true)) else emptyMap()),
    )
    private val conversationId = Conversation.Id("conversation")
}
