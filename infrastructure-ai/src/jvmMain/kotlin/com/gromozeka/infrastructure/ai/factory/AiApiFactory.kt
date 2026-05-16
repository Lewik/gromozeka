package com.gromozeka.infrastructure.ai.factory

import com.google.genai.Client
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.service.SettingsProvider
import klog.KLoggers
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.stereotype.Component

@Component
class AiApiFactory(
    private val settingsProvider: SettingsProvider,
) {
    private val log = KLoggers.logger(this)

    fun createOllamaApi(connection: AiConnection): OllamaApi {
        val baseUrl = (connection as? AiConnection.HttpAiConnection)?.baseUrl ?: "http://localhost:11434"
        log.info("Creating Ollama API with base URL: $baseUrl")
        return OllamaApi.builder()
            .baseUrl(baseUrl)
            .build()
    }

    fun createOpenAiApi(connection: AiConnection): OpenAiApi {
        val apiKey = settingsProvider.resolveSecret((connection as? AiConnection.ApiKeyAiConnection)?.apiKey)
        val baseUrl = (connection as? AiConnection.HttpAiConnection)?.baseUrl
        return when {
            apiKey != null -> {
                log.info("Creating OpenAI API with API Key authentication")
                OpenAiApi.builder().apply {
                    apiKey(apiKey)
                    baseUrl?.takeIf { it.isNotBlank() }?.let(::baseUrl)
                }.build()
            }

            else -> {
                log.warn("No OpenAI API key configured")
                OpenAiApi.builder().apply {
                    apiKey("")
                    baseUrl?.takeIf { it.isNotBlank() }?.let(::baseUrl)
                }.build()
            }
        }
    }

    fun createGeminiClient(): Client? {
        val credentialsPath = java.io.File(settingsProvider.homeDirectory, "google-credentials.json")
        return if (credentialsPath.exists()) {
            log.info("Creating Gemini client with credentials from: ${credentialsPath.absolutePath}")

            val credentialsJson = com.google.gson.JsonParser.parseString(
                credentialsPath.readText()
            ).asJsonObject
            val projectId = credentialsJson.get("project_id").asString

            val credentials = com.google.auth.oauth2.GoogleCredentials.fromStream(
                java.io.FileInputStream(credentialsPath)
            ).createScoped("https://www.googleapis.com/auth/cloud-platform")

            Client.builder()
                .project(projectId)
                .location("us-central1")
                .vertexAI(true)
                .credentials(credentials)
                .build()
        } else {
            log.warn("Gemini credentials not found at: ${credentialsPath.absolutePath}")
            null
        }
    }
}
