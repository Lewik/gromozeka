package com.gromozeka.server

import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource
import java.net.InetSocketAddress
import java.net.Socket
import java.util.function.Supplier

/** Opt-in fixtures borrow one existing isolated domain context and expose the production Spring transport. */
internal class SpringServerTestHost(parent: ConfigurableApplicationContext, settings: ServerHttpSettings) : AutoCloseable {
    private val context = AnnotationConfigServletWebServerApplicationContext()
    init {
        require(settings.host == "127.0.0.1") { "A test host must bind loopback only" }
        check(runCatching { Socket().use { it.connect(InetSocketAddress(settings.host, settings.port), 300) } }.isFailure) {
            "The explicitly assigned test port is already in use"
        }
        context.parent = parent
        context.environment.propertySources.addFirst(MapPropertySource("isolated-http-fixture", mapOf<String, Any>(
            "server.forward-headers-strategy" to "none", "spring.mvc.async.request-timeout" to "-1",
            "spring.servlet.multipart.enabled" to "false",
            "server.error.include-message" to "never", "server.error.include-stacktrace" to "never",
        )))
        context.registerBean("serverHttpSettings", ServerHttpSettings::class.java, Supplier { settings })
        context.register(
            ServletConfiguration::class.java,
            GromozekaHttpSecurityConfiguration::class.java,
            GromozekaWebSocketConfiguration::class.java,
            GromozekaWebRouting::class.java,
            GromozekaWebIndex::class.java,
            GromozekaHttpExceptionHandler::class.java,
            GromozekaAuthenticationHttp::class.java,
            GromozekaDeviceConnectionHttp::class.java,
            GromozekaDistributionRouting::class.java,
            GromozekaArtifactRouting::class.java,
            GromozekaHistoryRouting::class.java,
            GromozekaInteractiveWorkerAccessRouting::class.java,
            GromozekaMemoryHttpRouting::class.java,
            WorkerEventRouting::class.java,
            GromozekaStatelessMcpHttp::class.java,
        )
        try { context.refresh() } catch (error: Exception) { context.close(); throw error }
    }

    override fun close() { context.close() }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = [
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
    ])
    internal class ServletConfiguration
}
