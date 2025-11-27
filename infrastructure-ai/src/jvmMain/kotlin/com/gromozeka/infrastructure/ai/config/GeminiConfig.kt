package com.gromozeka.infrastructure.ai.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.genai.Client
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.resolution.ToolCallbackResolver
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FileInputStream


@Configuration
class GeminiConfig {

    // DISABLED: Gemini requires google-credentials.json which is not available in production
    // Uncomment when credentials are available for development
    /*
    @Bean
    fun geminiClient(
        @Value("\${spring.ai.google-genai.project-id}") projectId: String,
        @Value("\${spring.ai.google-genai.location}") location: String,
        @Value("\${spring.ai.google-genai.credentials-uri}") credentialsUri: String,
    ): Client {
        val credentials = GoogleCredentials.fromStream(
            FileInputStream(credentialsUri.removePrefix("file:"))
        ).createScoped("https://www.googleapis.com/auth/cloud-platform")
        return Client.builder()
            .project(projectId)
            .location(location)
            .vertexAI(true)
            .credentials(credentials)
            .build()
    }
    */

    /**
     * Создает ToolCallingManager с lazy-загрузкой ToolCallback бинов из ApplicationContext.
     * 
     * КРИТИЧНО: Не инжектим List<ToolCallback> напрямую, т.к. это вызывает циклическую зависимость:
     * ToolCallingManager → ToolCallback beans → MemoryMcpTools → ChatModelFactory → ToolCallingManager
     * 
     * Вместо этого создаем резолвер, который получает бины из контекста ПО ТРЕБОВАНИЮ (lazy),
     * что разрывает цикл инициализации.
     */
    @Bean
    fun toolCallingManager(
        applicationContext: ApplicationContext
    ): ToolCallingManager {
        val resolver = SpringContextToolCallbackResolver(applicationContext)
        return ToolCallingManager.builder()
            .toolCallbackResolver(resolver)
            .build()
    }

}

/**
 * Lazy-резолвер, получающий ToolCallback бины из Spring контекста по требованию.
 * Кэширует результаты для повторных вызовов.
 */
private class SpringContextToolCallbackResolver(
    private val applicationContext: ApplicationContext
) : ToolCallbackResolver {
    
    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(SpringContextToolCallbackResolver::class.java)
    }
    
    private val cache = mutableMapOf<String, ToolCallback?>()
    
    override fun resolve(toolName: String): ToolCallback? {
        return cache.getOrPut(toolName) {
            log.info("=== Resolving tool: '$toolName' ===")
            
            // Strategy 1: Search through getBeansOfType (for regular @Bean)
            val fromBeans = applicationContext.getBeansOfType(ToolCallback::class.java)
            val availableFromBeans = fromBeans.values.map { it.toolDefinition.name() }
            log.info("Strategy 1 (getBeansOfType): Found ${availableFromBeans.size} tools: $availableFromBeans")
            
            val foundInBeans = fromBeans.values.firstOrNull { it.toolDefinition.name() == toolName }
            if (foundInBeans != null) {
                log.info("✓ Tool resolved via Strategy 1: $toolName -> ${foundInBeans.javaClass.simpleName}")
                return@getOrPut foundInBeans
            }
            
            // Strategy 2: Direct bean name lookup (for registerSingleton)
            // ToolsRegistrationConfig registers beans with pattern: "${tool.name}ToolCallback"
            val beanName = "${toolName}ToolCallback"
            log.info("Strategy 2 (singleton lookup): Looking for bean '$beanName'")
            if (applicationContext.containsBean(beanName)) {
                val foundBySingleton = applicationContext.getBean(beanName, ToolCallback::class.java)
                log.info("✓ Tool resolved via Strategy 2: $toolName -> ${foundBySingleton.javaClass.simpleName}")
                return@getOrPut foundBySingleton
            }
            
            log.warn("✗ Tool NOT FOUND: $toolName")
            log.warn("  Available tools from Strategy 1: $availableFromBeans")
            log.warn("  Tried bean name in Strategy 2: $beanName")
            null
        }
    }
}
