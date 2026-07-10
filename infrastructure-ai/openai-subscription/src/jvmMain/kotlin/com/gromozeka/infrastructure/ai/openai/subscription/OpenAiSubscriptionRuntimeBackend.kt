package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.infrastructure.ai.runtime.AiRuntimeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class OpenAiSubscriptionRuntimeBackend(
    private val authService: OpenAiSubscriptionAuthService,
    private val modelsClient: OpenAiSubscriptionModelsClient,
    private val responsesClient: OpenAiSubscriptionResponsesClient,
    private val requestMapper: OpenAiSubscriptionRequestMapper,
    private val responseMapper: OpenAiSubscriptionResponseMapper,
) : AiRuntimeBackend {

    override fun supports(connectionKind: AiConnection.Kind): Boolean =
        connectionKind == AiConnection.Kind.OPENAI_SUBSCRIPTION

    override fun createRuntime(
        connection: AiConnection,
        modelConfiguration: AiModelConfiguration,
        projectPath: String?,
    ): AiRuntime {
        return Runtime(
            connectionId = connection.id.value,
            modelConfigurationId = modelConfiguration.id.value,
            modelName = modelConfiguration.providerModelId,
            authService = authService,
            modelsClient = modelsClient,
            responsesClient = responsesClient,
            requestMapper = requestMapper,
            responseMapper = responseMapper,
        )
    }
}

private class Runtime(
    private val connectionId: String,
    private val modelConfigurationId: String,
    private val modelName: String,
    private val authService: OpenAiSubscriptionAuthService,
    private val modelsClient: OpenAiSubscriptionModelsClient,
    private val responsesClient: OpenAiSubscriptionResponsesClient,
    private val requestMapper: OpenAiSubscriptionRequestMapper,
    private val responseMapper: OpenAiSubscriptionResponseMapper,
) : AiRuntime {
    private val fallbackConversationKey = UUID.randomUUID().toString()
    override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities(
        supportsAutoCompaction = true,
    )

    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
        return execute(request, retryOnUnauthorized = true)
    }

    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = flow {
        emit(call(request))
    }

    private suspend fun execute(
        request: AiRuntimeRequest,
        retryOnUnauthorized: Boolean,
    ): AiRuntimeResponse = withContext(Dispatchers.IO) {
        val session = authService.getValidSession()
        val rawConversationKey = request.options.toolContext["conversationId"] as? String ?: fallbackConversationKey
        val conversationKey = rawConversationKey.toOpenAiSubscriptionKey()
        val promptCacheKey = (request.options.toolContext["promptCacheKey"] as? String ?: rawConversationKey)
            .toOpenAiSubscriptionKey()
        try {
            val modelProfile = modelsClient.getProfile(session, modelName)
            val requestBody = requestMapper.toRequest(
                request = request,
                modelProfile = modelProfile,
                conversationKey = promptCacheKey,
            )
            val parsed = responsesClient.create(
                session = session,
                conversationKey = conversationKey,
                requestBody = requestBody,
                modelProfile = modelProfile,
                assistantResponseFormat = request.options.assistantResponseFormat,
            )
            responseMapper.toRuntimeResponse(
                outputItems = parsed.outputItems,
                completed = parsed.completed,
                conversationKey = conversationKey,
                connectionId = connectionId,
                modelConfigurationId = modelConfigurationId,
                modelName = modelName,
                assistantResponseFormat = request.options.assistantResponseFormat,
            )
        } catch (error: OpenAiSubscriptionUnauthorizedException) {
            if (!retryOnUnauthorized) throw error

            authService.refreshTokens(session.refreshToken).getOrThrow()
            execute(request, retryOnUnauthorized = false)
        }
    }
}
