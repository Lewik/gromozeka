package com.gromozeka.bot.config.mcp

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class McpCoroutineConfig {

    @Bean("mcpCoroutineScope")
    fun mcpCoroutineScope(): CoroutineScope {
        val dispatcher = Dispatchers.IO.limitedParallelism(4)
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            LoggerFactory.getLogger("McpCoroutineScope")
                .error("Uncaught exception in MCP coroutine", throwable)
        }
        return CoroutineScope(dispatcher + exceptionHandler + SupervisorJob())
    }
}
