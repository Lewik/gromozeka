package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.infrastructure.ai.runtime.AiRuntimeBackend
import com.openai.errors.UnauthorizedException
import com.openai.helpers.ResponseAccumulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class OpenAiSubscriptionRuntimeBackend(
    private val authService: OpenAiSubscriptionAuthService,
    private val clientFactory: OpenAiSubscriptionClientFactory,
    private val messageMapper: OpenAiSubscriptionMessageMapper,
) : AiRuntimeBackend {

    override fun supports(provider: AIProvider): Boolean = provider == AIProvider.OPEN_AI_SUBSCRIPTION

    override fun createRuntime(
        provider: AIProvider,
        modelName: String,
        projectPath: String?
    ): AiRuntime {
        return OpenAiSubscriptionRuntime(
            modelName = modelName,
            authService = authService,
            clientFactory = clientFactory,
            messageMapper = messageMapper
        )
    }
}

private class OpenAiSubscriptionRuntime(
    private val modelName: String,
    private val authService: OpenAiSubscriptionAuthService,
    private val clientFactory: OpenAiSubscriptionClientFactory,
    private val messageMapper: OpenAiSubscriptionMessageMapper,
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
        val params = messageMapper.toRequestParams(
            request = request,
            modelName = modelName,
            conversationKey = conversationKey,
            accountId = session.accountId
        )

        try {
            val client = clientFactory.create(session)
            try {
                client.responses().createStreaming(params).use { stream ->
                    val accumulator = ResponseAccumulator.create()
                    stream.stream().forEach { event ->
                        accumulator.accumulate(event)
                    }

                    messageMapper.toRuntimeResponse(
                        response = accumulator.response(),
                        conversationKey = conversationKey
                    )
                }
            } finally {
                client.close()
            }
        } catch (e: UnauthorizedException) {
            if (!retryOnUnauthorized) throw e

            authService.refreshTokens(session.refreshToken).getOrThrow()
            execute(request, retryOnUnauthorized = false)
        }
    }
}
