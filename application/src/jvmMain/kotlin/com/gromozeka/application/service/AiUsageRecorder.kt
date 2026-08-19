package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_TARGET_MESSAGE_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_THREAD_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlin.time.Clock
import kotlin.time.Instant
import org.springframework.stereotype.Service

@Service
class AiUsageRecorder(
    private val repository: TokenUsageStatisticsRepository,
) {
    private val log = KLoggers.logger(this)

    suspend fun record(
        runtime: ResolvedAiRuntime,
        request: AiRuntimeRequest,
        response: AiRuntimeResponse,
    ) {
        val usage = response.usage ?: return
        runCatching {
            val context = request.options.toolContext
            val conversationId = context.stringValue(TOOL_CONTEXT_CONVERSATION_ID)
                ?: request.messages.firstOrNull()?.conversationId?.value
            val price = AiUsagePriceCatalog.price(
                connectionKind = runtime.connection.kind,
                modelId = runtime.modelConfiguration.providerModelId,
                usage = usage,
                contextInputTokens = response.contextUsage?.inputTokens,
            )
            repository.save(
                TokenUsageStatistics(
                    id = TokenUsageStatistics.Id("ai-usage:${uuid7()}"),
                    userId = context.stringValue(TOOL_CONTEXT_USER_ID)?.let(User::Id),
                    projectId = context.stringValue(TOOL_CONTEXT_PROJECT_ID)?.let(Project::Id),
                    agentDefinitionId = context.stringValue(TOOL_CONTEXT_AGENT_DEFINITION_ID)?.let(AgentDefinition::Id),
                    conversationId = conversationId?.let(Conversation::Id),
                    threadId = context.stringValue(TOOL_CONTEXT_THREAD_ID)?.let(Conversation.Thread::Id),
                    lastMessageId = (
                        context.stringValue(TOOL_CONTEXT_TARGET_MESSAGE_ID)
                            ?: request.messages.lastOrNull()?.id?.value
                    )?.let(Conversation.Message::Id),
                    timestamp = Clock.System.now(),
                    promptTokens = usage.promptTokens,
                    completionTokens = usage.completionTokens,
                    cacheCreationTokens = usage.cacheCreationTokens,
                    cacheReadTokens = usage.cacheReadTokens,
                    thinkingTokens = usage.thinkingTokens,
                    provider = runtime.connection.kind.provider.name,
                    modelId = runtime.modelConfiguration.providerModelId,
                    runtimePurpose = request.options.usagePurpose ?: "UNSPECIFIED",
                    executionTarget = runtime.connection.executionTarget.reportKey(),
                    connectionKind = runtime.connection.kind.name,
                    connectionId = runtime.connection.id.value,
                    modelConfigurationId = runtime.modelConfiguration.id.value,
                    contextInputTokens = response.contextUsage?.inputTokens,
                    price = price,
                )
            )
        }.onFailure { error ->
            log.error(error) {
                "Failed to record AI usage: connection=${runtime.connection.id.value} " +
                    "model=${runtime.modelConfiguration.providerModelId} purpose=${request.options.usagePurpose}"
            }
        }
    }

    private fun Map<String, Any?>.stringValue(key: String): String? =
        (this[key] as? String)?.takeIf(String::isNotBlank)

    private fun AiExecutionTarget.reportKey(): String =
        when (this) {
            AiExecutionTarget.Server -> "SERVER"
            is AiExecutionTarget.Worker -> "WORKER:$workerId"
        }
}

internal object AiUsagePriceCatalog {
    private val effectiveAt = Instant.parse("2026-08-19T00:00:00Z")

    fun price(
        connectionKind: AiConnection.Kind,
        modelId: String,
        usage: AiUsage,
        contextInputTokens: Int?,
    ): TokenUsageStatistics.PriceSnapshot? {
        val rates = rates(connectionKind, modelId, contextInputTokens) ?: return null
        val estimatedCostNanoUsd = (
            usage.promptTokens.toLong() * rates.input +
                usage.cacheCreationTokens.toLong() * rates.cacheCreation +
                usage.cacheReadTokens.toLong() * rates.cacheRead +
                usage.totalOutputTokens.toLong() * rates.output
        ) / 1_000_000L
        return TokenUsageStatistics.PriceSnapshot(
            catalogVersion = "2026-08-19",
            effectiveAt = effectiveAt,
            inputNanoUsdPerMillion = rates.input,
            cacheCreationNanoUsdPerMillion = rates.cacheCreation,
            cacheReadNanoUsdPerMillion = rates.cacheRead,
            outputNanoUsdPerMillion = rates.output,
            estimatedCostNanoUsd = estimatedCostNanoUsd,
        )
    }

    private fun rates(
        connectionKind: AiConnection.Kind,
        modelId: String,
        contextInputTokens: Int?,
    ): Rates? = when (connectionKind) {
        AiConnection.Kind.OPENAI_API -> openAiRates(modelId, contextInputTokens)
        AiConnection.Kind.ANTHROPIC_API -> anthropicRates(modelId)
        else -> null
    }

    private fun openAiRates(modelId: String, contextInputTokens: Int?): Rates? {
        val longContext = (contextInputTokens ?: 0) > 272_000
        return when (modelId) {
            "gpt-5.6", "gpt-5.6-sol" -> if (longContext) {
                Rates(10_000_000_000, 12_500_000_000, 1_000_000_000, 45_000_000_000)
            } else {
                Rates(5_000_000_000, 6_250_000_000, 500_000_000, 30_000_000_000)
            }
            "gpt-5.6-terra" -> if (longContext) {
                Rates(4_000_000_000, 5_000_000_000, 400_000_000, 18_000_000_000)
            } else {
                Rates(2_000_000_000, 2_500_000_000, 200_000_000, 12_000_000_000)
            }
            "gpt-5.6-luna" -> if (longContext) {
                Rates(400_000_000, 500_000_000, 40_000_000, 1_800_000_000)
            } else {
                Rates(200_000_000, 250_000_000, 20_000_000, 1_200_000_000)
            }
            else -> null
        }
    }

    private fun anthropicRates(modelId: String): Rates? =
        when (modelId) {
            "claude-opus-5" -> Rates(5_000_000_000, 6_250_000_000, 500_000_000, 25_000_000_000)
            "claude-sonnet-5" -> Rates(2_000_000_000, 2_500_000_000, 200_000_000, 10_000_000_000)
            "claude-fable-5", "claude-mythos-5" ->
                Rates(10_000_000_000, 12_500_000_000, 1_000_000_000, 50_000_000_000)
            else -> null
        }

    private data class Rates(
        val input: Long,
        val cacheCreation: Long,
        val cacheRead: Long,
        val output: Long,
    )
}
