package com.gromozeka.infrastructure.ai.tool.web

import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.web.BraveWebSearchRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
class BraveWebSearchTool(
    private val settingsProvider: SettingsProvider
) : com.gromozeka.domain.tool.web.BraveWebSearchTool {
    
    private val logger = LoggerFactory.getLogger(BraveWebSearchTool::class.java)
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    
    private val json = Json { ignoreUnknownKeys = true }
    
    override fun execute(request: BraveWebSearchRequest, context: ToolContext?): Map<String, Any> {
        if (!settingsProvider.enableBraveSearch || settingsProvider.braveApiKey.isNullOrBlank()) {
            return mapOf<String, Any>(
                "success" to false,
                "results" to emptyList<Map<String, Any>>(),
                "error" to "Brave Search is disabled or API key is not configured"
            )
        }
        
        return try {
            val apiKey = settingsProvider.braveApiKey!!
            val url = buildString {
                append("https://api.search.brave.com/res/v1/web/search")
                append("?q=").append(URLEncoder.encode(request.query, "UTF-8"))
                append("&count=").append(request.count)
                append("&offset=").append(request.offset)
            }

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .GET()
                .build()

            logger.debug("Brave Web Search: ${request.query} (count=${request.count})")
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

            when (response.statusCode()) {
                200 -> {
                    val parsed = json.decodeFromString<BraveSearchResponse>(response.body())
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
                    logger.error("Brave API error: ${response.statusCode()} - ${response.body()}")
                    mapOf<String, Any>(
                        "success" to false,
                        "results" to emptyList<Map<String, Any>>(),
                        "error" to "HTTP ${response.statusCode()}: ${response.body().take(200)}"
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Brave Web Search failed", e)
            mapOf<String, Any>(
                "success" to false,
                "results" to emptyList<Map<String, Any>>(),
                "error" to "Error: ${e.message}"
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
