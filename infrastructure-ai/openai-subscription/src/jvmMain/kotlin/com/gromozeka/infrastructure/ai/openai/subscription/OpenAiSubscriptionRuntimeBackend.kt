package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.AIProvider
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
    private val responsesClient: OpenAiSubscriptionResponsesClient,
    private val requestMapper: OpenAiSubscriptionRequestMapper,
    private val responseMapper: OpenAiSubscriptionResponseMapper,
) : AiRuntimeBackend {

    override fun supports(provider: AIProvider): Boolean = provider == AIProvider.OPEN_AI_SUBSCRIPTION

    override fun createRuntime(
        provider: AIProvider,
        modelName: String,
        projectPath: String?,
    ): AiRuntime {
        return Runtime(
            modelName = modelName,
            authService = authService,
            responsesClient = responsesClient,
            requestMapper = requestMapper,
            responseMapper = responseMapper,
        )
    }
}

private class Runtime(
    private val modelName: String,
    private val authService: OpenAiSubscriptionAuthService,
    private val responsesClient: OpenAiSubscriptionResponsesClient,
    private val requestMapper: OpenAiSubscriptionRequestMapper,
    private val responseMapper: OpenAiSubscriptionResponseMapper,
) : AiRuntime {
    private val fallbackConversationKey = UUID.randomUUID().toString()

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
        val conversationKey = request.options.toolContext["conversationId"] as? String ?: fallbackConversationKey
        val requestBody = requestMapper.toRequest(
            request = request,
            modelName = modelName,
            conversationKey = conversationKey,
        )

        try {
            val parsed = responsesClient.create(
                session = session,
                conversationKey = conversationKey,
                requestBody = requestBody,
            )
            responseMapper.toRuntimeResponse(
                outputItems = parsed.outputItems,
                completed = parsed.completed,
                conversationKey = conversationKey,
            )
        } catch (error: OpenAiSubscriptionUnauthorizedException) {
            if (!retryOnUnauthorized) throw error

            authService.refreshTokens(session.refreshToken).getOrThrow()
            execute(request, retryOnUnauthorized = false)
        }
    }
}
