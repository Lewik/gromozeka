package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.tool.AiToolCallback
import com.openai.core.JsonValue
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseIncludable
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputText
import com.openai.models.responses.ResponseOutputMessage
import com.openai.models.responses.ResponseOutputText
import com.openai.models.responses.ResponseReasoningItem
import com.openai.models.responses.ResponseStatus
import com.openai.models.responses.ResponseTextConfig
import com.openai.models.responses.ResponseUsage
import com.openai.models.responses.ToolChoiceFunction
import com.openai.models.responses.ToolChoiceOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.springframework.stereotype.Component
import kotlin.jvm.optionals.getOrNull

@Component
class OpenAiSubscriptionMessageMapper {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun toRequestParams(
        request: AiRuntimeRequest,
        modelName: String,
        conversationKey: String,
        accountId: String?,
    ): ResponseCreateParams {
        val builder = ResponseCreateParams.builder()
            .model(modelName)
            .inputOfResponse(toResponseInputItems(request.messages))
            .parallelToolCalls(true)
            .putAdditionalBodyProperty("store", JsonValue.from(false))
            .putAdditionalHeader("OpenAI-Beta", "responses=experimental")
            .putAdditionalHeader("originator", "gromozeka")
            .putAdditionalHeader("session_id", conversationKey)
            .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
            .promptCacheKey(conversationKey)

        if (request.systemPrompts.isNotEmpty()) {
            builder.instructions(request.systemPrompts.joinToString("\n\n"))
        }

        accountId?.let {
            builder.putAdditionalHeader("ChatGPT-Account-Id", it)
        }

        request.options.maxTokens?.let { builder.maxOutputTokens(it.toLong()) }
        buildReasoning(request.options.outputConfig)?.let(builder::reasoning)
        builder.text(buildTextConfig())

        request.tools.forEach { builder.addTool(it.toResponseFunctionTool()) }

        when (val toolChoice = request.options.toolChoice) {
            AiToolChoice.Auto -> builder.toolChoice(ToolChoiceOptions.AUTO)
            AiToolChoice.RequiredAny -> builder.toolChoice(ToolChoiceOptions.REQUIRED)
            is AiToolChoice.RequiredTool -> {
                builder.toolChoice(
                    ToolChoiceFunction.builder()
                        .name(toolChoice.name)
                        .build()
                )
            }
        }

        return builder.build()
    }

    fun toRuntimeResponse(
        response: Response,
        conversationKey: String,
    ): AiRuntimeResponse {
        val messages = response.output().mapNotNull { item ->
            when {
                item.isMessage() -> item.asMessage().toAssistantMessage()
                item.isFunctionCall() -> item.asFunctionCall().toToolCallMessage()
                item.isReasoning() -> item.asReasoning().toReasoningMessage()
                else -> null
            }
        }

        return AiRuntimeResponse(
            messages = messages,
            usage = response.usage().getOrNull()?.toAiUsage(),
            finishReason = response.status().getOrNull()?.asString(),
            providerMetadata = buildMap {
                put("conversationKey", conversationKey)
                put("responseId", response.id())
            }
        )
    }

    private fun toResponseInputItems(messages: List<Conversation.Message>): List<ResponseInputItem> {
        return buildList {
            messages.forEach { message ->
                when (message.role) {
                    Conversation.Message.Role.USER -> addAll(message.toUserInputItems())
                    Conversation.Message.Role.ASSISTANT -> addAll(message.toAssistantInputItems())
                    Conversation.Message.Role.SYSTEM -> message.toSystemInputItem()?.let(::add)
                }
            }
        }
    }

    private fun Conversation.Message.toUserInputItems(): List<ResponseInputItem> {
        return buildList {
            buildUserMessageText(this@toUserInputItems)?.let { userText ->
                add(
                    ResponseInputItem.ofMessage(
                        ResponseInputItem.Message.builder()
                            .role(ResponseInputItem.Message.Role.USER)
                            .addInputTextContent(userText)
                            .build()
                    )
                )
            }

            content
                .filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
                .forEach { toolResult ->
                    add(
                        ResponseInputItem.ofFunctionCallOutput(
                            ResponseInputItem.FunctionCallOutput.builder()
                                .callId(toolResult.toolUseId.value)
                                .output(toolResult.asPlainText())
                                .build()
                        )
                    )
                }
        }
    }

    private fun Conversation.Message.toAssistantInputItems(): List<ResponseInputItem> {
        return buildList {
            content
                .filterIsInstance<Conversation.Message.ContentItem.Thinking>()
                .mapNotNull { it.toResponseReasoningItem(id.value) }
                .forEach { add(ResponseInputItem.ofReasoning(it)) }

            val assistantText = content
                .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
                .joinToString("\n") { it.structured.fullText }
                .trim()

            if (assistantText.isNotBlank()) {
                add(
                    ResponseInputItem.ofResponseOutputMessage(
                        ResponseOutputMessage.builder()
                            .id(id.value)
                            .status(ResponseOutputMessage.Status.COMPLETED)
                            .addContent(
                                ResponseOutputText.builder()
                                    .annotations(emptyList())
                                    .text(assistantText)
                                    .build()
                            )
                            .build()
                    )
                )
            }

            content
                .filterIsInstance<Conversation.Message.ContentItem.ToolCall>()
                .forEach { toolCall ->
                    add(
                        ResponseInputItem.ofFunctionCall(
                            com.openai.models.responses.ResponseFunctionToolCall.builder()
                                .callId(toolCall.id.value)
                                .name(toolCall.call.name)
                                .arguments(toolCall.call.input.toString())
                                .build()
                        )
                    )
                }
        }
    }

    private fun Conversation.Message.toSystemInputItem(): ResponseInputItem? {
        val text = content
            .filterIsInstance<Conversation.Message.ContentItem.System>()
            .joinToString("\n") { it.content }
            .trim()

        if (text.isBlank()) return null

        return ResponseInputItem.ofMessage(
            ResponseInputItem.Message.builder()
                .role(ResponseInputItem.Message.Role.SYSTEM)
                .addInputTextContent(text)
                .build()
        )
    }

    private fun buildUserMessageText(message: Conversation.Message): String? {
        val instructionsPrefix = message.instructions.joinToString("\n") { it.toXmlLine() }
        val text = message.content
            .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
            .joinToString("\n") { it.text }
            .trim()

        val merged = listOf(
            instructionsPrefix.takeIf { it.isNotBlank() },
            text.takeIf { it.isNotBlank() }
        ).filterNotNull()
            .joinToString("\n")
            .trim()

        return merged.ifBlank { null }
    }

    private fun buildReasoning(outputConfig: AgentDefinition.OutputConfig?): Reasoning? {
        val effort = when (outputConfig?.effort?.lowercase()) {
            "low" -> ReasoningEffort.LOW
            "medium" -> ReasoningEffort.MEDIUM
            "high" -> ReasoningEffort.HIGH
            "max", "xhigh" -> ReasoningEffort.XHIGH
            else -> null
        } ?: return null

        return Reasoning.builder()
            .effort(effort)
            .summary(Reasoning.Summary.DETAILED)
            .build()
    }

    private fun buildTextConfig(): ResponseTextConfig {
        return ResponseTextConfig.builder()
            .verbosity(ResponseTextConfig.Verbosity.MEDIUM)
            .build()
    }

    private fun AiToolCallback.toResponseFunctionTool(): FunctionTool {
        return FunctionTool.builder()
            .name(definition.name)
            .description(definition.description)
            .parameters(definition.inputSchema.toFunctionParameters())
            .strict(false)
            .build()
    }

    private fun String.toFunctionParameters(): FunctionTool.Parameters {
        val schema = runCatching { json.parseToJsonElement(this) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())

        return FunctionTool.Parameters.builder()
            .additionalProperties(schema.mapValues { (_, value) -> value.toJsonValue() })
            .build()
    }

    private fun JsonElement.toJsonValue(): JsonValue {
        return when (this) {
            is JsonObject -> JsonValue.from(this.mapValues { (_, value) -> value.toJsonValue() })
            is JsonArray -> JsonValue.from(this.map { it.toJsonValue() })
            is JsonPrimitive -> when {
                isString -> JsonValue.from(content)
                booleanOrNull != null -> JsonValue.from(booleanOrNull)
                longOrNull != null -> JsonValue.from(longOrNull)
                doubleOrNull != null -> JsonValue.from(doubleOrNull)
                else -> JsonValue.from(content)
            }

            JsonNull -> JsonValue.from(null)
        }
    }

    private fun ResponseOutputMessage.toAssistantMessage(): AiAssistantMessage? {
        val blocks = buildList {
            content().forEach { contentPart ->
                when {
                    contentPart.isOutputText() -> {
                        val text = contentPart.asOutputText().text().trim()
                        if (text.isNotBlank()) {
                            add(
                                Conversation.Message.ContentItem.AssistantMessage(
                                    structured = Conversation.Message.StructuredText(fullText = text),
                                    state = Conversation.Message.BlockState.COMPLETE
                                )
                            )
                        }
                    }

                    contentPart.isRefusal() -> {
                        val refusalText = contentPart.asRefusal().refusal().trim()
                        if (refusalText.isNotBlank()) {
                            add(
                                Conversation.Message.ContentItem.AssistantMessage(
                                    structured = Conversation.Message.StructuredText(fullText = refusalText),
                                    state = Conversation.Message.BlockState.COMPLETE
                                )
                            )
                        }
                    }
                }
            }
        }
        val messageId = id()

        return blocks.takeIf { it.isNotEmpty() }?.let {
            AiAssistantMessage(
                content = it,
                metadata = buildMap {
                    put("messageId", messageId)
                }
            )
        }
    }

    private fun com.openai.models.responses.ResponseFunctionToolCall.toToolCallMessage(): AiAssistantMessage {
        val input = runCatching { json.parseToJsonElement(arguments()) }
            .getOrElse { JsonObject(mapOf("raw" to JsonPrimitive(arguments()))) }

        return AiAssistantMessage(
            content = listOf(
                Conversation.Message.ContentItem.ToolCall(
                    id = Conversation.Message.ContentItem.ToolCall.Id(callId()),
                    call = Conversation.Message.ContentItem.ToolCall.Data(
                        name = name(),
                        input = input
                    ),
                    state = Conversation.Message.BlockState.COMPLETE
                )
            )
        )
    }

    private fun ResponseReasoningItem.toReasoningMessage(): AiAssistantMessage? {
        val thinkingText = buildList {
            summary().forEach { add(it.text()) }
            content().getOrNull()?.forEach { add(it.text()) }
        }.joinToString("\n").trim()

        if (thinkingText.isBlank()) return null

        return AiAssistantMessage(
            content = listOf(
                Conversation.Message.ContentItem.Thinking(
                    thinking = thinkingText,
                    signature = encryptedContent().getOrNull(),
                    state = Conversation.Message.BlockState.COMPLETE
                )
            )
        )
    }

    private fun Conversation.Message.ContentItem.Thinking.toResponseReasoningItem(
        messageId: String,
    ): ResponseReasoningItem? {
        if (thinking.isBlank() && signature.isNullOrBlank()) return null

        return ResponseReasoningItem.builder()
            .id("${messageId}-reasoning")
            .addSummary(
                ResponseReasoningItem.Summary.builder()
                    .text(thinking.ifBlank { "Reasoning block" })
                    .build()
            )
            .apply {
                signature?.let(::encryptedContent)
                if (thinking.isNotBlank()) {
                    addContent(
                        ResponseReasoningItem.Content.builder()
                            .text(thinking)
                            .build()
                    )
                }
            }
            .build()
    }

    private fun ResponseUsage.toAiUsage(): AiUsage {
        return AiUsage(
            promptTokens = inputTokens().toInt(),
            completionTokens = outputTokens().toInt(),
            thinkingTokens = outputTokensDetails().reasoningTokens().toInt(),
            cacheReadTokens = inputTokensDetails().cachedTokens().toInt()
        )
    }

    private fun Conversation.Message.ContentItem.ToolResult.asPlainText(): String {
        return result.joinToString("\n") { data ->
            when (data) {
                is Conversation.Message.ContentItem.ToolResult.Data.Text -> data.content
                is Conversation.Message.ContentItem.ToolResult.Data.Base64Data ->
                    "[base64:${data.mediaType.value}] ${data.data}"
                is Conversation.Message.ContentItem.ToolResult.Data.UrlData ->
                    buildString {
                        append("[url")
                        data.mediaType?.let { append(":${it.value}") }
                        append("] ${data.url}")
                    }
                is Conversation.Message.ContentItem.ToolResult.Data.FileData ->
                    buildString {
                        append("[file")
                        data.mediaType?.let { append(":${it.value}") }
                        append("] ${data.fileId}")
                    }
            }
        }
    }
}
