package com.gromozeka.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock

class ConversationTest {
    @Test
    fun `conversation accepts a user without agents`() {
        val now = Clock.System.now()
        val user = Conversation.Participant.User(User.Id("user"))

        val conversation = Conversation(
            id = Conversation.Id("conversation"),
            projectId = Project.Id("project"),
            participants = setOf(user),
            currentThread = Conversation.Thread.Id("thread"),
            createdAt = now,
            updatedAt = now,
        )

        assertEquals(setOf(user), conversation.participants)
    }

    @Test
    fun `conversation requires a user participant`() {
        val now = Clock.System.now()

        assertFailsWith<IllegalArgumentException> {
            Conversation(
                id = Conversation.Id("conversation"),
                projectId = Project.Id("project"),
                participants = setOf(
                    Conversation.Participant.Agent(AgentDefinition.Id("agent")),
                ),
                currentThread = Conversation.Thread.Id("thread"),
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
