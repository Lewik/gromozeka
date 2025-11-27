package com.gromozeka.infrastructure.ai.tool.web

import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.web.BraveLocalSearchRequest
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
 * Infrastructure implementation of BraveLocalSearchTool.
 * 
 * Calls Brave Local Search API directly and formats response for Spring AI.
 * 
 * @see com.gromozeka.domain.tool.web.BraveLocalSearchTool Domain specification
 * @see com.gromozeka.domain.service.WebSearchService.searchLocal (when created)
 */
@Service
class BraveLocalSearchTool(
    private val settingsProvider: SettingsProvider
) : com.gromozeka.domain.tool.web.BraveLocalSearchTool {
    
    private val logger = LoggerFactory.getLogger(BraveLocalSearchTool::class.java)
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    
    private val json = Json { ignoreUnknownKeys = true }
    
    override fun execute(request: BraveLocalSearchRequest, context: ToolContext?): Map<String, Any> {
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
                append("https://api.search.brave.com/res/v1/local/search")
                append("?q=").append(URLEncoder.encode(request.query, "UTF-8"))
                append("&count=").append(request.count)
            }

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .GET()
                .build()

            logger.debug("Brave Local Search: ${request.query} (count=${request.count})")
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

            when (response.statusCode()) {
                200 -> {
                    val parsed = json.decodeFromString<BraveLocalResponse>(response.body())
                    mapOf<String, Any>(
                        "success" to true,
                        "results" to (parsed.results?.take(request.count)?.map { result ->
                            mapOf<String, Any>(
                                "title" to result.title,
                                "address" to (result.address ?: ""),
                                "phone" to (result.phone ?: ""),
                                "rating" to (result.rating ?: 0.0)
                            )
                        } ?: emptyList()),
                        "error" to ""
                    )
                }
                else -> {
                    logger.error("Brave Local API error: ${response.statusCode()} - ${response.body()}")
                    mapOf<String, Any>(
                        "success" to false,
                        "results" to emptyList<Map<String, Any>>(),
                        "error" to "HTTP ${response.statusCode()}: ${response.body().take(200)}"
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Brave Local Search failed", e)
            mapOf<String, Any>(
                "success" to false,
                "results" to emptyList<Map<String, Any>>(),
                "error" to "Error: ${e.message}"
            )
        }
    }
}

// Internal response models for Brave Local API
@Serializable
private data class BraveLocalResponse(
    val results: List<BraveLocalResult>?
)

@Serializable
private data class BraveLocalResult(
    val title: String,
    val address: String? = null,
    val phone: String? = null,
    val rating: Double? = null
)
