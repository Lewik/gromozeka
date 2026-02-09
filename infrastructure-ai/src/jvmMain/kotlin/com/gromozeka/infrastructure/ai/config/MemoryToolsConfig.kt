package com.gromozeka.infrastructure.ai.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration

/**
 * DEPRECATED: This configuration class is no longer needed.
 * 
 * Memory tool registration now handled by ToolsRegistrationConfig which automatically
 * discovers all Tool<*, *> @Service beans and registers them as ToolCallbacks.
 * 
 * This file kept temporarily for reference but all @Bean methods have been removed
 * to avoid duplicate tool registration.
 * 
 * @see ToolsRegistrationConfig for current tool registration mechanism
 */
@Configuration
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class MemoryToolsConfig {
    // All @Bean methods removed - tools now registered via ToolsRegistrationConfig
}
