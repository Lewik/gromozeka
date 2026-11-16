package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageTemporalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.AgentDefinition

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
    fun `ignores runtime and peer messages`() {
        val first = userMessage("first", "2026-08-17T10:00:00Z")
        val synthetic = userMessage("synthetic", "2026-08-17T10:01:00Z").copy(
            role = Conversation.Message.Role.RUNTIME_NOTIFICATION, author = Conversation.Message.Author.Runtime(),
        )
        val fromAgent = userMessage("agent", "2026-08-17T10:02:00Z").copy(
            role = Conversation.Message.Role.PEER, author = Conversation.Message.Author.Agent(AgentDefinition.Id("peer"), "Peer"),
        )
        val second = userMessage("second", "2026-08-17T10:04:00Z")

        val enriched = service.enrich(
            listOf(first, synthetic, fromAgent, second),
            enabled = true,
        )

        assertNull(enriched[1].temporalContextOrNull())
        assertNull(enriched[2].temporalContextOrNull())
        assertEquals(240, enriched[3].temporalContext().elapsedSincePreviousUserMessageSeconds)
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
        role = Conversation.Message.Role.USER, author = Conversation.Message.Author.User(User.Id("user"), "User"),
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
