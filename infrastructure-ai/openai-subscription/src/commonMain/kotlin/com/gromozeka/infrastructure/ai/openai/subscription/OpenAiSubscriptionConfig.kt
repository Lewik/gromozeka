package com.gromozeka.infrastructure.ai.openai.subscription

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class OpenAiSubscriptionConfig(
    val clientId: String = DEFAULT_CLIENT_ID,
    val issuer: String = DEFAULT_ISSUER,
    val redirectUri: String = DEFAULT_REDIRECT_URI,
    val includeRedirectUri: Boolean = false,
    val scope: String = DEFAULT_SCOPE,
    val pendingVerifier: String? = null,
    val pendingState: String? = null,
    val pendingDeviceCode: OpenAiSubscriptionPendingDeviceCode? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val accountId: String? = null,
    val expiresAt: Long? = null,
) {
    companion object {
        const val DEFAULT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val DEFAULT_ISSUER = "https://auth.openai.com"
        const val DEFAULT_REDIRECT_URI = "http://localhost:1455/auth/callback"
        const val DEFAULT_SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke"
    }
}

data class OpenAiSubscriptionSession(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String?,
    val accountId: String?,
    val expiresAt: Long,
)

data class OpenAiSubscriptionAuthorizationUrl(
    val url: String,
    val verifier: String,
    val state: String,
)

@Serializable
data class OpenAiSubscriptionPendingDeviceCode(
    val verificationUrl: String,
    val userCode: String,
    val deviceAuthId: String,
    val intervalSeconds: Long,
    val expiresAt: Long,
)

@Serializable
data class OpenAiSubscriptionResponsesRequest(
    val model: String,
    val input: List<JsonObject>,
    val store: Boolean = false,
    val stream: Boolean = true,
    val instructions: String? = null,
    @SerialName("previous_response_id")
    val previousResponseId: String? = null,
    @SerialName("context_management")
    val contextManagement: List<JsonObject>? = null,
    @SerialName("parallel_tool_calls")
    val parallelToolCalls: Boolean = true,
    val tools: List<JsonObject> = emptyList(),
    @SerialName("tool_choice")
    val toolChoice: JsonElement? = null,
    val include: List<String> = listOf("reasoning.encrypted_content"),
    val text: JsonObject? = null,
    val reasoning: JsonObject? = null,
    @SerialName("service_tier")
    val serviceTier: String? = null,
    @SerialName("prompt_cache_key")
    val promptCacheKey: String? = null,
)

@Serializable
data class OpenAiSubscriptionResponsesWebSocketRequest(
    val type: String = "response.create",
    val model: String,
    val input: List<JsonObject>,
    val store: Boolean = false,
    val stream: Boolean = true,
    val instructions: String? = null,
    @SerialName("previous_response_id")
    val previousResponseId: String? = null,
    @SerialName("context_management")
    val contextManagement: List<JsonObject>? = null,
    @SerialName("parallel_tool_calls")
    val parallelToolCalls: Boolean = true,
    val tools: List<JsonObject> = emptyList(),
    @SerialName("tool_choice")
    val toolChoice: JsonElement? = null,
    val include: List<String> = listOf("reasoning.encrypted_content"),
    val text: JsonObject? = null,
    val reasoning: JsonObject? = null,
    @SerialName("service_tier")
    val serviceTier: String? = null,
    @SerialName("prompt_cache_key")
    val promptCacheKey: String? = null,
) {
    companion object {
        fun from(request: OpenAiSubscriptionResponsesRequest): OpenAiSubscriptionResponsesWebSocketRequest {
            return OpenAiSubscriptionResponsesWebSocketRequest(
                model = request.model,
                input = request.input,
                store = request.store,
                stream = request.stream,
                instructions = request.instructions,
                previousResponseId = request.previousResponseId,
                contextManagement = request.contextManagement,
                parallelToolCalls = request.parallelToolCalls,
                tools = request.tools,
                toolChoice = request.toolChoice,
                include = request.include,
                text = request.text,
                reasoning = request.reasoning,
                serviceTier = request.serviceTier,
                promptCacheKey = request.promptCacheKey,
            )
        }
    }
}

@Serializable
data class OpenAiSubscriptionSseEnvelope(
    val type: String? = null,
    val response: JsonObject? = null,
    val item: JsonObject? = null,
    val delta: String? = null,
    @SerialName("summary_index")
    val summaryIndex: Long? = null,
    @SerialName("content_index")
    val contentIndex: Long? = null,
)

@Serializable
data class OpenAiSubscriptionCompletedResponse(
    val id: String,
    val status: String? = null,
    val usage: OpenAiSubscriptionUsage? = null,
    val output: List<JsonObject> = emptyList(),
)

@Serializable
data class OpenAiSubscriptionUsage(
    @SerialName("input_tokens")
    val inputTokens: Long = 0,
    @SerialName("input_tokens_details")
    val inputTokensDetails: OpenAiSubscriptionInputTokensDetails? = null,
    @SerialName("output_tokens")
    val outputTokens: Long = 0,
    @SerialName("output_tokens_details")
    val outputTokensDetails: OpenAiSubscriptionOutputTokensDetails? = null,
)

@Serializable
data class OpenAiSubscriptionInputTokensDetails(
    @SerialName("cached_tokens")
    val cachedTokens: Long = 0,
)

@Serializable
data class OpenAiSubscriptionOutputTokensDetails(
    @SerialName("reasoning_tokens")
    val reasoningTokens: Long = 0,
)
