package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*
import kotlin.time.Instant

class MessagePinningTest {
    private val literal = "  שלום\r\nКириллица 👩🏽‍💻 100%\t "

    @Test fun userSnapshotKeepsExactWhitespaceAndUnicode() {
        val message = message(listOf(ContentItem.UserMessage(literal)))
        assertEquals(literal, message.pinTextSnapshot())
        assertEquals(literal, (message.content.single() as ContentItem.UserMessage).text)
    }

    @Test fun assistantSnapshotDoesNotIncludeThinkingToolsMetadataOrSpeech() {
        val readable = ContentItem.AssistantMessage(Conversation.Message.StructuredText(
            fullText = literal, ttsText = "PRIVATE_SPEECH", voiceTone = "PRIVATE_TONE",
            suggestedReplies = listOf("PRIVATE_SUGGESTION"),
        ))
        val message = message(listOf(
            ContentItem.Thinking("PRIVATE_THINKING", "PRIVATE_SIGNATURE"),
            ContentItem.ToolCall(ContentItem.ToolCall.Id("tool"), ContentItem.ToolCall.Data(
                "private_tool", JsonObject(mapOf("text" to JsonPrimitive("PRIVATE_INPUT"))),
            )),
            readable,
            ContentItem.System(ContentItem.System.SystemLevel.INFO, "PRIVATE_SYSTEM"),
        )).copy(
            providerMetadata = JsonObject(mapOf("nativePayload" to JsonPrimitive("PRIVATE_NATIVE_PAYLOAD"))),
            instructions = listOf(Conversation.Message.Instruction.UserInstruction("instruction", "PRIVATE_TITLE", "PRIVATE_INSTRUCTION")),
        )
        assertEquals(literal, message.pinTextSnapshot())
        assertFalse(message.pinTextSnapshot().orEmpty().contains("PRIVATE_"))
        assertEquals(readable, message.content[2])
    }

    @Test fun onlySupportedReadableTextCanStartAMessagePin() {
        assertNull(message(emptyList()).pinTextSnapshot())
        assertNull(message(listOf(ContentItem.Thinking("PRIVATE_THINKING"))).pinTextSnapshot())
        assertNull(message(listOf(ContentItem.UserMessage(" \r\n\t"))).pinTextSnapshot())
    }

    @Test fun multipleTextBlocksKeepTheirContentAndOrderWithoutTrimming() {
        val message = message(listOf(
            ContentItem.UserMessage("  first"), ContentItem.Thinking("PRIVATE_THINKING"),
            ContentItem.UserMessage(" \t"), ContentItem.AssistantMessage(Conversation.Message.StructuredText(fullText = "\nlast  ")),
        ))
        assertEquals("  first\n\n \t\n\n\nlast  ", message.pinTextSnapshot())
    }

    private fun message(content: List<ContentItem>) = Conversation.Message(
        id = Conversation.Message.Id("message"), conversationId = Conversation.Id("conversation"),
        role = Conversation.Message.Role.ASSISTANT, content = content, createdAt = Instant.fromEpochMilliseconds(123),
    )
}
