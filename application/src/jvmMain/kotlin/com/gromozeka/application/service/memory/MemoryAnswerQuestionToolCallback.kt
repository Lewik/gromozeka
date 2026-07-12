package com.gromozeka.application.service.memory

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component

@Component
class MemoryAnswerQuestionToolCallback(
    private val memoryOperations: MemoryAsyncOperationApplicationService,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Input(
        val target: String = "previous_user_message",
        val target_message_id: String? = null,
        val question: String? = null,
        val mode: String? = null,
        val namespace: String? = null,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_ANSWER_QUESTION_TOOL_NAME,
        description = "Queue a direct question answered from persisted memory only and return a run_id immediately. Use memory_run_status with that run_id to retrieve completion and the final answer. Use this when the user asks what Gromozeka remembers or asks a factual question whose answer should come from memory. For normal chat where the assistant should reason over returned context itself, use memory_enrich_context instead.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "target": {
                  "type": "string",
                  "description": "Question selection mode. Supports 'previous_user_message', 'message_id', and 'provided_question'."
                },
                "target_message_id": {
                  "type": "string",
                  "description": "Optional explicit message id in the current thread to answer from memory."
                },
                "question": {
                  "type": "string",
                  "description": "Standalone direct memory question when target='provided_question'."
                },
                "mode": {
                  "type": "string",
                  "description": "Optional caller mode label for debugging or analytics."
                },
                "namespace": {
                  "type": "string",
                  "description": "Optional readable memory namespace to read from. Examples: global, user:lewik, work:hebrew, project:<project-id>. Omit to use the configured default or current project namespace."
                }
              }
            }
        """.trimIndent()
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = parseInput(toolInput)
        val providedQuestion = input.question?.trim().orEmpty()
        if (input.target == "provided_question" || providedQuestion.isNotBlank()) {
            if (providedQuestion.isBlank()) {
                return@runBlocking MemoryToolResultRenderer.failureJsonString(
                    "target='provided_question' requires non-blank question."
                )
            }
            return@runBlocking memoryOperations.answerProvidedQuestion(
                conversationIdValue = context?.getString("conversationId"),
                questionText = providedQuestion,
                mode = input.mode,
                namespaceValue = input.namespace,
            )
        }

        val conversationId = context?.getString("conversationId")
            ?: return@runBlocking MemoryToolResultRenderer.failureJsonString(
                "conversationId not found in ToolExecutionContext. target='${input.target}' can only run inside a conversation turn. Use target='provided_question' with question for standalone answering."
            )

        if (input.target !in setOf("previous_user_message", "message_id")) {
            return@runBlocking MemoryToolResultRenderer.failureJsonString("Unsupported memory_answer_question target: ${input.target}")
        }

        memoryOperations.answerMessage(
            conversationIdValue = conversationId,
            targetMessageId = input.target_message_id,
            namespaceValue = input.namespace,
        )
    }

    private fun parseInput(toolInput: String): Input {
        if (toolInput.isBlank() || toolInput == "{}") {
            return Input()
        }
        return json.decodeFromString(toolInput)
    }
}
