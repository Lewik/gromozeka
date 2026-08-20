package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Clock

class MessageEditingTest {
    @Test
    fun `editing user text preserves attachments and instructions`() {
        val image = Conversation.Message.ContentItem.ImageItem(
            Conversation.Message.ImageSource.Base64ImageSource(
                data = "aW1hZ2U=",
                mediaType = "image/png",
            )
        )
        val instruction = Conversation.Message.Instruction.UserInstruction(
            id = "instruction",
            title = "Instruction",
            description = "Description",
        )
        val message = message(
            role = Conversation.Message.Role.USER,
            content = listOf(
                Conversation.Message.ContentItem.UserMessage("before"),
                image,
            ),
            instructions = listOf(instruction),
        )

        val edited = message.withEditedText("after")

        assertEquals("before", message.editableText())
        assertEquals("after", assertIs<Conversation.Message.ContentItem.UserMessage>(edited[0]).text)
        assertEquals(image, edited[1])
        assertEquals(listOf(instruction), message.instructions)
    }

    @Test
    fun `editing assistant text preserves tool calls and clears stale generated text metadata`() {
        val toolCall = Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id("tool-call"),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = "grz_read_file",
                input = JsonObject(mapOf("path" to JsonPrimitive("README.md"))),
            ),
        )
        val message = message(
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                toolCall,
                Conversation.Message.ContentItem.AssistantMessage(
                    Conversation.Message.StructuredText(
                        fullText = "before",
                        ttsText = "spoken before",
                        voiceTone = "warm",
                        attentionRequested = true,
                        suggestedReplies = listOf("Continue"),
                        failedToParse = true,
                    )
                ),
            ),
        )

        val edited = message.withEditedText("after")
        val assistant = assertIs<Conversation.Message.ContentItem.AssistantMessage>(edited[1])

        assertEquals(toolCall, edited[0])
        assertEquals("after", assistant.structured.fullText)
        assertEquals(null, assistant.structured.ttsText)
        assertEquals(null, assistant.structured.voiceTone)
        assertEquals(emptyList(), assistant.structured.suggestedReplies)
        assertEquals(false, assistant.structured.failedToParse)
        assertEquals(true, assistant.structured.attentionRequested)
    }

    @Test
    fun `system message is not editable`() {
        val message = message(
            role = Conversation.Message.Role.SYSTEM,
            content = listOf(
                Conversation.Message.ContentItem.System(
                    level = Conversation.Message.ContentItem.System.SystemLevel.INFO,
                    content = "status",
                )
            ),
        )

        assertEquals(null, message.editableText())
        assertFailsWith<IllegalArgumentException> {
            message.withEditedText("changed")
        }
    }

    private fun message(
        role: Conversation.Message.Role,
        content: List<Conversation.Message.ContentItem>,
        instructions: List<Conversation.Message.Instruction> = emptyList(),
    ): Conversation.Message = Conversation.Message(
        id = Conversation.Message.Id("message"),
        conversationId = Conversation.Id("conversation"),
        role = role,
        content = content,
        instructions = instructions,
        createdAt = Clock.System.now(),
    )
}
