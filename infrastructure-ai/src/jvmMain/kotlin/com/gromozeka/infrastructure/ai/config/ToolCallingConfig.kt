package com.gromozeka.infrastructure.ai.config

import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.resolution.ToolCallbackResolver
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration


@Configuration
class ToolCallingConfig {

    /**
     * Creates ToolCallingManager with lazy-loading of ToolCallback beans from ApplicationContext.
     *
     * CRITICAL: We do NOT inject List<ToolCallback> directly, as it causes circular dependency:
     * ToolCallingManager → ToolCallback beans → MemoryMcpTools → ChatModelFactory → ToolCallingManager
     *
     * Instead, we create a resolver that fetches beans from context ON-DEMAND (lazy),
     * which breaks the initialization cycle.
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
 * Lazy resolver that fetches ToolCallback beans from Spring context on-demand.
 * Caches results for repeated calls.
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
