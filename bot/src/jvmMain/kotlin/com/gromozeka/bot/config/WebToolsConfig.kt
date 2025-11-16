package com.gromozeka.bot.config

import com.gromozeka.bot.services.SettingsService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.function.Function

@Configuration
class WebToolsConfig(
    private val settingsService: SettingsService
) {
    private val logger = LoggerFactory.getLogger(WebToolsConfig::class.java)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Bean
    fun braveWebSearchTool(): ToolCallback? {
        val settings = settingsService.settingsFlow.value
        if (!settings.enableBraveSearch || settings.braveApiKey.isNullOrBlank()) {
            logger.info("Brave Web Search disabled (enableBraveSearch=${settings.enableBraveSearch}, apiKey present=${!settings.braveApiKey.isNullOrBlank()})")
            return null
        }

        val function = Function { request: BraveWebSearchRequest ->
            try {
                val url = buildString {
                    append("https://api.search.brave.com/res/v1/web/search")
                    append("?q=").append(URLEncoder.encode(request.query, "UTF-8"))
                    append("&count=").append(request.count ?: 10)
                    request.offset?.let { append("&offset=").append(it) }
                }

                val httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", settings.braveApiKey)
                    .GET()
                    .build()

                logger.debug("Brave Web Search: ${request.query} (count=${request.count ?: 10})")
                val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

                when (response.statusCode()) {
                    200 -> {
                        val parsed = json.decodeFromString<BraveSearchResponse>(response.body())
                        BraveWebSearchResponse(
                            success = true,
                            results = parsed.web?.results?.take(request.count ?: 10)?.map {
                                BraveResult(
                                    title = it.title,
                                    url = it.url,
                                    description = it.description
                                )
                            } ?: emptyList(),
                            error = null
                        )
                    }
                    else -> {
                        logger.error("Brave API error: ${response.statusCode()} - ${response.body()}")
                        BraveWebSearchResponse(
                            success = false,
                            results = emptyList(),
                            error = "HTTP ${response.statusCode()}: ${response.body().take(200)}"
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Brave Web Search failed", e)
                BraveWebSearchResponse(
                    success = false,
                    results = emptyList(),
                    error = "Error: ${e.message}"
                )
            }
        }

        return FunctionToolCallback.builder("brave_web_search", function)
            .description("""
                Performs a web search using the Brave Search API, ideal for general queries, news, articles, and online content. Use this for broad information gathering, recent events, or when you need diverse web sources. Supports pagination, content filtering, and freshness controls. Maximum 20 results per request, with offset for pagination. 
            """.trimIndent())
            .inputType(object : ParameterizedTypeReference<BraveWebSearchRequest>() {})
            .build()
    }

    @Bean
    fun braveLocalSearchTool(): ToolCallback? {
        val settings = settingsService.settingsFlow.value
        if (!settings.enableBraveSearch || settings.braveApiKey.isNullOrBlank()) {
            return null
        }

        val function = Function { request: BraveLocalSearchRequest ->
            try {
                val url = buildString {
                    append("https://api.search.brave.com/res/v1/local/search")
                    append("?q=").append(URLEncoder.encode(request.query, "UTF-8"))
                    append("&count=").append(request.count ?: 5)
                }

                val httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", settings.braveApiKey)
                    .GET()
                    .build()

                logger.debug("Brave Local Search: ${request.query} (count=${request.count ?: 5})")
                val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

                when (response.statusCode()) {
                    200 -> {
                        val parsed = json.decodeFromString<BraveLocalResponse>(response.body())
                        BraveLocalSearchResponse(
                            success = true,
                            results = parsed.results?.take(request.count ?: 5)?.map {
                                BraveLocalResult(
                                    title = it.title,
                                    address = it.address,
                                    phone = it.phone,
                                    rating = it.rating
                                )
                            } ?: emptyList(),
                            error = null
                        )
                    }
                    else -> {
                        logger.error("Brave Local API error: ${response.statusCode()} - ${response.body()}")
                        BraveLocalSearchResponse(
                            success = false,
                            results = emptyList(),
                            error = "HTTP ${response.statusCode()}: ${response.body().take(200)}"
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Brave Local Search failed", e)
                BraveLocalSearchResponse(
                    success = false,
                    results = emptyList(),
                    error = "Error: ${e.message}"
                )
            }
        }

        return FunctionToolCallback.builder("brave_local_search", function)
            .description("""
                Searches for local businesses and places using Brave's Local Search API. Best for queries related to physical locations, businesses, restaurants, services, etc. Returns detailed information including:
                - Business names and addresses
                - Ratings and review counts
                - Phone numbers and opening hours
                Use this when the query implies 'near me' or mentions specific locations. Automatically falls back to web search if no local results are found.
            """.trimIndent())
            .inputType(object : ParameterizedTypeReference<BraveLocalSearchRequest>() {})
            .build()
    }

    @Bean
    fun jinaReadUrlTool(): ToolCallback? {
        val settings = settingsService.settingsFlow.value
        if (!settings.enableJinaReader || settings.jinaApiKey.isNullOrBlank()) {
            logger.info("Jina Reader disabled (enableJinaReader=${settings.enableJinaReader}, apiKey present=${!settings.jinaApiKey.isNullOrBlank()})")
            return null
        }

        val function = Function { request: JinaReadUrlRequest ->
            try {
                val url = "https://r.jina.ai/${request.url}"

                val httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer ${settings.jinaApiKey}")
                    .GET()
                    .build()

                logger.debug("Jina Reader: ${request.url}")
                val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

                when (response.statusCode()) {
                    200 -> {
                        JinaReadUrlResponse(
                            success = true,
                            content = response.body(),
                            error = null
                        )
                    }
                    else -> {
                        logger.error("Jina Reader error: ${response.statusCode()} - ${response.body()}")
                        JinaReadUrlResponse(
                            success = false,
                            content = "",
                            error = "HTTP ${response.statusCode()}: ${response.body().take(200)}"
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Jina Reader failed", e)
                JinaReadUrlResponse(
                    success = false,
                    content = "",
                    error = "Error: ${e.message}"
                )
            }
        }

        return FunctionToolCallback.builder("jina_read_url", function)
            .description("""
                Convert any URL to LLM-friendly Markdown using Jina Reader API. Extracts clean, structured content from web pages, removing ads, navigation, and other noise. Best for:
                - Reading documentation, articles, blog posts
                - Extracting main content from complex web pages
                - Converting HTML to Markdown for further processing
                
                Simply provide the URL and get back clean Markdown content optimized for LLM consumption.
            """.trimIndent())
            .inputType(object : ParameterizedTypeReference<JinaReadUrlRequest>() {})
            .build()
    }
}

data class BraveWebSearchRequest(
    val query: String,
    val count: Int? = 10,
    val offset: Int? = 0
)

data class BraveWebSearchResponse(
    val success: Boolean,
    val results: List<BraveResult>,
    val error: String?
)

data class BraveResult(
    val title: String,
    val url: String,
    val description: String?
)

data class BraveLocalSearchRequest(
    val query: String,
    val count: Int? = 5
)

data class BraveLocalSearchResponse(
    val success: Boolean,
    val results: List<BraveLocalResult>,
    val error: String?
)

data class BraveLocalResult(
    val title: String,
    val address: String?,
    val phone: String?,
    val rating: Double?
)

data class JinaReadUrlRequest(
    val url: String
)

data class JinaReadUrlResponse(
    val success: Boolean,
    val content: String,
    val error: String?
)

@Serializable
private data class BraveSearchResponse(
    val web: BraveWebResults?
)

@Serializable
private data class BraveWebResults(
    val results: List<BraveWebResult>?
)

@Serializable
private data class BraveWebResult(
    val title: String,
    val url: String,
    val description: String?
)

@Serializable
private data class BraveLocalResponse(
    val results: List<BraveLocalResponseResult>?
)

@Serializable
private data class BraveLocalResponseResult(
    val title: String,
    val address: String? = null,
    val phone: String? = null,
    val rating: Double? = null
)
