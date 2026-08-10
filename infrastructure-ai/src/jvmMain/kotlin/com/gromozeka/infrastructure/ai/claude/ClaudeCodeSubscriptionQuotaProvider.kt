package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaRequest
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaSnapshot
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaWindow
import com.gromozeka.domain.service.DirectAiSubscriptionQuotaProvider
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@Service
internal class ClaudeCodeSubscriptionQuotaProvider : DirectAiSubscriptionQuotaProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder().build()
    private val versions = ConcurrentHashMap<String, String>()

    override fun supports(request: AiSubscriptionQuotaRequest): Boolean =
        request.connection.kind == AiConnection.Kind.CLAUDE_CODE

    override suspend fun read(request: AiSubscriptionQuotaRequest): AiSubscriptionQuotaSnapshot =
        withContext(Dispatchers.IO) {
            val connection = request.connection as? AiConnection.ClaudeCode
                ?: error("Claude Code quota requires a Claude Code connection")
            val credentials = readCredentials()
            val accessToken = credentials["claudeAiOauth"]
                ?.jsonObject
                ?.get("accessToken")
                ?.jsonPrimitive
                ?.content
                ?.takeIf(String::isNotBlank)
                ?: error("Claude Code OAuth access token was not found")
            val version = versions.computeIfAbsent(connection.executablePath, ::readVersion)
            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(USAGE_URL))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer $accessToken")
                .header("anthropic-beta", OAUTH_BETA)
                .header("Accept", "application/json")
                .header("User-Agent", "claude-code/$version")
                .GET()
                .build()
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            check(response.statusCode() in 200..299) {
                "Claude Code quota request failed: HTTP ${response.statusCode()}"
            }
            ClaudeCodeSubscriptionQuotaParser.parse(response.body(), connection, request.modelId)
        }

    private fun readCredentials(): JsonObject {
        val raw = if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
            runProcess(listOf("security", "find-generic-password", "-s", "Claude Code-credentials", "-w"))
        } else {
            val configDirectory = System.getenv("CLAUDE_CONFIG_DIR")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".claude")
            Files.readString(configDirectory.resolve(".credentials.json"))
        }
        return json.parseToJsonElement(raw).jsonObject
    }

    private fun readVersion(executablePath: String): String =
        runProcess(listOf(executablePath, "--version"))
            .substringBefore(' ')
            .trim()
            .takeIf(String::isNotBlank)
            ?: error("Claude Code did not report its version")

    private fun runProcess(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        check(process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Timed out running ${command.first()}"
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.exitValue() == 0) {
            "${command.first()} failed with exit code ${process.exitValue()}"
        }
        return output
    }

    private companion object {
        const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
        const val OAUTH_BETA = "oauth-2025-04-20"
        const val REQUEST_TIMEOUT_SECONDS = 15L
        const val PROCESS_TIMEOUT_SECONDS = 10L
    }
}

internal object ClaudeCodeSubscriptionQuotaParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        body: String,
        connection: AiConnection.ClaudeCode,
        modelId: String?,
        observedAt: Instant = Clock.System.now(),
    ): AiSubscriptionQuotaSnapshot {
        val payload = json.parseToJsonElement(body).jsonObject
        val normalizedModel = modelId?.normalizeModelName()
        val windows = buildList {
            payload.objectOrNull("five_hour")?.let { add(it.toWindow("five_hour", "5 hour", 5.hours)) }
            payload.objectOrNull("seven_day")?.let { add(it.toWindow("seven_day", "7 day", 7.days)) }
            MODEL_WINDOWS.forEach { definition ->
                if (normalizedModel == null || normalizedModel.matchesNormalized(definition.modelName)) {
                    payload.objectOrNull(definition.id)?.let {
                        add(it.toWindow(definition.id, definition.displayName, 7.days))
                    }
                }
            }
            payload["limits"].arrayOrEmpty().forEachIndexed { index, element ->
                val limit = element as? JsonObject ?: return@forEachIndexed
                if (limit["kind"]?.jsonPrimitive?.content != "weekly_scoped") return@forEachIndexed
                val displayName = limit.objectOrNull("scope")
                    ?.objectOrNull("model")
                    ?.get("display_name")
                    ?.jsonPrimitive
                    ?.content
                    ?.takeIf(String::isNotBlank)
                    ?: return@forEachIndexed
                if (normalizedModel != null && !normalizedModel.matchesNormalized(displayName.normalizeModelName())) {
                    return@forEachIndexed
                }
                add(limit.toWindow("weekly_scoped_$index", displayName, 7.days, "percent"))
            }
        }
        return AiSubscriptionQuotaSnapshot(
            connectionId = connection.id,
            observedAt = observedAt,
            windows = windows,
        )
    }

    private fun JsonObject.toWindow(
        id: String,
        displayName: String,
        duration: kotlin.time.Duration,
        usageField: String = "utilization",
    ): AiSubscriptionQuotaWindow {
        val usedPercent = this[usageField]?.jsonPrimitive?.doubleOrNull
            ?: error("Claude Code quota $id did not report usage")
        val resetsAt = this["resets_at"]?.jsonPrimitive?.content?.let(Instant::parse)
            ?: error("Claude Code quota $id did not report a reset time")
        return AiSubscriptionQuotaWindow(
            id = id,
            displayName = displayName,
            usedPercent = usedPercent.coerceIn(0.0, 100.0),
            startedAt = resetsAt - duration,
            resetsAt = resetsAt,
        )
    }

    private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonElement?.arrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

    private fun String.normalizeModelName(): String =
        lowercase().filter(Char::isLetterOrDigit).removePrefix("claude")

    private fun String.matchesNormalized(other: String): Boolean =
        contains(other) || other.contains(this)

    private data class ModelWindow(
        val id: String,
        val displayName: String,
        val modelName: String,
    )

    private val MODEL_WINDOWS = listOf(
        ModelWindow("seven_day_opus", "Opus 7 day", "opus"),
        ModelWindow("seven_day_sonnet", "Sonnet 7 day", "sonnet"),
    )
}
