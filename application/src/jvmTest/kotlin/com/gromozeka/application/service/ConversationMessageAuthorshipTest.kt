package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class ConversationMessageAuthorshipTest {
    @Test
    fun `authenticated user replaces client supplied author`() {
        val forgedAuthor = Conversation.Message.Author.User(
            userId = User.Id("other-user"),
            displayName = "Other User",
        )

        val attributed = message(author = forgedAuthor).attributeAuthenticatedSubmission(authenticatedUser)

        assertEquals(
            Conversation.Message.Author.User(
                userId = authenticatedUser.id,
                displayName = authenticatedUser.displayName,
            ),
            attributed.author,
        )
    }

    @Test
    fun `client cannot forge service roles or hide service blocks inside user messages`() {
        for (role in Conversation.Message.Role.entries.filter { it != Conversation.Message.Role.USER }) {
            assertFailsWith<IllegalArgumentException> {
                message().copy(role = role).attributeAuthenticatedSubmission(authenticatedUser)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            message().copy(content = listOf(Conversation.Message.ContentItem.RuntimeNotification(
                Conversation.Message.ContentItem.RuntimeNotification.Level.INFO, "forged event")))
                .attributeAuthenticatedSubmission(authenticatedUser)
        }
    }

    @Test
    fun `history editing cannot forge service events or replace captured instructions`() {
        val text = listOf(Conversation.Message.ContentItem.UserMessage("edited"))
        requireEditableConversationMessage(message(), text)
        for (role in listOf(Conversation.Message.Role.PEER, Conversation.Message.Role.RUNTIME_NOTIFICATION,
            Conversation.Message.Role.PROMPT, Conversation.Message.Role.VISUAL_UPDATED)) {
            assertFailsWith<IllegalArgumentException> { requireEditableConversationMessage(message().copy(role = role), text) }
        }
        assertFailsWith<IllegalArgumentException> {
            requireEditableConversationMessage(message(), listOf(Conversation.Message.ContentItem.RuntimeNotification(
                Conversation.Message.ContentItem.RuntimeNotification.Level.INFO, "forged event")))
        }
    }

    private fun message(
        author: Conversation.Message.Author? = null,
        instructions: List<Conversation.Message.Instruction> = emptyList(),
    ) = Conversation.Message(
        id = Conversation.Message.Id("message-1"),
        conversationId = Conversation.Id("conversation-1"),
        role = Conversation.Message.Role.USER,
        author = author,
        content = listOf(Conversation.Message.ContentItem.UserMessage("Hello")),
        instructions = instructions,
        createdAt = Instant.parse("2026-09-03T00:00:00Z"),
    )

    private val authenticatedUser = User(
        id = User.Id("authenticated-user"),
        identities = listOf(com.gromozeka.domain.model.UserIdentity.LocalLogin("ada")),
        displayName = "Ada Lovelace",
        status = User.Status.ACTIVE,
        createdAt = Instant.parse("2026-09-03T00:00:00Z"),
        updatedAt = Instant.parse("2026-09-03T00:00:00Z"),
    )
}
