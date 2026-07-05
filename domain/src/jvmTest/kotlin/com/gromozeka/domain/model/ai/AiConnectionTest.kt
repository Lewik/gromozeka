package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AiProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiConnectionTest {
    @Test
    fun separatesProviderFromConnectionKind() {
        assertEquals(
            AiProvider.OPENAI,
            AiConnection.OpenAiSubscription(AiConnection.Id("openai-sub"), "OpenAI").kind.provider,
        )
        assertEquals(
            AiConnection.Kind.OPENAI_SUBSCRIPTION,
            AiConnection.OpenAiSubscription(AiConnection.Id("openai-sub"), "OpenAI").kind,
        )

        assertEquals(
            AiProvider.ANTHROPIC,
            AiConnection.AnthropicBedrock(AiConnection.Id("bedrock"), "Bedrock").kind.provider,
        )
        assertEquals(
            AiConnection.Kind.ANTHROPIC_BEDROCK,
            AiConnection.AnthropicBedrock(AiConnection.Id("bedrock"), "Bedrock").kind,
        )
        assertEquals(
            AiProvider.ANTHROPIC,
            AiConnection.ClaudeCode(AiConnection.Id("claude-code"), "Claude Code").kind.provider,
        )
        assertEquals(
            AiConnection.Kind.CLAUDE_CODE,
            AiConnection.ClaudeCode(AiConnection.Id("claude-code"), "Claude Code").kind,
        )
    }

    @Test
    fun openAiCompatibleConnectionIsCustomProviderByDefault() {
        val connection = AiConnection.OpenAiCompatible(
            id = AiConnection.Id("openai-compatible"),
            displayName = "OpenAI-compatible endpoint",
            baseUrl = "http://localhost:1234",
        )

        assertEquals(AiProvider.CUSTOM, connection.kind.provider)
        assertEquals(AiConnection.Kind.OPENAI_COMPATIBLE, connection.kind)
    }

    @Test
    fun claudeCodeConnectionRequiresExecutablePath() {
        assertFailsWith<IllegalArgumentException> {
            AiConnection.ClaudeCode(
                id = AiConnection.Id("claude-code"),
                displayName = "Claude Code",
                executablePath = "",
            )
        }
    }
}
