package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.remote.protocol.ConversationRuntimeStatePayload
import com.gromozeka.remote.protocol.ConversationRuntimeStateQuery
import com.gromozeka.remote.protocol.GromozekaServerEnvelope
import com.gromozeka.remote.protocol.RemoteProtocolCodec
import com.gromozeka.remote.protocol.RemoteStateSyncCursor
import com.gromozeka.remote.protocol.StateSyncSnapshotResponse
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class ConversationRuntimeSnapshotProjectionTest {
    private val conversationId = Conversation.Id("conversation")
    private val now = Instant.parse("2026-11-24T00:00:00Z")

    @Test
    fun runtimeBodiesAreRemovedFromEveryMessageBearingRemoteTaskSlot() {
        val runtimeJson = JsonPrimitive("x".repeat(5 * 1024 * 1024))
        val tasks = (1..4).map { index ->
            task(
                id = "runtime-$index",
                message = message("runtime-message-$index", Conversation.Message.Role.RUNTIME, runtimeJson),
                agentInvocation = index == 3,
            )
        }
        val canonical = ConversationRuntimeSnapshot(
            revision = 1,
            conversationId = conversationId,
            state = null,
            activeTask = tasks[0],
            activeInsertions = listOf(tasks[1], tasks[2]),
            pendingTasks = listOf(tasks[3]),
        )

        assertTrue(encode(canonical).size > DEFAULT_WEBSOCKET_MESSAGE_BYTES)

        val projected = canonical.projectForRemoteClient()
        assertTrue(encode(projected).size < 64 * 1024)
        projected.tasks().zip(tasks).forEach { (projectedTask, canonicalTask) ->
            assertEquals(canonicalTask.id, projectedTask.id)
            assertEquals(canonicalTask.placement, projectedTask.placement)
            assertEquals(canonicalTask.requirements, projectedTask.requirements)
            assertEquals(
                JsonNull,
                (projectedTask.userMessage().content.single() as Conversation.Message.ContentItem.RuntimeJson).json,
            )
            assertSame(runtimeJson, canonicalTask.userMessage().content.single().let {
                (it as Conversation.Message.ContentItem.RuntimeJson).json
            })
        }
    }

    @Test
    fun ordinaryUserMessagesRemainCanonical() {
        val message = message("user-message", Conversation.Message.Role.USER, JsonPrimitive("ordinary text"))
        val task = task("user-task", message)
        val projected = ConversationRuntimeSnapshot(
            revision = 1,
            conversationId = conversationId,
            state = null,
            pendingTasks = listOf(task),
        ).projectForRemoteClient()

        assertSame(task, projected.pendingTasks.single())
        assertSame(message, projected.pendingTasks.single().userMessage())
    }

    private fun message(
        id: String,
        role: Conversation.Message.Role,
        content: JsonPrimitive,
    ): Conversation.Message = Conversation.Message(
        id = Conversation.Message.Id(id),
        conversationId = conversationId,
        role = role,
        author = when (role) {
            Conversation.Message.Role.USER -> Conversation.Message.Author.User(
                com.gromozeka.domain.model.User.Id("user"),
                "User",
            )
            Conversation.Message.Role.RUNTIME -> Conversation.Message.Author.Runtime()
            Conversation.Message.Role.ASSISTANT,
            Conversation.Message.Role.SYSTEM,
            -> null
        },
        content = when (role) {
            Conversation.Message.Role.RUNTIME -> listOf(Conversation.Message.ContentItem.RuntimeJson(content))
            Conversation.Message.Role.USER -> listOf(Conversation.Message.ContentItem.UserMessage(content.content))
            Conversation.Message.Role.ASSISTANT,
            Conversation.Message.Role.SYSTEM,
            -> error("Unsupported test role: $role")
        },
        createdAt = now,
    )

    private fun task(
        id: String,
        message: Conversation.Message,
        agentInvocation: Boolean = false,
    ): ConversationRuntimeTask = ConversationRuntimeTask(
        id = ConversationRuntimeTask.Id(id),
        conversationId = conversationId,
        payload = if (agentInvocation) {
            ConversationRuntimeTask.Payload.AgentInvocation(message, AgentDefinition.Id("agent"))
        } else {
            ConversationRuntimeTask.Payload.PostMessage(message)
        },
        placement = QueuedMessagePlacement.END_OF_TURN,
        idempotencyKey = id,
        requirements = ConversationRuntimeTaskRequirements(
            capabilities = ConversationRuntimeCapability.entries.toSet(),
            target = ConversationRuntimeTaskTarget.Server,
        ),
        createdAt = now,
    )

    private fun ConversationRuntimeTask.userMessage(): Conversation.Message = requireNotNull(userMessageOrNull())

    private fun ConversationRuntimeSnapshot.tasks(): List<ConversationRuntimeTask> =
        listOfNotNull(activeTask) + activeInsertions + pendingTasks

    private fun encode(snapshot: ConversationRuntimeSnapshot): ByteArray = RemoteProtocolCodec.encodeServerBinary(
        GromozekaServerEnvelope(
            id = "snapshot",
            payload = StateSyncSnapshotResponse(
                query = ConversationRuntimeStateQuery(conversationId),
                cursor = RemoteStateSyncCursor("server", 1, snapshot.revision),
                state = ConversationRuntimeStatePayload(snapshot),
            ),
        ),
    )
}
