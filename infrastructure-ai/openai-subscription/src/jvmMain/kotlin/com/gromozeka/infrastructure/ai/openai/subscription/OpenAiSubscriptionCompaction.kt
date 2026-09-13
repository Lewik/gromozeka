package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiContextUsage
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiStepOutcome
import klog.KLoggers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.springframework.stereotype.Component

@Component
class OpenAiSubscriptionCompaction(
    private val client: OpenAiSubscriptionResponsesClient,
    private val requestMapper: OpenAiSubscriptionRequestMapper,
    private val responseMapper: OpenAiSubscriptionResponseMapper,
) {
    private val log = KLoggers.logger(this)

    suspend fun executeIfNeeded(
        request: AiRuntimeRequest,
        requestBody: OpenAiSubscriptionResponsesRequest,
        modelProfile: OpenAiSubscriptionModelProfile,
        session: OpenAiSubscriptionSession,
        conversationKey: String,
        connectionId: String,
        modelConfigurationId: String,
    ): AiRuntimeResponse? {
        val threshold = request.options.autoCompactionThresholdTokens ?: return null
        if (!modelProfile.useResponsesLite || requestBody.input.isEmpty()) return null
        val lastCheckpoint = request.messages.indexOfLast { message ->
            message.content.any { it is ContextCompactionResult }
        }
        if (lastCheckpoint >= 0 && lastCheckpoint == request.messages.lastIndex) return null
        val estimatedTokens = estimateContextTokens(
            request.messages.drop(lastCheckpoint.coerceAtLeast(0)), requestBody, connectionId,
        )
        if (estimatedTokens < threshold) return null

        log.info("OpenAI subscription compaction started: conversationKey=$conversationKey, " +
            "model=${modelProfile.slug}, estimatedTokens=$estimatedTokens, threshold=$threshold")
        val parsed = client.create(
            session = session,
            conversationKey = conversationKey,
            requestBody = requestBody.copy(
                input = requestBody.input + buildJsonObject { put("type", "compaction_trigger") },
                contextManagement = null,
                text = null,
            ),
            modelProfile = modelProfile,
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        )
        check(parsed.completed?.status == "completed") { "OpenAI subscription compaction did not complete" }
        val compacted = parsed.outputItems.singleOrNull { it["type"] == JsonPrimitive("compaction") }
        check(compacted != null && !compacted["encrypted_content"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
            "OpenAI subscription compaction returned no unique replayable checkpoint"
        }
        check(parsed.outputItems.none { it["type"] == JsonPrimitive("function_call") }) {
            "OpenAI subscription compaction unexpectedly returned tool calls"
        }
        val retainedInput = retainUserInput(requestBody.input, minOf(16_000L, threshold.toLong() / 4))
        val response = responseMapper.toRuntimeResponse(
            outputItems = listOf(compacted), completed = parsed.completed,
            conversationKey = conversationKey, connectionId = connectionId,
            modelConfigurationId = modelConfigurationId, modelName = modelProfile.slug,
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        )
        val message = response.messages.single()
        val checkpoint = message.content.single() as ContextCompactionResult
        val payload = checkpoint.payload as ContextCompactionResult.Payload.OpaqueProviderState
        val replayItem = payload.state.getValue("replay_item").jsonObject
        val nextTokens = estimateRequestTokens(requestBody.copy(input = retainedInput + replayItem))
        log.info("OpenAI subscription compaction completed: conversationKey=$conversationKey, " +
            "retainedUserItems=${retainedInput.size}, estimatedTokens=$nextTokens")
        return response.copy(
            messages = listOf(message.copy(content = listOf(checkpoint.copy(
                payload = payload.copy(state = JsonObject(payload.state +
                    ("retained_input" to JsonArray(retainedInput)))),
            )))),
            contextUsage = AiContextUsage(nextTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
            outcome = AiStepOutcome.CONTINUE,
            finishReason = "compaction",
            providerMetadata = response.providerMetadata + mapOf(
                CONTEXT_TOKENS_KEY to nextTokens,
                CONTEXT_ESTIMATE_KEY to nextTokens,
            ),
        )
    }

    fun recordContextUsage(
        requestBody: OpenAiSubscriptionResponsesRequest,
        parsed: OpenAiSubscriptionParsedResponse,
        response: AiRuntimeResponse,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): AiRuntimeResponse {
        val usage = response.usage ?: return response
        if (parsed.outputItems.any { it["type"] in listOf(JsonPrimitive("compaction"), JsonPrimitive("compaction_summary")) }) {
            return response
        }
        val output = requestMapper.toReplayItems(parsed.outputItems, assistantResponseFormat)
        return response.copy(providerMetadata = response.providerMetadata + mapOf(
            CONTEXT_TOKENS_KEY to usage.totalTokens.toLong(),
            CONTEXT_ESTIMATE_KEY to estimateRequestTokens(requestBody.copy(input = requestBody.input + output)),
        ))
    }

    internal fun estimateContextTokens(
        messages: List<Conversation.Message>,
        request: OpenAiSubscriptionResponsesRequest,
        connectionId: String,
    ): Long {
        val estimate = estimateRequestTokens(request)
        val measured = messages.lastOrNull { message ->
            val metadata = message.providerMetadata
            metadata["provider"] == JsonPrimitive(AiConnection.Kind.OPENAI_SUBSCRIPTION.name) &&
                metadata["connectionId"] == JsonPrimitive(connectionId) &&
                metadata["model"] == JsonPrimitive(request.model) &&
                metadata[CONTEXT_TOKENS_KEY]?.jsonPrimitive?.longOrNull != null &&
                metadata[CONTEXT_ESTIMATE_KEY]?.jsonPrimitive?.longOrNull != null
        }?.providerMetadata ?: return estimate
        val measuredTokens = measured.getValue(CONTEXT_TOKENS_KEY).jsonPrimitive.longOrNull!!
        val measuredEstimate = measured.getValue(CONTEXT_ESTIMATE_KEY).jsonPrimitive.longOrNull!!
        return maxOf(estimate, measuredTokens + (estimate - measuredEstimate).coerceAtLeast(0))
    }

    internal fun retainUserInput(input: List<JsonObject>, tokenBudget: Long): List<JsonObject> {
        var remaining = tokenBudget
        return input.asReversed().asSequence()
            .filter { it["type"] == JsonPrimitive("message") && it["role"] == JsonPrimitive("user") }
            .mapNotNull { item ->
                when (val content = item["content"]) {
                    is JsonPrimitive -> item
                    is JsonArray -> content.filter { (it as? JsonObject)?.get("type") == JsonPrimitive("input_text") }
                        .takeIf { it.isNotEmpty() }?.let { JsonObject(item + ("content" to JsonArray(it))) }
                    else -> null
                }
            }
            .takeWhile { item ->
                remaining -= estimateItemTokens(item)
                remaining >= 0
            }.toList().asReversed()
    }

    internal fun estimateRequestTokens(request: OpenAiSubscriptionResponsesRequest): Long =
        request.input.sumOf(::estimateItemTokens) +
            textTokens(request.instructions.orEmpty()) +
            textTokens(request.tools.orEmpty().joinToString("\n")) +
            textTokens(request.text?.toString().orEmpty())

    private fun estimateItemTokens(item: JsonObject): Long = when (item["type"]?.jsonPrimitive?.contentOrNull) {
        "reasoning", "compaction", "compaction_summary" -> {
            val encodedLength = item["encrypted_content"]?.jsonPrimitive?.contentOrNull?.length?.toLong()
            // Match Codex's decoded payload estimate; base64 and encryption overhead are not model tokens.
            if (encodedLength == null) textTokens(item.toString())
            else ((encodedLength * 3 / 4 - 650).coerceAtLeast(0) + 3) / 4
        }
        else -> (estimateVisibleBytes(item) + 3) / 4
    }

    private fun estimateVisibleBytes(value: JsonElement): Long = when (value) {
        is JsonPrimitive -> value.content.toByteArray(Charsets.UTF_8).size.toLong()
        is JsonArray -> value.sumOf(::estimateVisibleBytes)
        is JsonObject -> when (value["type"]?.jsonPrimitive?.contentOrNull) {
            "input_image" -> 40_000L
            else -> value.entries.sumOf { (key, child) ->
                if (key in setOf("id", "call_id", "type")) 0L else estimateVisibleBytes(child)
            }
        }
    }

    private fun textTokens(text: String): Long = (text.toByteArray(Charsets.UTF_8).size.toLong() + 3) / 4

    companion object {
        internal const val CONTEXT_TOKENS_KEY = "openaiSubscriptionContextTokens"
        internal const val CONTEXT_ESTIMATE_KEY = "openaiSubscriptionContextEstimate"
    }
}
