package com.gromozeka.infrastructure.ai.tool.web

import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.web.JinaReadUrlRequest
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Infrastructure implementation of JinaReadUrlTool.
 * 
 * Calls Jina Reader API directly and formats response for Spring AI.
 * 
 * @see com.gromozeka.domain.tool.web.JinaReadUrlTool Domain specification
 * @see com.gromozeka.domain.service.WebSearchService.readUrl (when created)
 */
@Service
class JinaReadUrlTool(
    private val settingsProvider: SettingsProvider
) : com.gromozeka.domain.tool.web.JinaReadUrlTool {
    
    private val logger = LoggerFactory.getLogger(JinaReadUrlTool::class.java)
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    
    override fun execute(request: JinaReadUrlRequest, context: ToolContext?): Map<String, Any> {
        if (!settingsProvider.enableJinaReader || settingsProvider.jinaApiKey.isNullOrBlank()) {
            return mapOf<String, Any>(
                "success" to false,
                "content" to "",
                "error" to "Jina Reader is disabled or API key is not configured"
            )
        }
        
        return try {
            val apiKey = settingsProvider.jinaApiKey!!
            val url = "https://r.jina.ai/${request.url}"

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .GET()
                .build()

            logger.debug("Jina Reader: ${request.url}")
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

            when (response.statusCode()) {
                200 -> {
                    mapOf<String, Any>(
                        "success" to true,
                        "content" to response.body(),
                        "error" to ""
                    )
                }
                else -> {
                    logger.error("Jina Reader error: ${response.statusCode()} - ${response.body()}")
                    mapOf<String, Any>(
                        "success" to false,
                        "content" to "",
                        "error" to "HTTP ${response.statusCode()}: ${response.body().take(200)}"
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Jina Reader failed", e)
            mapOf<String, Any>(
                "success" to false,
                "content" to "",
                "error" to "Error: ${e.message}"
            )
        }
    }
}
