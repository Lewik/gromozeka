package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `agent sourced submission is not attributed to authenticated user`() {
        val attributed = message(
            author = Conversation.Message.Author.User(
                userId = User.Id("other-user"),
                displayName = "Other User",
            ),
            instructions = listOf(Conversation.Message.Instruction.Source.Agent("sender-tab")),
        ).attributeAuthenticatedSubmission(authenticatedUser)

        assertNull(attributed.author)
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
        username = "ada",
        displayName = "Ada Lovelace",
        status = User.Status.ACTIVE,
        createdAt = Instant.parse("2026-09-03T00:00:00Z"),
        updatedAt = Instant.parse("2026-09-03T00:00:00Z"),
    )
}
