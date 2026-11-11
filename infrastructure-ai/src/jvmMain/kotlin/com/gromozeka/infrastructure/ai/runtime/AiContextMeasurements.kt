package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ResolvedAiRuntime
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** A single request baseline. Never sum usage across turns or reuse it after a context/model change. */
internal object AiContextMeasurements {
    private const val KEY = "gromozekaContextMeasurement"
    private val json = Json

    @Serializable
    private data class Measurement(
        val connectionId: String,
        val configurationId: String,
        val model: String,
        val threadId: String,
        val sourceCount: Int,
        val sourceIdsHash: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val inputEstimate: Int,
        val outputEstimate: Int,
    ) {
        init {
            require(sourceCount > 0 && inputTokens >= 0 && outputTokens >= 0 && inputEstimate >= 0 && outputEstimate >= 0) {
                "Invalid stored context measurement"
            }
        }
    }

    fun record(runtime: ResolvedAiRuntime, request: AiRuntimeRequest, response: AiRuntimeResponse): AiRuntimeResponse {
        val input = response.contextUsage?.inputTokens ?: return response
        val output = response.contextUsage?.outputTokens ?: return response
        val thread = request.options.toolContext["threadId"] as? String ?: return response
        if (request.options.usagePurpose != "CONVERSATION" || response.messages.isEmpty() ||
            response.messages.any { message -> message.content.any { it is Conversation.Message.ContentItem.ContextCompactionResult } }) return response
        val measurement = Measurement(runtime.connection.id.value, runtime.modelConfiguration.id.value,
            runtime.modelConfiguration.providerModelId, thread, request.messages.size, idsHash(request.messages),
            input, output, AiRequestTokenEstimate.input(request), AiRequestTokenEstimate.output(response.messages))
        return response.copy(messages = response.messages.mapIndexed { index, message ->
            if (index == response.messages.lastIndex) message.copy(metadata = message.metadata + (KEY to json.encodeToJsonElement(measurement)))
            else message
        })
    }

    fun calibrate(runtime: ResolvedAiRuntime, request: AiRuntimeRequest, inspection: AiContextInspection): AiContextInspection {
        if (inspection.precision != AiContextInspection.Precision.ESTIMATE) return inspection
        val thread = request.options.toolContext["threadId"] as? String ?: return inspection
        val measurement = request.messages.asReversed().firstNotNullOfOrNull { it.providerMetadata[KEY] }
            ?.let { json.decodeFromJsonElement<Measurement>(it) } ?: return inspection
        if (measurement.connectionId != runtime.connection.id.value || measurement.configurationId != runtime.modelConfiguration.id.value ||
            measurement.model != runtime.modelConfiguration.providerModelId || measurement.threadId != thread ||
            measurement.sourceCount >= request.messages.size ||
            idsHash(request.messages.take(measurement.sourceCount)) != measurement.sourceIdsHash) return inspection
        val tokens = measurement.inputTokens.toLong() + measurement.outputTokens + inspection.inputTokens -
            measurement.inputEstimate - measurement.outputEstimate
        // A changed prompt/tool mix can invalidate the byte estimator's scale. Do not invent a zero-sized context.
        if (tokens < 0) return inspection
        require(tokens <= Int.MAX_VALUE.toLong()) { "Context estimate exceeds the supported range" }
        return inspection.copy(inputTokens = tokens.toInt(), precision = AiContextInspection.Precision.USAGE_CALIBRATED_ESTIMATE)
    }

    private fun idsHash(messages: List<Conversation.Message>): String = MessageDigest.getInstance("SHA-256")
        .digest(messages.joinToString("\n") { it.id.value }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
