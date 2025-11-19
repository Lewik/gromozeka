package com.gromozeka.infrastructure.ai.config.mcp

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Managed CoroutineScope with proper lifecycle for MCP operations.
 * Ensures coroutines are cancelled during application shutdown.
 */
class ManagedCoroutineScope(
    private val scope: CoroutineScope
) : CoroutineScope by scope {
    private val log = LoggerFactory.getLogger(ManagedCoroutineScope::class.java)

    @PreDestroy
    fun shutdown() {
        log.info("Cancelling MCP coroutine scope")
        scope.cancel("Application shutdown")
    }
}

@Configuration
class McpCoroutineConfig {

    @Bean("mcpCoroutineScope")
    fun mcpCoroutineScope(): ManagedCoroutineScope {
        // Create daemon thread factory for MCP operations
        val threadCounter = AtomicInteger(0)
        val threadFactory = ThreadFactory { runnable ->
            Thread(runnable).apply {
                isDaemon = true
                name = "mcp-coroutine-${threadCounter.incrementAndGet()}"
            }
        }

        val executor = Executors.newFixedThreadPool(4, threadFactory)
        val dispatcher = executor.asCoroutineDispatcher()

        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            LoggerFactory.getLogger("McpCoroutineScope")
                .error("Uncaught exception in MCP coroutine", throwable)
        }
        val scope = CoroutineScope(dispatcher + exceptionHandler + SupervisorJob())
        return ManagedCoroutineScope(scope)
    }
}
