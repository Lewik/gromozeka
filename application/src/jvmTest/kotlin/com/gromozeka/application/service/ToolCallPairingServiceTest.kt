package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiStepOutcome

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class ToolCallPairingServiceTest {
    @Test
    fun `provider managed calls stay separate from application calls during persistence`() {
        val providerCall = Conversation.Message.ContentItem.ToolCall(
            Conversation.Message.ContentItem.ToolCall.Id("web"),
            Conversation.Message.ContentItem.ToolCall.Data("web.run", JsonObject(emptyMap())),
        )
        val ordinaryCall = providerCall.copy(id = Conversation.Message.ContentItem.ToolCall.Id("ordinary"),
            call = providerCall.call.copy(name = "read_file"))
        val response = AiRuntimeResponse(listOf(
            AiAssistantMessage(listOf(providerCall), mapOf(com.gromozeka.domain.model.ai.AI_PROVIDER_MANAGED_TOOL_METADATA_KEY to true)),
            AiAssistantMessage(listOf(ordinaryCall)),
        ), outcome = AiStepOutcome.TOOL_CALLS)
        val prepared = AiConversationMessageMapper.prepareResponse(response)
        assertEquals(listOf(ordinaryCall), prepared.toolCalls)
        val messages = AiConversationMessageMapper.toConversationMessages(Conversation.Id("conversation"), prepared,
            Conversation.Message.Author.Agent(com.gromozeka.domain.model.AgentDefinition.Id("agent"), "Agent"))
        assertEquals(2, messages.size)
        assertEquals(listOf(providerCall), messages.first().content)
        assertEquals(listOf(ordinaryCall), messages.last().content)
        val continuation = response.copy(messages = response.messages.take(1), outcome = AiStepOutcome.CONTINUE)
        assertEquals(continuation, AiConversationMessageMapper.prepareResponse(continuation))
    }

    @Test
    fun `incomplete response preserves remarks without executable or replayable tool calls`() {
        val remark = Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText("Working."))
        val call = Conversation.Message.ContentItem.ToolCall(
            Conversation.Message.ContentItem.ToolCall.Id("call"),
            Conversation.Message.ContentItem.ToolCall.Data("check", JsonObject(emptyMap())),
        )
        val response = AiRuntimeResponse(
            messages = listOf(AiAssistantMessage(
                listOf(remark, call), metadata = mapOf("opaqueReplay" to "unexecuted call"),
            )),
            providerMetadata = mapOf("opaqueReplay" to "unexecuted call"),
            outcome = AiStepOutcome.INCOMPLETE,
        )
        val prepared = AiConversationMessageMapper.prepareResponse(response)
        assertEquals(emptyList(), prepared.toolCalls)
        assertEquals(listOf(remark), prepared.messages.single().content)
        assertEquals(emptyMap(), prepared.messages.single().metadata)
        assertEquals(emptyMap(), prepared.providerMetadata)
        assertEquals(response.copy(outcome = AiStepOutcome.TOOL_CALLS),
            AiConversationMessageMapper.prepareResponse(response.copy(outcome = AiStepOutcome.TOOL_CALLS)))
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AiConversationMessageMapper.prepareResponse(response.copy(outcome = AiStepOutcome.COMPLETE))
        }
    }
    @Test
    fun `selection of visible tool call includes hidden tool result message`() {
        val toolCallId = Conversation.Message.ContentItem.ToolCall.Id("tool-1")
        val toolCallMessage = message(
            id = "call",
            role = Conversation.Message.Role.ASSISTANT,
            content = Conversation.Message.ContentItem.ToolCall(
                id = toolCallId,
                call = Conversation.Message.ContentItem.ToolCall.Data(
                    name = "read_file",
                    input = JsonObject(emptyMap()),
                ),
            ),
        )
        val toolResultMessage = message(
            id = "result",
            role = Conversation.Message.Role.USER,
            content = Conversation.Message.ContentItem.ToolResult(
                toolUseId = toolCallId,
                toolName = "read_file",
                result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("raw output")),
            ),
        )

        val selected = ToolCallPairingService().includePairedToolMessages(
            listOf(toolCallMessage, toolResultMessage),
            listOf(toolCallMessage.id),
        )

        assertEquals(setOf(toolCallMessage.id, toolResultMessage.id), selected)
    }

    private fun message(
        id: String,
        role: Conversation.Message.Role,
        content: Conversation.Message.ContentItem,
    ): Conversation.Message = Conversation.Message(
        id = Conversation.Message.Id(id),
        conversationId = Conversation.Id("conversation"),
        role = role,
        content = listOf(content),
        createdAt = Clock.System.now(),
    )
}
