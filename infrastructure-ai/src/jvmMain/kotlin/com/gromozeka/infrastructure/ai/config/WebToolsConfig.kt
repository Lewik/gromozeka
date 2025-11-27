package com.gromozeka.infrastructure.ai.config

import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.web.*
import com.gromozeka.infrastructure.ai.tool.web.*
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import java.util.function.BiFunction

/**
 * Configuration for Web search and content extraction tools.
 * 
 * All tools delegate to @Service Tool classes following the unified architecture.
 * Tools can be disabled via settings (returns null bean if disabled).
 */
@Configuration
class WebToolsConfig(
    private val settingsProvider: SettingsProvider
) {
    private val logger = LoggerFactory.getLogger(WebToolsConfig::class.java)

    @Bean
    fun braveWebSearchToolCallback(tool: com.gromozeka.infrastructure.ai.tool.web.BraveWebSearchTool): ToolCallback? {
        if (!settingsProvider.enableBraveSearch || settingsProvider.braveApiKey.isNullOrBlank()) {
            logger.info("Brave Web Search disabled (enabled=${settingsProvider.enableBraveSearch}, apiKey present=${!settingsProvider.braveApiKey.isNullOrBlank()})")
            return null
        }
        
        val function = object : BiFunction<BraveWebSearchRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: BraveWebSearchRequest, context: ToolContext?) = 
                tool.execute(request, context)
        }
        
        return FunctionToolCallback.builder(tool.name, function)
            .description(tool.description)
            .inputType(object : ParameterizedTypeReference<BraveWebSearchRequest>() {})
            .build()
    }

    @Bean
    fun braveLocalSearchToolCallback(tool: com.gromozeka.infrastructure.ai.tool.web.BraveLocalSearchTool): ToolCallback? {
        if (!settingsProvider.enableBraveSearch || settingsProvider.braveApiKey.isNullOrBlank()) {
            return null
        }
        
        val function = object : BiFunction<BraveLocalSearchRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: BraveLocalSearchRequest, context: ToolContext?) = 
                tool.execute(request, context)
        }
        
        return FunctionToolCallback.builder(tool.name, function)
            .description(tool.description)
            .inputType(object : ParameterizedTypeReference<BraveLocalSearchRequest>() {})
            .build()
    }

    @Bean
    fun jinaReadUrlToolCallback(tool: com.gromozeka.infrastructure.ai.tool.web.JinaReadUrlTool): ToolCallback? {
        if (!settingsProvider.enableJinaReader || settingsProvider.jinaApiKey.isNullOrBlank()) {
            logger.info("Jina Reader disabled (enabled=${settingsProvider.enableJinaReader}, apiKey present=${!settingsProvider.jinaApiKey.isNullOrBlank()})")
            return null
        }
        
        val function = object : BiFunction<JinaReadUrlRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: JinaReadUrlRequest, context: ToolContext?) = 
                tool.execute(request, context)
        }
        
        return FunctionToolCallback.builder(tool.name, function)
            .description(tool.description)
            .inputType(object : ParameterizedTypeReference<JinaReadUrlRequest>() {})
            .build()
    }
}
