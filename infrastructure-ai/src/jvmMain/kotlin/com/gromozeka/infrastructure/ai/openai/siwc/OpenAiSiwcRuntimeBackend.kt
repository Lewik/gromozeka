package com.gromozeka.infrastructure.ai.openai.siwc

import com.gromozeka.domain.model.ai.AiCallFailure
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiHttpSettings
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.service.AiCallAdmission
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.tool.ProviderNativeTool
import com.gromozeka.infrastructure.ai.openai.OpenAiResponsesContinuations
import com.gromozeka.infrastructure.ai.openai.OpenAiResponsesProvider
import com.gromozeka.infrastructure.ai.openai.OpenAiResponsesRequestFeatures
import com.gromozeka.infrastructure.ai.openai.OpenAiResponsesSdkRuntime
import com.gromozeka.infrastructure.ai.openai.OpenAiSdkClientFactory
import com.gromozeka.infrastructure.ai.runtime.AiRuntimeBackend
import com.gromozeka.infrastructure.ai.runtime.ModelGenerationPolicy
import com.gromozeka.infrastructure.ai.runtime.SdkHttpCallExecutor
import com.gromozeka.infrastructure.ai.runtime.SdkInferenceClient
import com.openai.client.OpenAIClient
import org.springframework.stereotype.Service

@Service
internal class OpenAiSiwcRuntimeBackend(
    private val clientFactory: OpenAiSdkClientFactory,
    private val accessTokens: OpenAiSiwcAccessTokenProvider,
    private val admission: AiCallAdmission,
    private val continuations: OpenAiResponsesContinuations,
) : AiRuntimeBackend {
    override fun supports(connectionKind: AiConnection.Kind): Boolean = connectionKind == AiConnection.Kind.OPENAI_SIWC

    override fun capabilities(connection: AiConnection, modelConfiguration: AiModelConfiguration) =
        AiRuntimeCapabilities()

    override fun createRuntime(runtime: ResolvedAiRuntime, workspaceRootPath: String?): AiRuntime {
        val connection = runtime.connection as? AiConnection.OpenAiSiwc
            ?: error("ChatGPT SIWC runtime requires an OPENAI_SIWC connection")
        val modelConfiguration = runtime.modelConfiguration
        val executor = SdkHttpCallExecutor(
            connection = connection,
            timeoutSeconds = modelConfiguration.defaultParameters.timeoutSeconds,
            maxAttempts = HTTP_SETTINGS.maxAttempts,
            admission = admission,
            modelConfiguration = modelConfiguration,
            failureMapper = ::mapResourceFailure,
        ) { deadline ->
            clientFactory.createInferenceClient(
                connection = connection,
                baseUrl = RESPONSES_BASE_URL,
                token = try {
                    accessTokens.validAccessToken(deadline)
                } catch (error: OpenAiSiwcException) {
                    throw error.toAiCallException()
                },
                settings = HTTP_SETTINGS,
                deadlineNanos = deadline,
            )
        }
        return OpenAiResponsesSdkRuntime(
            connectionId = connection.id.value,
            modelConfigurationId = modelConfiguration.id.value,
            modelName = modelConfiguration.providerModelId,
            webSearchEnabled = connection.webSearchEnabled,
            executor = executor,
            provider = PROVIDER,
            continuations = continuations,
            continuationSettingsIdentity = null,
            quotaEnabled = modelConfiguration.tpm != null,
            generation = ModelGenerationPolicy(runtime.modelSpec),
        )
    }

    private fun mapResourceFailure(
        session: SdkInferenceClient<OpenAIClient>,
        failure: AiCallFailure,
        cause: Throwable,
        deadlineNanos: Long,
    ): AiCallFailure {
        val error = cause.openAiSiwcResourceError(
            failure.httpStatus,
            session.transport.responseHeader("x-request-id"),
        ) ?: return failure
        val mapped = failure.withOpenAiSiwcResourceError(error)
        if (mapped.outcome == AiCallFailure.Outcome.REJECTED) {
            session.identity?.credentialFingerprint?.let {
                accessTokens.resourceRejectedAccessToken(it, error, deadlineNanos)
            }
        }
        return mapped
    }

    private companion object {
        const val RESPONSES_BASE_URL = "https://api.openai.com/v1"
        val HTTP_SETTINGS = AiHttpSettings(maxAttempts = 1)
        val PROVIDER = OpenAiResponsesProvider(
            providerScope = AiConnection.Kind.OPENAI_SIWC.name,
            webSearchTool = ProviderNativeTool.OPENAI_SIWC_WEB_SEARCH,
            functionNamespace = "functions",
            supportsNativeCompaction = false,
            supportsDetachedNativeCompaction = false,
            supportsProviderTokenCount = false,
            streaming = true,
            requestFeatures = OpenAiResponsesRequestFeatures(maxOutputTokens = false),
        )
    }
}

internal fun AiCallFailure.withOpenAiSiwcResourceError(error: OpenAiSiwcResourceError): AiCallFailure {
    val (mappedKind, mappedOutcome) = when (error.code) {
        "subscription_sharing_usage_limit_exceeded" ->
            AiCallFailure.Kind.RATE_LIMITED to AiCallFailure.Outcome.REJECTED
        "subscription_sharing_usage_unavailable",
        "subscription_sharing_user_unavailable",
        -> AiCallFailure.Kind.TRANSPORT to AiCallFailure.Outcome.UNKNOWN
        "subscription_sharing_invalid_user" ->
            AiCallFailure.Kind.AUTHENTICATION to AiCallFailure.Outcome.REJECTED
        "subscription_sharing_user_not_eligible",
        "chatpass_v2_scope_not_authorized",
        "chatpass_v2_invalid_authorization_context",
        -> AiCallFailure.Kind.ACCESS_DENIED to AiCallFailure.Outcome.REJECTED
        "subscription_sharing_unsupported_capability",
        "subscription_sharing_route_not_supported",
        -> AiCallFailure.Kind.CONFIGURATION to AiCallFailure.Outcome.REJECTED
        else -> kind to outcome
    }
    return copy(
        kind = mappedKind,
        outcome = mappedOutcome,
        providerErrorCode = error.code,
        providerErrorParameter = error.parameter,
        providerRequestId = error.requestId,
    )
}
