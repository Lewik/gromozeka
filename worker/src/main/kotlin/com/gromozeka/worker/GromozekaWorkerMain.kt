package com.gromozeka.worker

import com.gromozeka.application.service.ConversationRuntimeWorker
import com.gromozeka.application.service.SettingsService
import com.gromozeka.infrastructure.ai.config.InternalMcpToolsRegistrar
import kotlinx.coroutines.runBlocking
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import java.io.File

fun main() {
    applyWorkerSystemProperties()

    val context = SpringApplicationBuilder(GromozekaWorkerApplication::class.java)
        .web(WebApplicationType.NONE)
        .profiles(resolveSpringProfile())
        .run()

    val worker = context.getBean(ConversationRuntimeWorker::class.java)
    check(worker.isRunning) { "Conversation runtime Worker did not start" }
    val failure = runBlocking { worker.awaitTermination() }
    context.close()
    if (failure != null) {
        throw IllegalStateException("Conversation runtime Worker terminated unexpectedly", failure)
    }
}

private fun applyWorkerSystemProperties() {
    val mode = System.getProperty("GROMOZEKA_MODE")
        ?: System.getenv("GROMOZEKA_MODE")

    mode?.let { System.setProperty("GROMOZEKA_MODE", it) }
    System.setProperty("logging.file.path", determineLogPath(mode))
}

private fun resolveSpringProfile(): String =
    when ((System.getProperty("GROMOZEKA_MODE") ?: System.getenv("GROMOZEKA_MODE"))?.lowercase()) {
        "dev", "development" -> "dev"
        "test", "e2e" -> "e2e"
        null, "prod", "production" -> "prod"
        else -> error("Unsupported GROMOZEKA_MODE=${System.getProperty("GROMOZEKA_MODE")}")
    }

private fun determineLogPath(mode: String?): String {
    val customHome = System.getProperty("GROMOZEKA_HOME")
        ?: System.getenv("GROMOZEKA_HOME")

    return when (mode?.lowercase()) {
        "dev", "development" -> "logs/workers"
        "test", "e2e" -> customHome?.let { "$it/logs/workers" } ?: "build/test-data/logs/workers"
        null, "prod", "production" -> {
            val userHome = System.getProperty("user.home")
            when {
                System.getProperty("os.name").lowercase().contains("mac") ->
                    "$userHome/Library/Logs/Gromozeka/workers"
                System.getProperty("os.name").lowercase().contains("windows") ->
                    "$userHome/AppData/Local/Gromozeka/logs/workers"
                else -> "$userHome/.local/share/Gromozeka/logs/workers"
            }
        }
        else -> error("Unsupported GROMOZEKA_MODE=$mode")
    }
}

@SpringBootApplication(
    exclude = [
        JdbcTemplateAutoConfiguration::class,
        DataSourceTransactionManagerAutoConfiguration::class,
    ]
)
@ComponentScan(
    basePackages = [
        "com.gromozeka.worker",
        "com.gromozeka.application",
        "com.gromozeka.infrastructure.db",
        "com.gromozeka.infrastructure.runtime",
        "com.gromozeka.infrastructure.ai",
    ],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ["com\\.gromozeka\\.infrastructure\\.ai\\.mcp\\.tools\\..*"]
        ),
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [InternalMcpToolsRegistrar::class]
        ),
    ],
)
class GromozekaWorkerApplication(
    settingsService: SettingsService,
) {
    init {
        System.setProperty("GROMOZEKA_HOME", settingsService.gromozekaHome.absolutePath)
    }
}
