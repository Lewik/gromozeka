package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ConversationRuntimeTaskTest {
    private val conversationId = Conversation.Id("conversation-1")
    private val agentDefinitionId = AgentDefinition.Id("agent-1")

    @Test
    fun `task rejects requirements that cannot execute its payload`() {
        assertFailsWith<IllegalArgumentException> {
            userTurnTask(
                requirements = ConversationRuntimeTaskRequirements(
                    capabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
                ),
            )
        }
    }

    @Test
    fun `user turn task rejects a message from another conversation`() {
        assertFailsWith<IllegalArgumentException> {
            userTurnTask(
                messageConversationId = Conversation.Id("conversation-2"),
                requirements = userTurnRequirements(),
            )
        }
    }

    @Test
    fun `local agent tool requirements need tool execution and tool tasks need exact target`() {
        assertFailsWith<IllegalArgumentException> {
            ConversationRuntimeTaskRequirements(
                capabilities = setOf(ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            toolExecutionTask(
                requirements = ConversationRuntimeTaskRequirements(
                    capabilities = setOf(
                        ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                        ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
                    ),
                ),
            )
        }
    }

    private fun userTurnTask(
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
            payload = ConversationRuntimeTask.Payload.UserTurn(message, agentDefinitionId),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "test:${message.id.value}",
            requirements = requirements,
            createdAt = Clock.System.now(),
        )
    }

    private fun userTurnRequirements(): ConversationRuntimeTaskRequirements =
        ConversationRuntimeTaskRequirements(
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
            ),
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
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "test:tool-task-1",
            requirements = requirements,
            createdAt = Clock.System.now(),
        )
}
