package com.gromozeka.server

import com.gromozeka.application.service.SettingsService
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.FirstUserBootstrapToken
import com.gromozeka.domain.tool.Tool
import com.gromozeka.infrastructure.ai.config.InternalMcpToolsRegistrar
import com.gromozeka.shared.logging.JvmLogComponent
import com.gromozeka.shared.logging.JvmLogDirectoryResolver
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import java.net.InetSocketAddress
import java.net.Socket

fun main(args: Array<String>) {
    applyServerSystemProperties(args)
    val settings = ServerHttpSettings.configured()
    checkRemoteEndpointIsFree(settings.host, settings.port)
    val context = SpringApplicationBuilder(GromozekaServerApplication::class.java)
        .web(WebApplicationType.SERVLET)
        .profiles(resolveSpringProfile())
        .properties(mapOf(
            "server.forward-headers-strategy" to "none",
            // Artifact uploads are raw bytes, even when their media type is a form type.
            "spring.servlet.multipart.enabled" to "false",
            // Native operations already own delivery/execution deadlines. Do not introduce
            // an unrelated MVC 30-second timeout on top of durable Worker requests.
            "spring.mvc.async.request-timeout" to "-1",
            "server.shutdown" to "graceful",
            "server.error.include-message" to "never",
            "server.error.include-stacktrace" to "never",
        ))
        .run(*args)

    val webContext = context as? org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
    if (webContext?.webServer?.port != settings.port) {
        context.close()
        error("Gromozeka Servlet HTTP listener did not start on the configured port")
    }
    println("==== Gromozeka server started: ws://${settings.host}:${settings.port}/ws ====")
    println("==== Gromozeka MCP Streamable HTTP: http://${settings.host}:${settings.port}/mcp ====")
    println("==== Gromozeka memory HTTP: http://${settings.host}:${settings.port}/memory/status ====")
    val authentication = context.getBean(AuthenticationService::class.java)
    val bootstrapToken = context.getBean(FirstUserBootstrapToken::class.java)
    runBlocking {
        if (authentication.hasUsers()) bootstrapToken.disable()
        else println("==== First-user bootstrap token: ${bootstrapToken.currentToken()} ====")
    }
    // Spring owns the web server and its shutdown; no second engine or joining main thread.
}

private fun checkRemoteEndpointIsFree(host: String, port: Int) {
    val probeHost = when (host) { "0.0.0.0", "::" -> "127.0.0.1"; else -> host }
    val acceptsConnections = runCatching {
        Socket().use { it.connect(InetSocketAddress(probeHost, port), 500) }
    }.isSuccess
    check(!acceptsConnections) {
        "Gromozeka remote endpoint is already accepting connections at ws://$probeHost:$port/ws"
    }
}

private fun applyServerSystemProperties(args: Array<String>) {
    val mode = System.getProperty("GROMOZEKA_MODE") ?: System.getenv("GROMOZEKA_MODE")
    mode?.let { System.setProperty("GROMOZEKA_MODE", it) }
    JvmLogDirectoryResolver.configure(args, mode, JvmLogComponent.SERVER)
}

private fun resolveSpringProfile(): String =
    when ((System.getProperty("GROMOZEKA_MODE") ?: System.getenv("GROMOZEKA_MODE"))?.lowercase()) {
        "dev", "development" -> "dev"
        "test", "e2e" -> "e2e"
        null, "prod", "production" -> "prod"
        else -> error("Unsupported GROMOZEKA_MODE=${System.getProperty("GROMOZEKA_MODE")}")
    }

@SpringBootApplication(
    excludeName = [
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        // Authentication is provided by our existing identity/session/PAT services.
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
    ]
)
@ComponentScan(
    basePackages = [
        "com.gromozeka.server",
        "com.gromozeka.application",
        "com.gromozeka.infrastructure.db",
        "com.gromozeka.infrastructure.ai",
    ],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.CUSTOM,
            classes = [org.springframework.boot.context.TypeExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.REGEX,
            pattern = ["com\\.gromozeka\\.infrastructure\\.ai\\.mcp\\.tools\\..*"]),
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
            classes = [InternalMcpToolsRegistrar::class, Tool::class]),
    ]
)
class GromozekaServerApplication(private val settingsService: SettingsService) {
    @PostConstruct
    fun setupEnvironment() {
        System.setProperty("GROMOZEKA_HOME", settingsService.gromozekaHome.absolutePath)
    }
}
