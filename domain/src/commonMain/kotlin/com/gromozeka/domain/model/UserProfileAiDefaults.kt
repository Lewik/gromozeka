package com.gromozeka.domain.model

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection

object UserProfileAiDefaults {
    fun aiSettings(): UserProfile.AiSettings = UserProfile.AiSettings(
        connections = connections(),
        modelSpecs = modelSpecs(),
        modelConfigurations = modelConfigurations(),
        runtimeAssignments = runtimeAssignments(),
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
            id = AiModelConfiguration.Id("claude-code-opus"),
            connectionId = AiConnection.Id("claude-code"),
            providerModelId = "opus",
            displayName = "Claude Code Opus",
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

    fun modelSpecs(): List<AiModelSpec> = listOf(
        textGenerationSpec(AiProvider.OPENAI, "gpt-5.6-sol", contextWindowTokens = 372_000),
        textGenerationSpec(AiProvider.OPENAI, "gpt-5.6-terra", contextWindowTokens = 372_000),
        textGenerationSpec(AiProvider.OPENAI, "gpt-5.6-luna", contextWindowTokens = 372_000),
        textGenerationSpec(AiProvider.OPENAI, "gpt-5.5", contextWindowTokens = 272_000, maxOutputTokens = 128_000),
        textGenerationSpec(AiProvider.OPENAI, "gpt-5.4", contextWindowTokens = 400_000, maxOutputTokens = 128_000),
        textGenerationSpec(AiProvider.OPENAI, "gpt-5.4-mini", contextWindowTokens = 400_000, maxOutputTokens = 128_000),
        textGenerationSpec(AiProvider.OPENAI, "gpt-5.2", contextWindowTokens = 400_000),
        textGenerationSpec(AiProvider.OPENAI, "gpt-4o", contextWindowTokens = 128_000),
        textGenerationSpec(AiProvider.OPENAI, "gpt-4o-mini", contextWindowTokens = 128_000),
        AiModelSpec(
            id = "text-embedding-3-large",
            provider = AiProvider.OPENAI,
            capabilities = setOf(AiModelCapability.EMBEDDINGS),
            limits = AiModelSpec.Limits(
                embeddings = AiModelSpec.Limits.Embeddings(dimensions = 3_072, maxInputTokens = 8_191)
            ),
        ),
        AiModelSpec(
            id = "text-embedding-3-small",
            provider = AiProvider.OPENAI,
            capabilities = setOf(AiModelCapability.EMBEDDINGS),
            limits = AiModelSpec.Limits(
                embeddings = AiModelSpec.Limits.Embeddings(dimensions = 1_536, maxInputTokens = 8_191)
            ),
        ),
        AiModelSpec(
            id = "gpt-4o-transcribe",
            provider = AiProvider.OPENAI,
            capabilities = setOf(AiModelCapability.SPEECH_TO_TEXT),
        ),
        AiModelSpec(
            id = "gpt-4o-mini-tts",
            provider = AiProvider.OPENAI,
            capabilities = setOf(AiModelCapability.TEXT_TO_SPEECH),
        ),
        textGenerationSpec(AiProvider.ANTHROPIC, "claude-sonnet-4-7", contextWindowTokens = 1_000_000),
        textGenerationSpec(AiProvider.ANTHROPIC, "anthropic.claude-sonnet-4-20250514-v1:0", contextWindowTokens = 200_000),
        textGenerationSpec(AiProvider.ANTHROPIC, "claude-sonnet-4-5", contextWindowTokens = 200_000),
        textGenerationSpec(AiProvider.ANTHROPIC, "sonnet", contextWindowTokens = 1_000_000),
        textGenerationSpec(AiProvider.ANTHROPIC, "opus", contextWindowTokens = 1_000_000),
        textGenerationSpec(AiProvider.ANTHROPIC, "haiku", contextWindowTokens = 200_000),
        textGenerationSpec(AiProvider.GOOGLE, "gemini-2.0-flash-exp", contextWindowTokens = 1_000_000),
        textGenerationSpec(AiProvider.OLLAMA, "llama3.2", contextWindowTokens = 4_096),
    )

    private fun assignment(
        purpose: AiRuntimeAssignment.Purpose,
        modelConfigurationId: String,
    ): AiRuntimeAssignment =
        AiRuntimeAssignment(
            purpose = purpose,
            selection = AiRuntimeSelection(AiModelConfiguration.Id(modelConfigurationId)),
        )

    private fun textGenerationSpec(
        provider: AiProvider,
        id: String,
        contextWindowTokens: Int,
        maxOutputTokens: Int? = null,
    ): AiModelSpec =
        AiModelSpec(
            id = id,
            provider = provider,
            capabilities = setOf(
                AiModelCapability.TEXT_GENERATION,
                AiModelCapability.STRUCTURED_OUTPUT,
                AiModelCapability.TOOL_CALLING,
            ),
            limits = AiModelSpec.Limits(
                textGeneration = AiModelSpec.Limits.TextGeneration(
                    contextWindowTokens = contextWindowTokens,
                    maxOutputTokens = maxOutputTokens,
                    autoCompaction = AiModelSpec.AutoCompaction.Percent(80),
                )
            ),
        )
}
