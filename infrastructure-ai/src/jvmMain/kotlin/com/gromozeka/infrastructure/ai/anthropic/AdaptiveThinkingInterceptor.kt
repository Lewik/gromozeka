package com.gromozeka.infrastructure.ai.anthropic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import klog.KLoggers
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/**
 * Intercepts Anthropic API requests to inject adaptive thinking and effort parameters.
 *
 * Spring AI 1.1.x only supports ThinkingType.ENABLED/DISABLED, but Anthropic 4.6 models
 * support adaptive thinking with effort control. This interceptor bridges the gap by:
 *
 * 1. Reading custom X-Gromozeka-* headers set by ConversationEngine
 * 2. Removing those headers before sending to Anthropic (they're internal)
 * 3. Patching the JSON request body with proper thinking/effort config
 *
 * Custom headers used:
 * - X-Gromozeka-Thinking-Type: "adaptive" | "enabled" | "disabled"
 * - X-Gromozeka-Effort: "max" | "high" | "medium" | "low"
 */
class AdaptiveThinkingInterceptor : ClientHttpRequestInterceptor {

    private val log = KLoggers.logger(this)
    private val objectMapper = ObjectMapper()

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution
    ): ClientHttpResponse {
        val thinkingType = request.headers.getFirst(HEADER_THINKING_TYPE)
        val effort = request.headers.getFirst(HEADER_EFFORT)

        // Remove custom headers — they must NOT reach Anthropic
        request.headers.remove(HEADER_THINKING_TYPE)
        request.headers.remove(HEADER_EFFORT)

        val modifiedBody = if (thinkingType != null || effort != null) {
            log.info { "Patching Anthropic request: thinkingType=$thinkingType, effort=$effort" }
            patchRequestBody(body, thinkingType, effort)
        } else {
            body
        }

        return execution.execute(request, modifiedBody)
    }

    private fun patchRequestBody(body: ByteArray, thinkingType: String?, effort: String?): ByteArray {
        if (body.isEmpty()) return body

        return try {
            val json = objectMapper.readTree(body) as? ObjectNode ?: return body

            // Patch thinking configuration
            if (thinkingType == "adaptive") {
                val thinkingNode = objectMapper.createObjectNode()
                thinkingNode.put("type", "adaptive")
                json.set<ObjectNode>("thinking", thinkingNode)

                // Remove budget_tokens — not used with adaptive thinking
                // Spring AI may have set it via ThinkingType.ENABLED workaround
            }

            // Patch effort via output_config (only meaningful with adaptive thinking)
            if (effort != null) {
                val outputConfigNode = objectMapper.createObjectNode()
                outputConfigNode.put("effort", effort)
                json.set<ObjectNode>("output_config", outputConfigNode)
            }

            objectMapper.writeValueAsBytes(json)
        } catch (e: Exception) {
            log.warn(e) { "Failed to patch request body for adaptive thinking, using original" }
            body
        }
    }

    companion object {
        const val HEADER_THINKING_TYPE = "X-Gromozeka-Thinking-Type"
        const val HEADER_EFFORT = "X-Gromozeka-Effort"
    }
}
