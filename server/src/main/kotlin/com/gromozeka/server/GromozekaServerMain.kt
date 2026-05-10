package com.gromozeka.server

import com.gromozeka.application.service.SettingsService
import com.gromozeka.infrastructure.ai.config.InternalMcpToolsRegistrar
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import jakarta.annotation.PostConstruct
import klog.KLoggers
import kotlinx.coroutines.runBlocking
import org.springframework.boot.WebApplicationType
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import java.util.concurrent.atomic.AtomicBoolean

private val log = KLoggers.logger("GromozekaServer")

fun main() {
    applyServerSystemProperties()

    val springReady = AtomicBoolean(false)
    val context = SpringApplicationBuilder(GromozekaServerApplication::class.java)
        .web(WebApplicationType.NONE)
        .profiles(resolveSpringProfile())
        .listeners(ApplicationListener<ApplicationReadyEvent> { springReady.set(true) })
        .run()

    check(springReady.get()) { "Spring application did not publish ApplicationReadyEvent" }

    val remoteServer = context.getBean(GromozekaRemoteServer::class.java)
    val port = System.getProperty("gromozeka.remote.port")?.toIntOrNull()
        ?: System.getenv("GROMOZEKA_REMOTE_PORT")?.toIntOrNull()
        ?: 8765

    log.info { "Starting Gromozeka remote server on ws://127.0.0.1:$port/ws" }

    val ktorServer = embeddedServer(CIO, port = port, host = "127.0.0.1") {
        install(WebSockets) {
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        routing {
            webSocket("/ws") {
                remoteServer.handle(this)
            }
        }
    }.start(wait = false)

    val endpoints = runBlocking {
        ktorServer.engine.resolvedConnectors()
            .joinToString { "ws://${it.host}:${it.port}/ws" }
    }
    println("==== Gromozeka server started: $endpoints ====")
    Thread.currentThread().join()
}

private fun applyServerSystemProperties() {
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
        "dev", "development" -> "logs"
        "test", "e2e" -> customHome?.let { "$it/logs" } ?: "build/test-data/logs"
        null, "prod", "production" -> {
            val userHome = System.getProperty("user.home")
            when {
                System.getProperty("os.name").lowercase().contains("mac") -> "$userHome/Library/Logs/Gromozeka"
                System.getProperty("os.name").lowercase().contains("windows") -> "$userHome/AppData/Local/Gromozeka/logs"
                else -> "$userHome/.local/share/Gromozeka/logs"
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
        "com.gromozeka.server",
        "com.gromozeka.application",
        "com.gromozeka.infrastructure.db",
        "com.gromozeka.infrastructure.ai"
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
    ]
)
class GromozekaServerApplication(
    private val settingsService: SettingsService,
) {
    @PostConstruct
    fun setupEnvironment() {
        System.setProperty("GROMOZEKA_HOME", settingsService.gromozekaHome.absolutePath)
    }
}
