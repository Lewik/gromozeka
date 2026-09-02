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
            AiConnection.ClaudeCode(
                AiConnection.Id("claude-code"),
                "Claude Code",
                executionTarget = AiExecutionTarget.Worker("worker-1"),
            ).kind.provider,
        )
        assertEquals(
            AiConnection.Kind.CLAUDE_CODE,
            AiConnection.ClaudeCode(
                AiConnection.Id("claude-code"),
                "Claude Code",
                executionTarget = AiExecutionTarget.Worker("worker-1"),
            ).kind,
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
                executionTarget = AiExecutionTarget.Worker("worker-1"),
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
                executionTarget = AiExecutionTarget.Worker("worker-1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AiConnection.ClaudeCode(
                id = AiConnection.Id("claude-code"),
                displayName = "Claude Code",
                processIdleTtlMinutes = 0,
                executionTarget = AiExecutionTarget.Worker("worker-1"),
            )
        }
    }

    @Test
    fun claudeCodeConnectionRequiresWorkerExecutionTarget() {
        assertFailsWith<IllegalArgumentException> {
            AiConnection.ClaudeCode(
                id = AiConnection.Id("claude-code"),
                displayName = "Claude Code",
                executionTarget = AiExecutionTarget.Server,
            )
        }
    }

    @Test
    fun claudeCodeSettingsSurviveSerialization() {
        val connection: AiConnection = AiConnection.ClaudeCode(
            id = AiConnection.Id("claude-code"),
            displayName = "Claude Code",
            outputStyle = AiConnection.ClaudeCodeOutputStyle.CONCISE,
            maxCachedProcesses = 17,
            processIdleTtlMinutes = 95,
            executionTarget = AiExecutionTarget.Worker("worker-1"),
        )

        val restored = Json.decodeFromString<AiConnection>(
            Json.encodeToString(connection),
        ) as AiConnection.ClaudeCode

        assertEquals(AiConnection.ClaudeCodeOutputStyle.CONCISE, restored.outputStyle)
        assertEquals(17, restored.maxCachedProcesses)
        assertEquals(95, restored.processIdleTtlMinutes)
    }

    @Test
    fun claudeCodeOutputStyleDefaultsToGromozekaPromptBehavior() {
        val restored = Json.decodeFromString<AiConnection>(
            """
            {
              "connectionKind": "claude_code",
              "id": "claude-code",
              "displayName": "Claude Code",
              "executionTarget": {
                "executionTargetKind": "worker",
                "workerId": "worker-1"
              }
            }
            """.trimIndent(),
        ) as AiConnection.ClaudeCode

        assertEquals(null, restored.outputStyle)
    }

    @Test
    fun githubCopilotAllowsServerAndWorkerCliTargets() {
        val server = AiConnection.GitHubCopilot(
            id = AiConnection.Id("copilot-server"),
            displayName = "Copilot Server",
            executionTarget = AiExecutionTarget.Server,
        )
        val worker = AiConnection.GitHubCopilot(
            id = AiConnection.Id("copilot-worker"),
            displayName = "Copilot Worker",
            executionTarget = AiExecutionTarget.Worker("worker-1"),
        )

        assertEquals(AiExecutionTarget.Server, server.executionTarget)
        assertEquals(AiExecutionTarget.Worker("worker-1"), worker.executionTarget)
    }

    @Test
    fun githubCopilotWorkerRejectsPerUserTokenAuthentication() {
        assertFailsWith<IllegalArgumentException> {
            AiConnection.GitHubCopilot(
                id = AiConnection.Id("copilot-worker"),
                displayName = "Copilot Worker",
                authMode = AiConnection.GitHubCopilotAuthMode.PER_USER_TOKEN,
                executionTarget = AiExecutionTarget.Worker("worker-1"),
            )
        }
    }

    @Test
    fun githubCopilotWorkerTargetSurvivesSerialization() {
        val connection: AiConnection = AiConnection.GitHubCopilot(
            id = AiConnection.Id("copilot-worker"),
            displayName = "Copilot Worker",
            executablePath = "/opt/copilot/bin/copilot",
            executionTarget = AiExecutionTarget.Worker("worker-1"),
        )

        val restored = Json.decodeFromString<AiConnection>(Json.encodeToString(connection))

        assertEquals(connection, restored)
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
