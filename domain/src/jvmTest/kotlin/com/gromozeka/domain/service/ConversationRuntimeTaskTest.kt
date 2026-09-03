package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import kotlin.time.Clock
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ConversationRuntimeTaskTest {
    private val conversationId = Conversation.Id("conversation-1")
    private val agentDefinitionId = AgentDefinition.Id("agent-1")

    @Test
    fun `task rejects requirements that cannot execute its payload`() {
        assertFailsWith<IllegalArgumentException> {
            agentInvocationTask(
                requirements = ConversationRuntimeTaskRequirements(
                    capabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN),
                    target = ConversationRuntimeTaskTarget.Server,
                ),
            )
        }
    }

    @Test
    fun `plain message post only requires serialized conversation access`() {
        val message = Conversation.Message(
            id = Conversation.Message.Id("message-post"),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Test")),
            createdAt = Clock.System.now(),
        )

        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id(message.id.value),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.PostMessage(message),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "test:${message.id.value}",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN),
                target = ConversationRuntimeTaskTarget.Server,
            ),
            createdAt = Clock.System.now(),
        )
    }

    @Test
    fun `agent invocation rejects a message from another conversation`() {
        assertFailsWith<IllegalArgumentException> {
            agentInvocationTask(
                messageConversationId = Conversation.Id("conversation-2"),
                requirements = agentInvocationRequirements(),
            )
        }
    }

    @Test
    fun `local agent tool requirements need tool execution`() {
        assertFailsWith<IllegalArgumentException> {
            ConversationRuntimeTaskRequirements(
                capabilities = setOf(ConversationRuntimeCapability.LOCAL_AGENT_TOOL),
                target = ConversationRuntimeTaskTarget.Worker(
                    workerId = ConversationRuntimeWorkerId("worker-1"),
                ),
            )
        }
    }

    @Test
    fun `tool orchestration task must remain Server owned`() {
        assertFailsWith<IllegalArgumentException> {
            toolExecutionTask(
                requirements = ConversationRuntimeTaskRequirements(
                    capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
                    target = ConversationRuntimeTaskTarget.Worker(
                        workerId = ConversationRuntimeWorkerId("worker-1"),
                    ),
                ),
            )
        }
    }

    private fun agentInvocationTask(
        messageConversationId: Conversation.Id = conversationId,
        requirements: ConversationRuntimeTaskRequirements,
    ): ConversationRuntimeTask {
        val message = Conversation.Message(
            id = Conversation.Message.Id("message-1"),
            conversationId = messageConversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Test")),
            createdAt = Clock.System.now(),
        )
        return ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id(message.id.value),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.AgentInvocation(message, agentDefinitionId),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "test:${message.id.value}",
            requirements = requirements,
            createdAt = Clock.System.now(),
        )
    }

    private fun agentInvocationRequirements(): ConversationRuntimeTaskRequirements =
        ConversationRuntimeTaskRequirements(
            capabilities = setOf(
                ConversationRuntimeCapability.CONVERSATION_TURN,
                ConversationRuntimeCapability.MEMORY_PIPELINE,
            ),
            target = ConversationRuntimeTaskTarget.Server,
        )

    private fun toolExecutionTask(
        requirements: ConversationRuntimeTaskRequirements,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("tool-task-1"),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.ToolExecution(
                rootUserMessageId = Conversation.Message.Id("message-1"),
                agentDefinitionId = agentDefinitionId,
                iteration = 1,
                toolCalls = listOf(
                    Conversation.Message.ContentItem.ToolCall(
                        id = Conversation.Message.ContentItem.ToolCall.Id("tool-call-1"),
                        call = Conversation.Message.ContentItem.ToolCall.Data(
                            name = "grz_read_file",
                            input = JsonObject(emptyMap()),
                        ),
                    )
                ),
                returnDirect = false,
                executionTarget = requirements.target,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "test:tool-task-1",
            requirements = requirements,
            createdAt = Clock.System.now(),
        )
}
