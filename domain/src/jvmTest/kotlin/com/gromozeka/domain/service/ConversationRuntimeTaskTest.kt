package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ConversationRuntimeTaskTest {
    private val conversationId = Conversation.Id("conversation-1")
    private val agent = AgentDefinition(
        id = AgentDefinition.Id("agent-1"),
        name = "Test Agent",
        prompts = listOf(Prompt.Id("prompt-1")),
        runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model-1")),
        type = AgentDefinition.Type.Inline,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

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
            payload = ConversationRuntimeTask.Payload.UserTurn(message, agent),
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
                agent = agent,
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
