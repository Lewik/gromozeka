package com.gromozeka.server

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File

internal data class ServerHttpSettings(
    val host: String = "127.0.0.1",
    val port: Int = 8765,
    val secureCookie: Boolean = false,
    val trustForwardedHttps: Boolean = false,
    val mcp: McpHttpSecurityConfiguration = resolveMcpHttpSecurityConfiguration(null),
    val webRoot: File = File("presentation/build/dist/wasmJs/developmentExecutable"),
) {
    init { require(port in 1..65535) { "Server port is out of range" } }

    companion object {
        fun configured(): ServerHttpSettings {
            fun value(property: String, environment: String) = System.getProperty(property) ?: System.getenv(environment)
            val host = value("gromozeka.remote.host", "GROMOZEKA_REMOTE_HOST") ?: "127.0.0.1"
            val portValue = value("gromozeka.remote.port", "GROMOZEKA_REMOTE_PORT")
            val port = portValue?.let { it.toIntOrNull() ?: error("Invalid GROMOZEKA_REMOTE_PORT") } ?: 8765
            val cookie = value("gromozeka.auth.secure-cookie", "GROMOZEKA_AUTH_SECURE_COOKIE")?.let {
                it.toBooleanStrictOrNull() ?: error("GROMOZEKA_AUTH_SECURE_COOKIE must be true or false")
            } ?: !(host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true))
            val root = value("gromozeka.web.static.dir", "GROMOZEKA_WEB_STATIC_DIR")?.let(::File)
                ?: File(System.getProperty("gromozeka.project.root") ?: File(".").absolutePath,
                    "presentation/build/dist/wasmJs/developmentExecutable")
            return ServerHttpSettings(
                host = host,
                port = port,
                secureCookie = cookie,
                trustForwardedHttps = resolveTrustForwardedHttps(
                    value("gromozeka.trust-forwarded-https", "GROMOZEKA_TRUST_FORWARDED_HTTPS")),
                mcp = resolveMcpHttpSecurityConfiguration(value("gromozeka.mcp.allowed-hosts", "GROMOZEKA_MCP_ALLOWED_HOSTS")),
                webRoot = root,
            )
        }
    }
}

@Configuration(proxyBeanMethods = false)
internal class ServerHttpSettingsConfiguration {
    @Bean
    fun serverHttpSettings(): ServerHttpSettings = ServerHttpSettings.configured()
}
