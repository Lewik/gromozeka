package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** The next request, not cumulative billed usage. Estimates never claim tokenizer accuracy. */
@Serializable
data class AiContextInspection(
    val inputTokens: Int,
    val precision: Precision,
    val nativeCompactionAvailable: Boolean = false,
) {
    init { require(inputTokens >= 0) { "Context size cannot be negative" } }

    @Serializable
    enum class Precision { PROVIDER_COUNT, REFERENCE_MODEL_ESTIMATE, USAGE_CALIBRATED_ESTIMATE, ESTIMATE }
}

/** Input and output must use the same projection and scale; change id when either changes. */
interface AiRequestTokenEstimator {
    val id: String
    fun input(request: AiRuntimeRequest): Int
    fun output(messages: List<AiAssistantMessage>): Int
}

/** Portable estimate when a route has no preflight counting API. UTF-8/4 includes non-ASCII input. */
object AiRequestTokenEstimate : AiRequestTokenEstimator {
    override val id: String = "portable-visible-context-v3"
    /** Conservative modality cost when the portable request has no model-specific image detail. */
    const val IMAGE_INPUT_TOKENS: Int = 10_000
    /** Coarse fallback for provider file parsing; transport base64 is not model-visible text. */
    const val FILE_INPUT_TOKENS: Int = 50_000
    private val json = Json { encodeDefaults = false }

    override fun input(request: AiRuntimeRequest): Int = checked(
        request.systemPrompts.sumOf { text(it).toLong() } +
            request.tools.sumOf { text(it.definition.name + it.definition.description + it.definition.inputSchema).toLong() + 8 } +
            request.messages.sumOf { message(it).toLong() } +
            when (val format = request.options.responseFormat) {
                is AiResponseFormat.JsonSchema -> text(format.schema.toString()).toLong()
                AiResponseFormat.Text -> 0L
            } + 8
    )

    fun message(message: Conversation.Message): Int = checked(
        message.content.sumOf { contentTokens(it).toLong() } + text(json.encodeToString(message.instructions)) + 4
    )

    override fun output(messages: List<AiAssistantMessage>): Int = checked(messages.sumOf {
        it.content.sumOf { item -> contentTokens(item).toLong() } + text("[]") + 4
    })

    private fun contentTokens(item: ContentItem): Int = when (item) {
        is ContentItem.Thinking -> if (item.kind == ContentItem.Thinking.Kind.THINKING)
            text(item.thinking) else 0
        is ContentItem.ContextCompactionResult -> when (val payload = item.payload) {
            is ContentItem.ContextCompactionResult.Payload.ReadableSummary -> text(payload.text)
            is ContentItem.ContextCompactionResult.Payload.OpaqueProviderState -> checked(opaqueTokens(payload.state))
        }
        is ContentItem.ImageItem -> imageTokens(item)
        is ContentItem.DocumentItem -> documentTokens(item)
        is ContentItem.ArtifactItem -> checked(
            serializedTokens(item).toLong() + item.artifact.kind.modalityTokens()
        )
        is ContentItem.ToolResult -> toolResultTokens(item)
        else -> text(json.encodeToString(ContentItem.serializer(), item))
    }

    private fun imageTokens(item: ContentItem.ImageItem): Int {
        val source = when (val source = item.source) {
            is Conversation.Message.ImageSource.Base64ImageSource -> source.copy(data = "")
            is Conversation.Message.ImageSource.UrlImageSource -> source.copy(url = "")
            is Conversation.Message.ImageSource.FileImageSource -> source.copy(fileId = "")
        }
        return checked(serializedTokens(item.copy(source = source)).toLong() + IMAGE_INPUT_TOKENS)
    }

    private fun documentTokens(item: ContentItem.DocumentItem): Int {
        val source = when (val source = item.source) {
            is Conversation.Message.DocumentSource.Base64DocumentSource -> source.copy(data = "")
        }
        return checked(serializedTokens(item.copy(source = source)).toLong() + FILE_INPUT_TOKENS)
    }

    private fun toolResultTokens(item: ContentItem.ToolResult): Int {
        var modalityTokens = 0L
        val result = item.result.map { data ->
            when (data) {
                is ContentItem.ToolResult.Data.Base64Data -> {
                    modalityTokens += if (data.mediaType.type == "image") IMAGE_INPUT_TOKENS else FILE_INPUT_TOKENS
                    data.copy(data = "")
                }
                is ContentItem.ToolResult.Data.UrlData -> if (data.mediaType?.type == "image") {
                    modalityTokens += IMAGE_INPUT_TOKENS
                    data.copy(url = "")
                } else {
                    data
                }
                is ContentItem.ToolResult.Data.ArtifactData -> data.also {
                    modalityTokens += data.artifact.kind.modalityTokens()
                }
                is ContentItem.ToolResult.Data.Text -> data
            }
        }
        return checked(serializedTokens(item.copy(result = result)).toLong() + modalityTokens)
    }

    private fun Artifact.Kind.modalityTokens(): Int = when (this) {
        Artifact.Kind.IMAGE -> IMAGE_INPUT_TOKENS
        Artifact.Kind.DOCUMENT -> FILE_INPUT_TOKENS
        Artifact.Kind.FILE -> 0
    }

    private fun serializedTokens(item: ContentItem): Int =
        text(json.encodeToString(ContentItem.serializer(), item))

    private fun opaqueTokens(value: JsonElement): Long = when (value) {
        is JsonObject -> value.entries.sumOf { (key, child) ->
            if (key == "encrypted_content" && child is JsonPrimitive && child.isString) {
                // Same decoded-payload approximation used by the subscription adapter; not base64 text tokens.
                ((child.content.length.toLong() * 3 / 4 - 650).coerceAtLeast(0) + 3) / 4
            } else if (key in setOf("id", "type", "call_id", "signature")) 0L else opaqueTokens(child)
        }
        is JsonArray -> value.sumOf(::opaqueTokens)
        is JsonPrimitive -> text(value.content).toLong()
    }

    fun text(text: String): Int = ((text.encodeToByteArray().size.toLong() + 3) / 4).toInt()

    private fun checked(tokens: Long): Int {
        require(tokens in 0..Int.MAX_VALUE.toLong()) { "Request is too large to count" }
        return tokens.toInt()
    }
}

/** Adapter support, not a claim that the configured endpoint is reachable. */
val AiConnection.Kind.hasContextCountApi: Boolean
    get() = this == AiConnection.Kind.OPENAI_API || this == AiConnection.Kind.ANTHROPIC_API
