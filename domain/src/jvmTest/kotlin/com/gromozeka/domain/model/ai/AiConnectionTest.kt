package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AiProvider
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    @Test
    fun claudeCodeConnectionRequiresPositiveProcessCacheSettings() {
        assertFailsWith<IllegalArgumentException> {
            AiConnection.ClaudeCode(
                id = AiConnection.Id("claude-code"),
                displayName = "Claude Code",
                maxCachedProcesses = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AiConnection.ClaudeCode(
                id = AiConnection.Id("claude-code"),
                displayName = "Claude Code",
                processIdleTtlMinutes = 0,
            )
        }
    }

    @Test
    fun claudeCodeProcessCacheSettingsSurviveSerialization() {
        val connection: AiConnection = AiConnection.ClaudeCode(
            id = AiConnection.Id("claude-code"),
            displayName = "Claude Code",
            maxCachedProcesses = 17,
            processIdleTtlMinutes = 95,
        )

        val restored = Json.decodeFromString<AiConnection>(
            Json.encodeToString(connection),
        ) as AiConnection.ClaudeCode

        assertEquals(17, restored.maxCachedProcesses)
        assertEquals(95, restored.processIdleTtlMinutes)
    }

    @Test
    fun executionTargetSurvivesSerialization() {
        val connection: AiConnection = AiConnection.OpenAiApi(
            id = AiConnection.Id("worker-openai"),
            displayName = "Worker OpenAI",
            executionTarget = AiExecutionTarget.Worker("macbook-primary"),
        )

        val restored = Json.decodeFromString<AiConnection>(
            Json.encodeToString(connection),
        )

        assertEquals(AiExecutionTarget.Worker("macbook-primary"), restored.executionTarget)
    }

    @Test
    fun openAiHostedWebSearchSettingSurvivesSerialization() {
        val apiConnection: AiConnection = AiConnection.OpenAiApi(
            id = AiConnection.Id("openai-api"),
            displayName = "OpenAI API",
            webSearchEnabled = false,
        )
        val subscriptionConnection: AiConnection = AiConnection.OpenAiSubscription(
            id = AiConnection.Id("openai-subscription"),
            displayName = "OpenAI Subscription",
            webSearchEnabled = false,
        )

        val restoredApi = Json.decodeFromString<AiConnection>(Json.encodeToString(apiConnection))
        val restoredSubscription = Json.decodeFromString<AiConnection>(Json.encodeToString(subscriptionConnection))

        assertEquals(false, (restoredApi as AiConnection.OpenAiApi).webSearchEnabled)
        assertEquals(false, (restoredSubscription as AiConnection.OpenAiSubscription).webSearchEnabled)
    }

    @Test
    fun workerExecutionTargetRequiresWorkerId() {
        assertFailsWith<IllegalArgumentException> {
            AiExecutionTarget.Worker(" ")
        }
    }
}
