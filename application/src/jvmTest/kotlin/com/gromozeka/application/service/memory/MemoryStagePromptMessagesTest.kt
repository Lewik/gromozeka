package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryThreadContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class MemoryStagePromptMessagesTest {

    @Test
    fun memoryStageMessagesDropUnpairedToolItemsFromContext() {
        val pairedCallId = Conversation.Message.ContentItem.ToolCall.Id("paired-call")
        val orphanCallId = Conversation.Message.ContentItem.ToolCall.Id("orphan-call")
        val orphanResultId = Conversation.Message.ContentItem.ToolCall.Id("orphan-result")
        val conversationId = Conversation.Id("conversation")
        val target = userMessage(conversationId, "target", "Remember this fact.")

        val request = DirectStructuredMemoryWriteRequest(
            namespace = MemoryNamespace("project:demo"),
            source = source(conversationId, target.id),
            threadContext = MemoryThreadContext(
                conversationId = conversationId,
                threadId = Conversation.Thread.Id("thread"),
                targetMessageId = target.id,
                messages = listOf(
                    assistantToolCallMessage(conversationId, "paired-call-message", pairedCallId),
                    userToolResultMessage(conversationId, "paired-result-message", pairedCallId),
                    assistantToolCallMessage(conversationId, "orphan-call-message", orphanCallId),
                    userToolResultMessage(conversationId, "orphan-result-message", orphanResultId),
                    userMessage(conversationId, "mixed-message", "Context text.", orphanCallId),
                    target,
                ),
            ),
        )

        val stageMessages = request.toMemoryStageMessages(
            stageName = "write-router",
            taskPrompt = "Route memory.",
        )
        val stageItems = stageMessages.flatMap { it.content }
        val toolCallIds = stageItems
            .filterIsInstance<Conversation.Message.ContentItem.ToolCall>()
            .map { it.id }
        val toolResultIds = stageItems
            .filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
            .map { it.toolUseId }
        val contextTexts = stageItems
            .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
            .map { it.text }

        assertEquals(listOf(pairedCallId), toolCallIds)
        assertEquals(listOf(pairedCallId), toolResultIds)
        assertFalse(orphanCallId in toolCallIds)
        assertFalse(orphanResultId in toolResultIds)
        assertTrue("Context text." in contextTexts)
    }

    @Test
    fun sourceRenderingKeepsFullTargetSourceForMemoryStages() {
        val conversationId = Conversation.Id("conversation")
        val longText = buildString {
            appendLine("start")
            append("x".repeat(8_500))
            appendLine()
            appendLine("needle-after-eight-thousand")
            append("end")
        }

        val rendered = source(
            conversationId = conversationId,
            sourceMessageId = Conversation.Message.Id("long-source"),
            contentText = longText,
        ).renderLatestTurn()

        assertTrue("needle-after-eight-thousand" in rendered)
        assertFalse("[truncated" in rendered)
        assertTrue(rendered.endsWith(longText.trim()))
    }

    private fun source(
        conversationId: Conversation.Id,
        sourceMessageId: Conversation.Message.Id,
        contentText: String = "Remember this fact.",
    ): MemorySource =
        MemorySource.ChatTurn(
            id = MemorySource.Id("chat:${sourceMessageId.value}"),
            namespace = MemoryNamespace("project:demo"),
            conversationId = conversationId,
            threadId = Conversation.Thread.Id("thread"),
            sourceMessageId = sourceMessageId,
            speakerRole = MemorySource.ActorRole.USER,
            contentText = contentText,
            contentHash = "hash",
            observedAt = NOW,
            createdAt = NOW,
        )

    private fun userMessage(
        conversationId: Conversation.Id,
        id: String,
        text: String,
        toolCallId: Conversation.Message.ContentItem.ToolCall.Id? = null,
    ): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id(id),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = buildList {
                add(Conversation.Message.ContentItem.UserMessage(text))
                toolCallId?.let { add(toolCall(it)) }
            },
            createdAt = NOW,
        )

    private fun assistantToolCallMessage(
        conversationId: Conversation.Id,
        id: String,
        toolCallId: Conversation.Message.ContentItem.ToolCall.Id,
    ): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id(id),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(toolCall(toolCallId)),
            createdAt = NOW,
        )

    private fun userToolResultMessage(
        conversationId: Conversation.Id,
        id: String,
        toolCallId: Conversation.Message.ContentItem.ToolCall.Id,
    ): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id(id),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(
                Conversation.Message.ContentItem.ToolResult(
                    toolUseId = toolCallId,
                    toolName = "demo_tool",
                    result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("ok")),
                )
            ),
            createdAt = NOW,
        )

    private fun toolCall(id: Conversation.Message.ContentItem.ToolCall.Id): Conversation.Message.ContentItem.ToolCall =
        Conversation.Message.ContentItem.ToolCall(
            id = id,
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = "demo_tool",
                input = JsonObject(mapOf("value" to JsonPrimitive("demo"))),
            ),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-12T00:00:00Z")
    }
}
