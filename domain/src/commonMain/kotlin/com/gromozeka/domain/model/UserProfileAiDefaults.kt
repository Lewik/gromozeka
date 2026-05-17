package com.gromozeka.domain.model

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection

object UserProfileAiDefaults {
    val defaultSelection: AiRuntimeSelection = AiRuntimeSelection(
        AiModelConfiguration.Id("openai-subscription-gpt-5.5"),
    )

    fun aiSettings(): UserProfile.AiSettings = UserProfile.AiSettings(
        defaultSelection = defaultSelection,
        connections = connections(),
        modelConfigurations = modelConfigurations(),
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
            roles = setOf(
                AiModelConfiguration.Role.CHAT,
                AiModelConfiguration.Role.MEMORY,
                AiModelConfiguration.Role.TRANSLATION,
            ),
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("anthropic-sonnet-4.7"),
            connectionId = AiConnection.Id("anthropic-direct"),
            providerModelId = "claude-sonnet-4-7",
            displayName = "Claude Sonnet 4.7",
            roles = setOf(
                AiModelConfiguration.Role.CHAT,
                AiModelConfiguration.Role.MEMORY,
                AiModelConfiguration.Role.TRANSLATION,
            ),
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("bedrock-sonnet-4"),
            connectionId = AiConnection.Id("anthropic-bedrock"),
            providerModelId = "anthropic.claude-sonnet-4-20250514-v1:0",
            displayName = "Claude Sonnet 4 via Bedrock",
            roles = setOf(
                AiModelConfiguration.Role.CHAT,
                AiModelConfiguration.Role.MEMORY,
                AiModelConfiguration.Role.TRANSLATION,
            ),
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.XML_INLINE,
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-api-gpt-4o-mini"),
            connectionId = AiConnection.Id("openai-api"),
            providerModelId = "gpt-4o-mini",
            displayName = "GPT-4o mini API",
            roles = setOf(
                AiModelConfiguration.Role.CHAT,
                AiModelConfiguration.Role.MEMORY,
                AiModelConfiguration.Role.TRANSLATION,
            ),
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-api-gpt-4o-transcribe"),
            connectionId = AiConnection.Id("openai-api"),
            providerModelId = "gpt-4o-transcribe",
            displayName = "OpenAI GPT-4o transcribe",
            roles = setOf(AiModelConfiguration.Role.SPEECH_TO_TEXT),
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-api-gpt-4o-mini-tts"),
            connectionId = AiConnection.Id("openai-api"),
            providerModelId = "gpt-4o-mini-tts",
            displayName = "OpenAI GPT-4o mini TTS",
            roles = setOf(AiModelConfiguration.Role.TEXT_TO_SPEECH),
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        ),
    )
}
