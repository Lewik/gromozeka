package com.gromozeka.infrastructure.ai.tool.web

import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.web.BraveWebSearchRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import com.gromozeka.domain.tool.ToolExecutionContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration

/**
 * Infrastructure implementation of BraveWebSearchTool.
 * 
 * Calls Brave Search API directly and formats response for Spring AI.
 * 
 * @see com.gromozeka.domain.tool.web.BraveWebSearchTool Domain specification
 * @see com.gromozeka.domain.service.WebSearchService.searchWeb (when created)
 */
@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class BraveWebSearchTool(
    private val settingsProvider: SettingsProvider,
    private val aiConfigurationProvider: AiConfigurationProvider,
) : com.gromozeka.domain.tool.web.BraveWebSearchTool {
    
    private val logger = LoggerFactory.getLogger(BraveWebSearchTool::class.java)
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    
    private val json = Json { ignoreUnknownKeys = true }

    override val available: Boolean
        get() {
            val settings = aiConfigurationProvider.catalog.webTools.braveSearch
            return settings.enabled && !settingsProvider.resolveSecret(settings.apiKey).isNullOrBlank()
        }
    
    override fun execute(request: BraveWebSearchRequest, context: ToolExecutionContext?): Map<String, Any> {
        val braveSearch = aiConfigurationProvider.catalog.webTools.braveSearch
        val apiKey = settingsProvider.resolveSecret(braveSearch.apiKey)
        if (!braveSearch.enabled || apiKey.isNullOrBlank()) {
            return mapOf<String, Any>(
                "success" to false,
                "results" to emptyList<Map<String, Any>>(),
                "error" to "Brave Search is disabled or API key is not configured"
            )
        }
        
        return try {
            require(request.query.isNotBlank() && request.query.length <= 600 && request.query.trim().split(Regex("\\s+")).size <= 75) {
                "Search query must contain at most 600 characters and 75 words"
            }
            require(request.count in 1..20 && request.offset in 0..9) { "Search count must be 1 to 20 and offset 0 to 9" }
            val url = buildString {
                append("https://api.search.brave.com/res/v1/web/search")
                append("?q=").append(URLEncoder.encode(request.query, "UTF-8"))
                append("&count=").append(request.count)
                append("&offset=").append(request.offset)
            }

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .GET()
                .build()

            logger.debug("Brave Web Search request, count={}", request.count)
            val response = httpClient.send(httpRequest, boundedWebBody())

            when (response.statusCode()) {
                200 -> {
                    val parsed = json.decodeFromString<BraveSearchResponse>(response.body().toString(Charsets.UTF_8))
                    mapOf<String, Any>(
                        "success" to true,
                        "results" to (parsed.web?.results?.take(request.count)?.map { result ->
                            mapOf<String, Any>(
                                "title" to result.title,
                                "url" to result.url,
                                "description" to (result.description ?: "")
                            )
                        } ?: emptyList()),
                        "error" to ""
                    )
                }
                else -> {
                    logger.warn("Brave Search HTTP status {}", response.statusCode())
                    mapOf<String, Any>(
                        "success" to false,
                        "results" to emptyList<Map<String, Any>>(),
                        "error" to "Brave Search HTTP ${response.statusCode()}"
                    )
                }
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            logger.warn("Brave Web Search failed: {}", e.javaClass.simpleName)
            mapOf<String, Any>(
                "success" to false,
                "results" to emptyList<Map<String, Any>>(),
                "error" to if (e is IllegalArgumentException) "Invalid search parameters" else "Brave Search request failed (${e.javaClass.simpleName})"
            )
        }
    }
}

// Internal response models for Brave API
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
