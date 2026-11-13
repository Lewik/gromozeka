package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** One measured request and its output, never cumulative billing or a measurement of a later branch. */
@Serializable
data class AiContextMeasurement(
    val version: Int,
    val estimatorId: String,
    val connectionId: String,
    val configurationId: String,
    val model: String,
    val threadId: String,
    val semanticsHash: String,
    val sourceCount: Int,
    val sourceHash: String,
    val outputCount: Int,
    val outputHash: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val inputEstimate: Int,
    val outputEstimate: Int,
    val portableInputEstimate: Int,
    val portableOutputEstimate: Int,
) {
    init {
        require(version == VERSION && estimatorId.isNotBlank() && sourceCount > 0 && outputCount > 0 &&
            listOf(inputTokens, outputTokens, inputEstimate, outputEstimate, portableInputEstimate, portableOutputEstimate).all { it >= 0 }) {
            "Invalid stored context measurement"
        }
    }

    fun estimate(currentEstimate: Int, portable: Boolean): AiContextInspection? {
        val previousEstimate = if (portable) portableInputEstimate.toLong() + portableOutputEstimate
            else inputEstimate.toLong() + outputEstimate
        val tokens = inputTokens.toLong() + outputTokens + currentEstimate - previousEstimate
        if (tokens < 0) return null
        require(tokens <= Int.MAX_VALUE.toLong()) { "Context estimate exceeds the supported range" }
        return AiContextInspection(tokens.toInt(), AiContextInspection.Precision.USAGE_CALIBRATED_ESTIMATE)
    }

    companion object {
        const val KEY = "gromozekaContextMeasurement"
        private const val VERSION = 2
        private val json = Json { encodeDefaults = true }

        fun prepare(runtime: ResolvedAiRuntime, request: AiRuntimeRequest): AiRuntimeRequest = request.copy(
            messages = if (runtime.connection.kind == AiConnection.Kind.CLAUDE_CODE) request.originalCollaborationMessages()
                else request.projectedMessages(runtime.connection.kind.nativeCompactionProvider()),
        )

        fun capture(runtime: ResolvedAiRuntime, request: AiRuntimeRequest, response: AiRuntimeResponse,
            estimator: AiRequestTokenEstimator): AiContextMeasurement? {
            val usage = response.contextUsage ?: return null
            val output = usage.outputTokens ?: return null
            val thread = request.options.toolContext["threadId"] as? String ?: return null
            if (request.options.usagePurpose != "CONVERSATION" || request.messages.isEmpty() || response.messages.isEmpty() ||
                response.outcome.isFailure || response.messages.any { message ->
                    message.content.any { it is Conversation.Message.ContentItem.ContextCompactionResult }
                }) return null
            return AiContextMeasurement(VERSION, estimator.id, runtime.connection.id.value,
                runtime.modelConfiguration.id.value, runtime.modelConfiguration.providerModelId, thread,
                semanticsHash(runtime, request), request.messages.size, sourceHash(request.messages),
                response.messages.size, contentHash(response.messages.map { it.content }), usage.inputTokens, output,
                estimator.input(request), estimator.output(response.messages),
                AiRequestTokenEstimate.input(request), AiRequestTokenEstimate.output(response.messages))
        }

        fun matching(runtime: ResolvedAiRuntime, request: AiRuntimeRequest): AiContextMeasurement? {
            val thread = request.options.toolContext["threadId"] as? String ?: return null
            val index = request.messages.indexOfLast { KEY in it.providerMetadata }
            if (index < 0) return null
            val stored = request.messages[index].providerMetadata.getValue(KEY).jsonObject
            // Baselines produced before the content-bound contract require one fresh count.
            if (stored["version"]?.jsonPrimitive?.intOrNull != VERSION) return null
            val measurement = json.decodeFromJsonElement<AiContextMeasurement>(stored)
            if (measurement.connectionId != runtime.connection.id.value ||
                measurement.configurationId != runtime.modelConfiguration.id.value ||
                measurement.model != runtime.modelConfiguration.providerModelId || measurement.threadId != thread ||
                measurement.semanticsHash != semanticsHash(runtime, request) ||
                measurement.sourceCount.toLong() + measurement.outputCount - 1 != index.toLong() ||
                sourceHash(request.messages.take(measurement.sourceCount)) != measurement.sourceHash ||
                contentHash(request.messages.subList(measurement.sourceCount, index + 1).map { it.content }) != measurement.outputHash) return null
            return measurement
        }

        fun portableInspection(runtime: ResolvedAiRuntime, request: AiRuntimeRequest): AiContextInspection? {
            val prepared = prepare(runtime, request)
            return matching(runtime, prepared)?.estimate(AiRequestTokenEstimate.input(prepared), portable = true)
        }

        private fun semanticsHash(runtime: ResolvedAiRuntime, request: AiRuntimeRequest): String = hash(buildJsonObject {
            put("kind", runtime.connection.kind.name)
            put("executionTarget", json.encodeToJsonElement(runtime.connection.executionTarget))
            put("endpoint", (runtime.connection as? AiConnection.ConfigurableHttpAiConnection)?.baseUrl)
            put("spec", json.encodeToJsonElement(runtime.modelSpec))
            put("reasoning", json.encodeToJsonElement(request.options.reasoning ?: runtime.modelConfiguration.defaultParameters.reasoning))
            put("assistantFormat", request.options.assistantResponseFormat.name)
            put("toolAccess", json.encodeToJsonElement(request.options.toolAccess))
            put("nativeWebSearch", when (val connection = runtime.connection) {
                is AiConnection.OpenAiApi -> connection.webSearchEnabled
                is AiConnection.OpenAiSubscription -> connection.webSearchEnabled
                else -> false
            })
        }.toString())

        private fun sourceHash(messages: List<Conversation.Message>): String = hash(JsonArray(messages.map { message ->
            buildJsonObject {
                put("id", message.id.value)
                put("role", message.role.name)
                put("content", json.encodeToJsonElement(message.content))
                put("replay", JsonObject(message.providerMetadata - KEY))
            }
        }).toString())

        private fun contentHash(content: List<List<Conversation.Message.ContentItem>>): String = hash(json.encodeToString(content))
        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
