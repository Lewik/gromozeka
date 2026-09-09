package com.gromozeka.infrastructure.ai.tool.web

import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.web.JinaReadUrlRequest
import org.slf4j.LoggerFactory
import com.gromozeka.domain.tool.ToolExecutionContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
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
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class JinaReadUrlTool(
    private val settingsProvider: SettingsProvider,
    private val aiConfigurationProvider: AiConfigurationProvider,
) : com.gromozeka.domain.tool.web.JinaReadUrlTool {
    
    private val logger = LoggerFactory.getLogger(JinaReadUrlTool::class.java)
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    override val available: Boolean
        get() {
            val settings = aiConfigurationProvider.catalog.webTools.jinaReader
            return settings.enabled && !settingsProvider.resolveSecret(settings.apiKey).isNullOrBlank()
        }
    
    override fun execute(request: JinaReadUrlRequest, context: ToolExecutionContext?): Map<String, Any> {
        val jinaReader = aiConfigurationProvider.catalog.webTools.jinaReader
        val apiKey = settingsProvider.resolveSecret(jinaReader.apiKey)
        if (!jinaReader.enabled || apiKey.isNullOrBlank()) {
            return mapOf<String, Any>(
                "success" to false,
                "content" to "",
                "error" to "Jina Reader is disabled or API key is not configured"
            )
        }
        
        return try {
            val target = requirePublicWebUrl(request.url)
            val url = "https://r.jina.ai/$target"

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .GET()
                .build()

            logger.debug("Jina Reader request")
            val response = httpClient.send(httpRequest, boundedWebBody())

            when (response.statusCode()) {
                200 -> {
                    mapOf<String, Any>(
                        "success" to true,
                        "content" to response.body().toString(Charsets.UTF_8),
                        "error" to ""
                    )
                }
                else -> {
                    logger.warn("Jina Reader HTTP status {}", response.statusCode())
                    mapOf<String, Any>(
                        "success" to false,
                        "content" to "",
                        "error" to "Jina Reader HTTP ${response.statusCode()}"
                    )
                }
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            logger.warn("Jina Reader failed: {}", e.javaClass.simpleName)
            mapOf<String, Any>(
                "success" to false,
                "content" to "",
                "error" to if (e is IllegalArgumentException) e.message.orEmpty() else "Jina Reader request failed (${e.javaClass.simpleName})"
            )
        }
    }
}
