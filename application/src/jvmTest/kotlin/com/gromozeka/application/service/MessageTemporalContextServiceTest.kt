package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageTemporalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive

class MessageTemporalContextServiceTest {
    private val service = MessageTemporalContextService()

    @Test
    fun `adds utc time and elapsed seconds to human user messages`() {
        val first = userMessage("first", "2026-08-17T10:00:00Z")
        val second = userMessage("second", "2026-08-17T10:02:03Z")

        val enriched = service.enrich(listOf(first, assistantMessage(), second), enabled = true)

        val firstContext = enriched[0].temporalContext()
        val secondContext = enriched[2].temporalContext()
        assertEquals(first.createdAt, firstContext.sentAt)
        assertNull(firstContext.elapsedSincePreviousUserMessageSeconds)
        assertEquals(123, secondContext.elapsedSincePreviousUserMessageSeconds)
        assertEquals(
            "<message_time sent_at=\"2026-08-17T10:02:03Z\" timezone=\"UTC\" " +
                "elapsed_since_previous_user_message_seconds=\"123\" />",
            secondContext.toXml(),
        )
    }

    @Test
    fun `ignores synthetic squashed and agent-authored user messages`() {
        val first = userMessage("first", "2026-08-17T10:00:00Z")
        val synthetic = userMessage("synthetic", "2026-08-17T10:01:00Z").copy(
            providerMetadata = kotlinx.serialization.json.buildJsonObject { put("synthetic", JsonPrimitive(true)) },
        )
        val fromAgent = userMessage("agent", "2026-08-17T10:02:00Z").copy(
            instructions = listOf(Conversation.Message.Instruction.Source.Agent("tab-1")),
        )
        val squashed = userMessage("squashed", "2026-08-17T10:03:00Z").copy(
            squashOperationId = Conversation.SquashOperation.Id("squash-1"),
        )
        val second = userMessage("second", "2026-08-17T10:04:00Z")

        val enriched = service.enrich(
            listOf(first, synthetic, fromAgent, squashed, second),
            enabled = true,
        )

        assertNull(enriched[1].temporalContextOrNull())
        assertNull(enriched[2].temporalContextOrNull())
        assertNull(enriched[3].temporalContextOrNull())
        assertEquals(240, enriched[4].temporalContext().elapsedSincePreviousUserMessageSeconds)
    }

    @Test
    fun `does not mutate messages when temporal context is disabled`() {
        val messages = listOf(userMessage("first", "2026-08-17T10:00:00Z"))

        val enriched = service.enrich(messages, enabled = false)

        assertSame(messages, enriched)
    }

    @Test
    fun `replaces an existing runtime temporal context instead of duplicating it`() {
        val messages = listOf(userMessage("first", "2026-08-17T10:00:00Z"))

        val enrichedTwice = service.enrich(service.enrich(messages, enabled = true), enabled = true)

        assertEquals(
            1,
            enrichedTwice.single().instructions
                .filterIsInstance<Conversation.Message.Instruction.MessageTemporalRuntimeContext>()
                .size,
        )
    }

    private fun userMessage(id: String, createdAt: String) = Conversation.Message(
        id = Conversation.Message.Id(id),
        conversationId = CONVERSATION_ID,
        role = Conversation.Message.Role.USER,
        content = listOf(Conversation.Message.ContentItem.UserMessage(id)),
        createdAt = Instant.parse(createdAt),
    )

    private fun assistantMessage() = Conversation.Message(
        id = Conversation.Message.Id("assistant"),
        conversationId = CONVERSATION_ID,
        role = Conversation.Message.Role.ASSISTANT,
        content = listOf(
            Conversation.Message.ContentItem.AssistantMessage(
                Conversation.Message.StructuredText("response")
            )
        ),
        createdAt = Instant.parse("2026-08-17T10:01:00Z"),
    )

    private fun Conversation.Message.temporalContext(): MessageTemporalContext =
        requireNotNull(temporalContextOrNull())

    private fun Conversation.Message.temporalContextOrNull(): MessageTemporalContext? =
        instructions
            .filterIsInstance<Conversation.Message.Instruction.MessageTemporalRuntimeContext>()
            .singleOrNull()
            ?.context

    private companion object {
        val CONVERSATION_ID = Conversation.Id("conversation-1")
    }
}
