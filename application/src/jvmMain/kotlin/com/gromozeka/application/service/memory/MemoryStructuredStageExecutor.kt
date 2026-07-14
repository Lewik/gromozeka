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
import kotlinx.serialization.json.Json

private val memoryStructuredStageLog = KLoggers.logger("MemoryStructuredStageExecutor")
private val memoryStructuredJson = Json { ignoreUnknownKeys = true }

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
    var currentRequest = request.withMemoryStagePromptCacheKey(stageName)
    var repairAttempt = 0

    while (true) {
        val response = callMemoryStageWithRetry(
            request = currentRequest,
            stageName = stageName,
            logContext = if (repairAttempt == 0) logContext else "$logContext repairAttempt=$repairAttempt",
        )
        val assistantTexts = AiConversationMessageMapper.extractAssistantTexts(response)
        val rawText = assistantTexts.joinToString("\n").trim()
        var jsonText = ""

        try {
            val extraction = assistantTexts.extractStructuredJson(jsonRoot)
            jsonText = extraction.jsonText
            if (extraction.duplicateRootsCollapsed) {
                memoryStructuredStageLog.warn {
                    "Memory structured stage collapsed duplicate JSON roots: " +
                        "stage=$stageName $logContext textBlocks=${extraction.textBlockCount} " +
                        "roots=${extraction.jsonRootCount}"
                }
            }
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
                        "$logContext textBlocks=${assistantTexts.size} rawChars=${rawText.length} " +
                        "jsonChars=${jsonText.length} error=${error.message}"
                }
                throw error
            }

            repairAttempt += 1
            memoryStructuredStageLog.warn(error) {
                "Memory structured stage returned invalid output; asking model to repair: " +
                    "stage=$stageName repairAttempt=$repairAttempt $logContext " +
                    "textBlocks=${assistantTexts.size} rawChars=${rawText.length} " +
                    "jsonChars=${jsonText.length} error=${error.message}"
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

private fun AiRuntimeRequest.withMemoryStagePromptCacheKey(stageName: String): AiRuntimeRequest =
    copy(
        options = options.copy(
            toolContext = options.toolContext + (
                "promptCacheKey" to "gromozeka:memory:$stageName"
            ),
        ),
    )

private data class StructuredJsonExtraction(
    val jsonText: String,
    val textBlockCount: Int,
    val jsonRootCount: Int,
    val duplicateRootsCollapsed: Boolean,
)

private fun List<String>.extractStructuredJson(root: MemoryStructuredJsonRoot): StructuredJsonExtraction {
    val blocks = map { it.stripWholeJsonFence() }
        .filter { it.isNotBlank() }
    val payload = blocks.joinToString("").trim()
    if (payload.isBlank()) {
        throw IllegalStateException("Memory stage did not return ${root.displayName()} JSON")
    }

    val roots = payload.extractJsonRoots(root)
    if (roots.isEmpty()) {
        throw IllegalStateException(
            "Memory stage did not return ${root.displayName()} JSON: ${payload.take(500)}"
        )
    }

    if (roots.size == 1) {
        return StructuredJsonExtraction(
            jsonText = roots.single(),
            textBlockCount = blocks.size,
            jsonRootCount = 1,
            duplicateRootsCollapsed = false,
        )
    }

    val parsedRoots = roots.map { memoryStructuredJson.parseToJsonElement(it) }
    val firstRoot = parsedRoots.first()
    if (parsedRoots.all { it == firstRoot }) {
        return StructuredJsonExtraction(
            jsonText = roots.first(),
            textBlockCount = blocks.size,
            jsonRootCount = roots.size,
            duplicateRootsCollapsed = true,
        )
    }

    throw IllegalStateException(
        "Memory stage returned ${roots.size} distinct ${root.displayName()} JSON roots"
    )
}

private fun String.stripWholeJsonFence(): String {
    val match = Regex("""\A\s*```(?:json)?\s*([\s\S]*?)\s*```\s*\z""")
        .find(this)
    return match?.groupValues?.getOrNull(1)?.trim() ?: trim()
}

private fun String.extractJsonRoots(root: MemoryStructuredJsonRoot): List<String> {
    val roots = mutableListOf<String>()
    var index = 0

    while (true) {
        index = skipWhitespace(index)
        if (index >= length) return roots

        val rootStart = this[index]
        if (!root.allows(rootStart)) {
            throw IllegalStateException(
                "Memory stage returned non-${root.displayName()} text outside JSON root: ${drop(index).take(200)}"
            )
        }

        val end = findJsonRootEnd(index)
            ?: throw IllegalStateException("Memory stage returned incomplete ${root.displayName()} JSON")
        roots += substring(index, end)
        index = end
    }
}

private fun String.findJsonRootEnd(start: Int): Int? {
    val stack = ArrayDeque<Char>()
    var inString = false
    var escaped = false
    var index = start

    while (index < length) {
        val char = this[index]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            index += 1
            continue
        }

        when (char) {
            '"' -> inString = true
            '{' -> stack.addLast('}')
            '[' -> stack.addLast(']')
            '}', ']' -> {
                val expected = stack.removeLastOrNull()
                    ?: throw IllegalStateException("Memory stage returned unmatched JSON closing '$char'")
                if (char != expected) {
                    throw IllegalStateException(
                        "Memory stage returned mismatched JSON closing '$char', expected '$expected'"
                    )
                }
                if (stack.isEmpty()) {
                    return index + 1
                }
            }
        }
        index += 1
    }

    return null
}

private fun String.skipWhitespace(start: Int): Int {
    var index = start
    while (index < length && this[index].isWhitespace()) {
        index += 1
    }
    return index
}

private fun MemoryStructuredJsonRoot.allows(char: Char): Boolean =
    when (this) {
        MemoryStructuredJsonRoot.OBJECT -> char == '{'
        MemoryStructuredJsonRoot.ARRAY -> char == '['
        MemoryStructuredJsonRoot.OBJECT_OR_ARRAY -> char == '{' || char == '['
    }

private fun MemoryStructuredJsonRoot.displayName(): String =
    name.lowercase().replace('_', ' ')

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

                Return the complete corrected structured JSON object, not a partial patch.
                Return the same intended result again, fixed to match the schema and validation rules.
                Do not use blank strings to mean unknown. If validation says a required text field must not be blank,
                fill it with a concise schema-compatible sentence that matches the status fields.
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
