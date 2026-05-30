package com.gromozeka.application.service.memory

import com.gromozeka.application.service.AiConversationMessageMapper
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock

private val memoryStructuredStageLog = KLoggers.logger("MemoryStructuredStageExecutor")

internal enum class MemoryStructuredJsonRoot {
    OBJECT,
    ARRAY,
    OBJECT_OR_ARRAY,
}

internal data class MemoryStructuredStageResult<T>(
    val value: T,
    val rawText: String,
    val jsonText: String,
    val response: AiRuntimeResponse,
)

internal suspend fun <T> AiRuntime.callMemoryStructuredStage(
    request: AiRuntimeRequest,
    stageName: String,
    logContext: String,
    jsonRoot: MemoryStructuredJsonRoot = MemoryStructuredJsonRoot.OBJECT,
    repairAttempts: Int = 1,
    parse: (String) -> T,
    validate: (T) -> Unit = {},
): MemoryStructuredStageResult<T> {
    var currentRequest = request
    var repairAttempt = 0

    while (true) {
        val response = callMemoryStageWithRetry(
            request = currentRequest,
            stageName = stageName,
            logContext = if (repairAttempt == 0) logContext else "$logContext repairAttempt=$repairAttempt",
        )
        val rawText = AiConversationMessageMapper.extractAssistantText(response)
        var jsonText = ""

        try {
            jsonText = rawText.extractStructuredJson(jsonRoot)
            val value = parse(jsonText)
            validate(value)
            return MemoryStructuredStageResult(
                value = value,
                rawText = rawText,
                jsonText = jsonText,
                response = response,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            if (repairAttempt >= repairAttempts) {
                memoryStructuredStageLog.warn(error) {
                    "Memory structured stage failed after repair: stage=$stageName repairs=$repairAttempt " +
                        "$logContext rawChars=${rawText.length} jsonChars=${jsonText.length} error=${error.message}"
                }
                throw error
            }

            repairAttempt += 1
            memoryStructuredStageLog.warn(error) {
                "Memory structured stage returned invalid output; asking model to repair: " +
                    "stage=$stageName repairAttempt=$repairAttempt $logContext " +
                    "rawChars=${rawText.length} jsonChars=${jsonText.length} error=${error.message}"
            }
            currentRequest = currentRequest.withStructuredRepairMessages(
                stageName = stageName,
                rawText = rawText,
                error = error,
                repairAttempt = repairAttempt,
            )
        }
    }
}

private fun String.extractStructuredJson(root: MemoryStructuredJsonRoot): String {
    val json = when (root) {
        MemoryStructuredJsonRoot.OBJECT -> extractJsonObject()
        MemoryStructuredJsonRoot.ARRAY -> extractJsonArray()
        MemoryStructuredJsonRoot.OBJECT_OR_ARRAY -> extractJsonObject() ?: extractJsonArray()
    }

    return json ?: throw IllegalStateException(
        "Memory stage did not return ${root.name.lowercase().replace('_', ' ')} JSON: ${take(500)}"
    )
}

private fun AiRuntimeRequest.withStructuredRepairMessages(
    stageName: String,
    rawText: String,
    error: Throwable,
    repairAttempt: Int,
): AiRuntimeRequest {
    val conversationId = messages.lastOrNull()?.conversationId ?: Conversation.Id("memory-$stageName")
    val previousAssistantMessage = Conversation.Message(
        id = Conversation.Message.Id("memory-repair-previous:$stageName:${uuid7()}"),
        conversationId = conversationId,
        role = Conversation.Message.Role.ASSISTANT,
        content = listOf(
            Conversation.Message.ContentItem.AssistantMessage(
                structured = Conversation.Message.StructuredText(rawText.limitForMemoryPrompt(REPAIR_RAW_OUTPUT_CHARS)),
            )
        ),
        createdAt = Clock.System.now(),
    )
    val repairMessage = Conversation.Message(
        id = Conversation.Message.Id("memory-repair-action-item:$stageName:${uuid7()}"),
        conversationId = conversationId,
        role = Conversation.Message.Role.USER,
        content = listOf(
            Conversation.Message.ContentItem.UserMessage(
                """
                Your previous response could not be parsed or validated as the required structured output.

                Error:
                ${error.memoryRepairMessage()}

                Return the same intended result again, fixed to match the schema and validation rules.
                Output only the required JSON. No prose, no markdown fences, no explanations, no extra fields.
                """.trimIndent()
            )
        ),
        createdAt = Clock.System.now(),
    )

    return copy(
        messages = messages + previousAssistantMessage + repairMessage,
        options = options.copy(
            toolContext = options.toolContext + mapOf(
                "memoryStageRepair" to true,
                "memoryStageRepairAttempt" to repairAttempt,
            )
        ),
    )
}

private fun Throwable.memoryRepairMessage(): String =
    generateSequence(this) { it.cause }
        .joinToString("\n") { error ->
            val type = error::class.simpleName ?: "Throwable"
            "$type: ${error.message.orEmpty().limitForMemoryPrompt(2_000)}"
        }
        .limitForMemoryPrompt(5_000)

private const val REPAIR_RAW_OUTPUT_CHARS = 16_000
