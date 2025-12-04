package com.gromozeka.infrastructure.ai.factory

import com.google.genai.Client
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.oauth.OAuthService
import com.gromozeka.infrastructure.ai.oauth.OAuthConfigService
import klog.KLoggers
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.WebClient

@Component
class AiApiFactory(
    private val settingsProvider: SettingsProvider,
    private val oauthService: OAuthService,
    private val oauthConfigService: OAuthConfigService,
) {
    private val log = KLoggers.logger(this)

    fun createAnthropicApi(): AnthropicApi {
        val oauthConfig = oauthConfigService.getConfig()

        return when {
            oauthConfig?.enabled == true -> {
                require(oauthConfig.accessToken != null) {
                    "OAuth is enabled but access token is missing. Please authenticate first."
                }

                if (oauthConfig.expiresAt != null &&
                    oauthConfig.expiresAt < System.currentTimeMillis() &&
                    oauthConfig.refreshToken != null
                ) {
                    log.info("Access token expired, refreshing...")
                    kotlinx.coroutines.runBlocking {
                        oauthService.refreshTokens(oauthConfig.refreshToken)
                            .onSuccess { tokens ->
                                log.info("Successfully refreshed access tokens")
                            }
                            .onFailure { e ->
                                log.error(e) { "Failed to refresh access tokens" }
                                throw IllegalStateException("Failed to refresh OAuth token", e)
                            }
                    }
                }

                val currentConfig = oauthConfigService.getConfig()!!
                log.info("Creating Anthropic API with Bearer token authentication")

                val restClientBuilder = RestClient.builder()
                    .requestInterceptor { request, body, execution ->
                        request.headers.remove("x-api-key")
                        request.headers.set("Authorization", "Bearer ${currentConfig.accessToken}")
                        execution.execute(request, body)
                    }

                val webClientBuilder = WebClient.builder()
                    .filter { request, next ->
                        val newRequest = ClientRequest.from(request)
                            .headers { headers ->
                                headers.remove("x-api-key")
                                headers.set("Authorization", "Bearer ${currentConfig.accessToken}")
                            }
                            .build()
                        next.exchange(newRequest)
                    }

                AnthropicApi.builder()
                    .apiKey(currentConfig.accessToken)
                    .restClientBuilder(restClientBuilder)
                    .webClientBuilder(webClientBuilder)
                    .anthropicBetaFeatures(currentConfig.betaHeaders)
                    .build()
            }

            settingsProvider.anthropicApiKey != null -> {
                log.info("Creating Anthropic API with API Key authentication")
                AnthropicApi.builder()
                    .apiKey(settingsProvider.anthropicApiKey)
                    .build()
            }

            else -> {
                throw IllegalStateException(
                    "Anthropic API not configured. Please either:\n" +
                            "1. Enable OAuth authentication in Settings, or\n" +
                            "2. Provide an Anthropic API key in Settings"
                )
            }
        }
    }

    fun createOllamaApi(): OllamaApi {
        val baseUrl = settingsProvider.ollamaBaseUrl
        log.info("Creating Ollama API with base URL: $baseUrl")
        return OllamaApi.builder()
            .baseUrl(baseUrl)
            .build()
    }

    fun createOpenAiApi(): OpenAiApi {
        return when {
            settingsProvider.openAiApiKey != null -> {
                log.info("Creating OpenAI API with API Key authentication")
                OpenAiApi.builder()
                    .apiKey(settingsProvider.openAiApiKey)
                    .build()
            }

            else -> {
                log.warn("No OpenAI API key configured")
                OpenAiApi.builder()
                    .apiKey("")
                    .build()
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
