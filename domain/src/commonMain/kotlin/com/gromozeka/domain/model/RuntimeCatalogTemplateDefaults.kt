package com.gromozeka.domain.model

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningDisplay
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection

object RuntimeCatalogTemplateDefaults {
    val defaultAgentId = AgentDefinition.Id("global:default-gromozeka")

    fun catalog(modelSpecs: List<AiModelSpec>): AiCatalog = AiCatalog(
        connections = connections(),
        modelSpecs = modelSpecs,
        modelConfigurations = modelConfigurations(),
        runtimeAssignments = runtimeAssignments(),
        defaultAgentId = defaultAgentId,
    )

    fun runtimeAssignments(): List<AiRuntimeAssignment> = listOf(
        assignment(AiRuntimeAssignment.Purpose.DEFAULT_CHAT, "openai-subscription-gpt-5.6-luna"),
        assignment(AiRuntimeAssignment.Purpose.MESSAGE_SQUASH, "openai-subscription-gpt-5.5"),
        assignment(AiRuntimeAssignment.Purpose.MEMORY_READ, "openai-subscription-gpt-5.5"),
        assignment(AiRuntimeAssignment.Purpose.MEMORY_WRITE, "openai-subscription-gpt-5.5"),
        assignment(AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE, "openai-subscription-gpt-5.5"),
        assignment(AiRuntimeAssignment.Purpose.MEMORY_EMBEDDINGS, "openai-api-text-embedding-3-large"),
        assignment(AiRuntimeAssignment.Purpose.LIVE_TRANSCRIPT_STABILIZER, "openai-subscription-gpt-5.5"),
        assignment(AiRuntimeAssignment.Purpose.LIVE_TRANSLATION, "openai-subscription-gpt-5.5"),
        assignment(AiRuntimeAssignment.Purpose.SPEECH_TO_TEXT, "openai-api-gpt-4o-transcribe"),
        assignment(AiRuntimeAssignment.Purpose.TEXT_TO_SPEECH, "openai-api-gpt-4o-mini-tts"),
    )

    fun connections(): List<AiConnection> = listOf(
        AiConnection.OpenAiSubscription(
            id = AiConnection.Id("openai-subscription"),
            displayName = "OpenAI Subscription",
            enabled = false,
        ),
        AiConnection.AnthropicApi(
            id = AiConnection.Id("anthropic-direct"),
            displayName = "Anthropic",
            baseUrl = "https://api.anthropic.com",
            apiKey = SecretRef.EnvironmentVariable("ANTHROPIC_API_KEY"),
        ),
        AiConnection.AnthropicBedrock(
            id = AiConnection.Id("anthropic-bedrock"),
            displayName = "Anthropic via Bedrock",
        ),
        AiConnection.ClaudeCode(
            id = AiConnection.Id("claude-code"),
            displayName = "Claude Code CLI",
        ),
        AiConnection.OpenAiApi(
            id = AiConnection.Id("openai-api"),
            displayName = "OpenAI API",
            enabled = false,
            apiKey = SecretRef.EnvironmentVariable("OPENAI_API_KEY"),
        ),
    )

    fun modelConfigurations(): List<AiModelConfiguration> = listOf(
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-subscription-gpt-5.5"),
            connectionId = AiConnection.Id("openai-subscription"),
            providerModelId = "gpt-5.5",
            displayName = "GPT-5.5 subscription",
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-subscription-gpt-5.6-sol"),
            connectionId = AiConnection.Id("openai-subscription"),
            providerModelId = "gpt-5.6-sol",
            displayName = "GPT-5.6 Sol subscription",
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-subscription-gpt-5.6-terra"),
            connectionId = AiConnection.Id("openai-subscription"),
            providerModelId = "gpt-5.6-terra",
            displayName = "GPT-5.6 Terra subscription",
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-subscription-gpt-5.6-luna"),
            connectionId = AiConnection.Id("openai-subscription"),
            providerModelId = "gpt-5.6-luna",
            displayName = "GPT-5.6 Luna subscription",
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("anthropic-opus-5"),
            connectionId = AiConnection.Id("anthropic-direct"),
            providerModelId = "claude-opus-5",
            displayName = "Claude Opus 5",
            defaultParameters = opus5DefaultParameters(),
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("anthropic-sonnet-4.7"),
            connectionId = AiConnection.Id("anthropic-direct"),
            providerModelId = "claude-sonnet-4-7",
            displayName = "Claude Sonnet 4.7",
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("bedrock-sonnet-4"),
            connectionId = AiConnection.Id("anthropic-bedrock"),
            providerModelId = "anthropic.claude-sonnet-4-20250514-v1:0",
            displayName = "Claude Sonnet 4 via Bedrock",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.XML_INLINE,
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("claude-code-sonnet"),
            connectionId = AiConnection.Id("claude-code"),
            providerModelId = "sonnet",
            displayName = "Claude Code Sonnet",
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("claude-code-opus-5"),
            connectionId = AiConnection.Id("claude-code"),
            providerModelId = "claude-opus-5",
            displayName = "Claude Code Opus 5",
            defaultParameters = opus5DefaultParameters(),
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("claude-code-haiku"),
            connectionId = AiConnection.Id("claude-code"),
            providerModelId = "haiku",
            displayName = "Claude Code Haiku",
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-api-gpt-4o-mini"),
            connectionId = AiConnection.Id("openai-api"),
            providerModelId = "gpt-4o-mini",
            displayName = "GPT-4o mini API",
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-api-text-embedding-3-large"),
            connectionId = AiConnection.Id("openai-api"),
            providerModelId = "text-embedding-3-large",
            displayName = "OpenAI text-embedding-3-large",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-api-text-embedding-3-small"),
            connectionId = AiConnection.Id("openai-api"),
            providerModelId = "text-embedding-3-small",
            displayName = "OpenAI text-embedding-3-small",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-api-gpt-4o-transcribe"),
            connectionId = AiConnection.Id("openai-api"),
            providerModelId = "gpt-4o-transcribe",
            displayName = "OpenAI GPT-4o transcribe",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-api-gpt-4o-mini-tts"),
            connectionId = AiConnection.Id("openai-api"),
            providerModelId = "gpt-4o-mini-tts",
            displayName = "OpenAI GPT-4o mini TTS",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        ),
    )

    private fun assignment(
        purpose: AiRuntimeAssignment.Purpose,
        modelConfigurationId: String,
    ): AiRuntimeAssignment =
        AiRuntimeAssignment(
            purpose = purpose,
            selection = AiRuntimeSelection(AiModelConfiguration.Id(modelConfigurationId)),
        )

    private fun opus5DefaultParameters(): AiModelConfiguration.DefaultParameters =
        AiModelConfiguration.DefaultParameters(
            reasoning = AiReasoningConfig(
                mode = AiReasoningMode.ADAPTIVE,
                effort = AiReasoningEffort.HIGH,
                display = AiReasoningDisplay.SUMMARIZED,
            )
        )

}
