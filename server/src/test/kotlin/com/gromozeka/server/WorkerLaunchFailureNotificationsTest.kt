package com.gromozeka.server

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class WorkerLaunchFailureNotificationsTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val actor = User.Id("creator")
    private val conversation = Conversation.Id("closed-conversation")
    private val worker = ConversationRuntimeWorkerId("worker")
    private val json = Json { encodeDefaults = true }
    private val call = ContentItem.ToolCall(ContentItem.ToolCall.Id("call"),
        ContentItem.ToolCall.Data("grz_execute_command", buildJsonObject { put("command", "private-command") }),
        state = Conversation.Message.BlockState.COMPLETE)
    private fun record(dispatched: Boolean = true) = StoredWorkerRequest(
        "request", worker, WorkerGatewayCodec.encode(WorkerGatewayMessage.Request("request", WorkerGatewayOperation.TOOL_EXECUTION,
            json.encodeToString(WorkerToolExecutionRequest(ConversationRuntimeTaskTarget.Worker(worker, WorkspaceMount.Id("mount")),
                listOf(call), mapOf(TOOL_CONTEXT_CONVERSATION_ID to conversation.value))).encodeToByteArray())),
        now, now, actorUserId = actor, dispatchedAt = now.takeIf { dispatched },
    )
    private fun response(error: Boolean) = WorkerGatewayMessage.Response("request", WorkerGatewayMessage.Response.Status.SUCCEEDED,
        json.encodeToString(WorkerToolExecutionResponse(listOf(ContentItem.ToolResult(call.id, call.call.name,
            listOf(ContentItem.ToolResult.Data.Text("private-output: process status FAILED, exit 1")), error)), false)).encodeToByteArray())

    @Test
    fun `nested tool error creates one owner notification but a shell exit failure does not`() = runBlocking {
        val publisher = CapturingPublisher()
        val observer = WorkerLaunchFailureNotifications(publisher)
        observer.completed(record(), response(false))
        assertTrue(publisher.items.isEmpty())
        observer.completed(record(), response(true))
        observer.completed(record(), response(true))
        val notification = publisher.items.values.single()
        assertEquals(actor, notification.userId)
        assertEquals(conversation, notification.conversationId)
        assertFalse(notification.message.contains("private-output"))
        assertFalse(notification.message.contains("private-command"))
        assertTrue(notification.message.contains("partial effects"))
    }

    @Test
    fun `RPC timeout never claims a dispatched long task did not start`() = runBlocking {
        val publisher = CapturingPublisher()
        val observer = WorkerLaunchFailureNotifications(publisher)
        observer.completed(record(), WorkerGatewayMessage.Response("request", WorkerGatewayMessage.Response.Status.FAILED,
            errorCode = "OUTCOME_UNKNOWN", errorMessage = "private-exception"))
        val message = publisher.items.values.single().message
        assertTrue(message.contains("outcome is unknown"))
        assertTrue(message.contains("may have started"))
        assertFalse(message.contains("private-exception"))
    }

    @Test
    fun `only an undispatched failure states that this request launched nothing`() = runBlocking {
        val publisher = CapturingPublisher()
        WorkerLaunchFailureNotifications(publisher).completed(record(false),
            WorkerGatewayMessage.Response("request", WorkerGatewayMessage.Response.Status.FAILED, errorCode = "EXPIRED", errorMessage = "Delivery TTL expired"))
        assertTrue(publisher.items.values.single().message.contains("before dispatch"))
        assertTrue(publisher.items.values.single().message.contains("no command was launched by this request"))
    }

    private inner class CapturingPublisher : UserNotificationPublisher {
        val items = mutableMapOf<Pair<User.Id, String?>, UserNotification>()
        override suspend fun publish(userId: User.Id, notification: UserNotificationDraft): UserNotification {
            val key = userId to notification.sourceKey
            return items.getOrPut(key) { UserNotification(UserNotification.Id("notification"), userId,
                notification.title, notification.message, notification.level, now, conversationId = notification.conversationId) }
        }
    }
}
