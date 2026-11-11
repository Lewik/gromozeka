package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The next request, not cumulative billed usage. Estimates never claim tokenizer accuracy. */
@Serializable
data class AiContextInspection(
    val inputTokens: Int,
    val precision: Precision,
    val nativeCompactionAvailable: Boolean = false,
) {
    init { require(inputTokens >= 0) { "Context size cannot be negative" } }

    @Serializable
    enum class Precision { PROVIDER_COUNT, USAGE_CALIBRATED_ESTIMATE, ESTIMATE }
}

/** Portable estimate when a route has no preflight counting API. UTF-8/4 includes non-ASCII input. */
object AiRequestTokenEstimate {
    private val json = Json { encodeDefaults = false }

    fun input(request: AiRuntimeRequest): Int = checked(
        request.systemPrompts.sumOf { text(it).toLong() } +
            request.tools.sumOf { text(it.definition.name + it.definition.description + it.definition.inputSchema).toLong() + 8 } +
            request.messages.sumOf { message(it).toLong() } +
            when (val format = request.options.responseFormat) {
                is AiResponseFormat.JsonSchema -> text(format.schema.toString()).toLong()
                AiResponseFormat.Text -> 0L
            } + 8
    )

    fun message(message: Conversation.Message): Int = checked(
        text(json.encodeToString(message.content)).toLong() + text(json.encodeToString(message.instructions)) + 4
    )

    fun output(messages: List<AiAssistantMessage>): Int = checked(messages.sumOf {
        text(json.encodeToString(it.content)).toLong() + text("[]") + 4
    })

    fun text(text: String): Int = ((text.encodeToByteArray().size.toLong() + 3) / 4).toInt()

    private fun checked(tokens: Long): Int {
        require(tokens in 0..Int.MAX_VALUE.toLong()) { "Request is too large to count" }
        return tokens.toInt()
    }
}
