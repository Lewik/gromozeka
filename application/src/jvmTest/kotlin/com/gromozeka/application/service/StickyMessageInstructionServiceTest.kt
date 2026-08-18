package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageInstructionGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Instant

class StickyMessageInstructionServiceTest {
    private val service = StickyMessageInstructionService()

    @Test
    fun `keeps ordinary instruction history unchanged`() {
        val messages = listOf(message("first", readonly), message("second", writable))

        val materialized = service.materialize(messages, listOf(accessGroup()))

        assertSame(messages, materialized)
    }

    @Test
    fun `keeps only the latest instruction from a sticky group`() {
        val unrelated = instruction("tone", "Tone")
        val messages = listOf(
            message("first", readonly, unrelated),
            assistantMessage(),
            message("second", writable),
        )

        val materialized = service.materialize(
            messages,
            listOf(accessGroup(MessageInstructionGroup.RetentionMode.STICKY_LATEST)),
        )

        assertEquals(listOf(unrelated), materialized[0].instructions)
        assertEquals(listOf(writable), materialized[2].instructions)
        assertEquals(messages[1], materialized[1])
        assertEquals(listOf(readonly, unrelated), messages[0].instructions)
    }

    @Test
    fun `materializes independent sticky groups on their latest messages`() {
        val concise = instruction("concise", "Concise")
        val messages = listOf(
            message("first", readonly, concise),
            message("second", writable),
        )
        val toneGroup = MessageInstructionGroup(
            id = "tone",
            title = "Tone",
            controls = listOf(control(concise, "C")),
            retentionMode = MessageInstructionGroup.RetentionMode.STICKY_LATEST,
        )

        val materialized = service.materialize(
            messages,
            listOf(
                accessGroup(MessageInstructionGroup.RetentionMode.STICKY_LATEST),
                toneGroup,
            ),
        )

        assertEquals(listOf(concise), materialized[0].instructions)
        assertEquals(listOf(writable), materialized[1].instructions)
    }

    @Test
    fun `restores full history when sticky mode is disabled`() {
        val messages = listOf(message("first", readonly), message("second", writable))
        service.materialize(
            messages,
            listOf(accessGroup(MessageInstructionGroup.RetentionMode.STICKY_LATEST)),
        )

        val restored = service.materialize(messages, listOf(accessGroup()))

        assertSame(messages, restored)
        assertEquals(listOf(readonly), restored[0].instructions)
        assertEquals(listOf(writable), restored[1].instructions)
    }

    private fun accessGroup(
        retentionMode: MessageInstructionGroup.RetentionMode = MessageInstructionGroup.RetentionMode.KEEP_HISTORY,
    ) = MessageInstructionGroup(
        id = "access",
        title = "Access",
        controls = listOf(control(readonly, "R"), control(writable, "W")),
        retentionMode = retentionMode,
    )

    private fun control(
        instruction: Conversation.Message.Instruction.UserInstruction,
        label: String,
    ) = MessageInstructionGroup.Control(
        data = instruction,
        shortLabel = label,
    )

    private fun instruction(
        id: String,
        title: String,
    ) = Conversation.Message.Instruction.UserInstruction(
        id = id,
        title = title,
        description = title,
    )

    private fun message(
        id: String,
        vararg instructions: Conversation.Message.Instruction,
    ) = Conversation.Message(
        id = Conversation.Message.Id(id),
        conversationId = conversationId,
        role = Conversation.Message.Role.USER,
        content = listOf(Conversation.Message.ContentItem.UserMessage(id)),
        instructions = instructions.toList(),
        createdAt = Instant.parse("2026-08-18T00:00:00Z"),
    )

    private fun assistantMessage() = Conversation.Message(
        id = Conversation.Message.Id("assistant"),
        conversationId = conversationId,
        role = Conversation.Message.Role.ASSISTANT,
        content = listOf(
            Conversation.Message.ContentItem.AssistantMessage(
                Conversation.Message.StructuredText("response")
            )
        ),
        createdAt = Instant.parse("2026-08-18T00:00:01Z"),
    )

    private val conversationId = Conversation.Id("conversation-1")
    private val readonly = instruction("readonly", "Readonly")
    private val writable = instruction("writable", "Writable")
}
