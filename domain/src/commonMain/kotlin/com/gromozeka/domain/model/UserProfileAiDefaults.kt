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
        AiConnection.ClaudeCode(
            id = AiConnection.Id("claude-code"),
            displayName = "Claude Code",
        ),
        AiConnection.GeminiApi(
            id = AiConnection.Id("gemini"),
            displayName = "Gemini",
            apiKey = SecretRef.EnvironmentVariable("GEMINI_API_KEY"),
        ),
        AiConnection.Ollama(
            id = AiConnection.Id("ollama-local"),
            displayName = "Local Ollama",
            baseUrl = "http://localhost:11434",
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
            roles = setOf(AiModelConfiguration.Role.CHAT, AiModelConfiguration.Role.MEMORY),
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("bedrock-sonnet-4"),
            connectionId = AiConnection.Id("anthropic-bedrock"),
            providerModelId = "anthropic.claude-sonnet-4-20250514-v1:0",
            displayName = "Claude Sonnet 4 via Bedrock",
            roles = setOf(AiModelConfiguration.Role.CHAT, AiModelConfiguration.Role.MEMORY),
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-api-gpt-4o-mini"),
            connectionId = AiConnection.Id("openai-api"),
            providerModelId = "gpt-4o-mini",
            displayName = "GPT-4o mini API",
            roles = setOf(AiModelConfiguration.Role.CHAT),
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-api-text-embedding-3-large"),
            connectionId = AiConnection.Id("openai-api"),
            providerModelId = "text-embedding-3-large",
            displayName = "OpenAI text-embedding-3-large",
            roles = setOf(AiModelConfiguration.Role.EMBEDDINGS),
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-api-gpt-4o-transcribe"),
            connectionId = AiConnection.Id("openai-api"),
            providerModelId = "gpt-4o-transcribe",
            displayName = "OpenAI GPT-4o transcribe",
            roles = setOf(AiModelConfiguration.Role.SPEECH_TO_TEXT),
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-api-gpt-4o-mini-tts"),
            connectionId = AiConnection.Id("openai-api"),
            providerModelId = "gpt-4o-mini-tts",
            displayName = "OpenAI GPT-4o mini TTS",
            roles = setOf(AiModelConfiguration.Role.TEXT_TO_SPEECH),
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("claude-code-sonnet"),
            connectionId = AiConnection.Id("claude-code"),
            providerModelId = "claude-sonnet-4-5",
            displayName = "Claude Code Sonnet",
            roles = setOf(AiModelConfiguration.Role.CHAT),
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("gemini-flash"),
            connectionId = AiConnection.Id("gemini"),
            providerModelId = "gemini-2.0-flash-exp",
            displayName = "Gemini 2.0 Flash",
            roles = setOf(AiModelConfiguration.Role.CHAT),
        ),
        AiModelConfiguration(
            id = AiModelConfiguration.Id("ollama-llama3.2"),
            connectionId = AiConnection.Id("ollama-local"),
            providerModelId = "llama3.2",
            displayName = "Ollama llama3.2",
            roles = setOf(AiModelConfiguration.Role.CHAT),
        ),
    )
}
