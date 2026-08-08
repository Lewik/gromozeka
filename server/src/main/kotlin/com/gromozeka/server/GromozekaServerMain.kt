package com.gromozeka.server

import com.gromozeka.application.service.MemoryToolApplicationService
import com.gromozeka.application.service.ConversationArtifactApplicationService
import com.gromozeka.application.service.SettingsService
import com.gromozeka.application.service.ContextStateApplicationService
import com.gromozeka.infrastructure.ai.config.InternalMcpToolsRegistrar
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.FirstUserBootstrapToken
import com.gromozeka.domain.service.PersonalAccessTokenService
import com.gromozeka.domain.service.WorkerAccessService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.routing.routing
import io.ktor.server.routing.route
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
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
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

private val log = KLoggers.logger("GromozekaServer")

fun main() {
    applyServerSystemProperties()

    val host = System.getProperty("gromozeka.remote.host")
        ?: System.getenv("GROMOZEKA_REMOTE_HOST")
        ?: "127.0.0.1"
    val port = System.getProperty("gromozeka.remote.port")?.toIntOrNull()
        ?: System.getenv("GROMOZEKA_REMOTE_PORT")?.toIntOrNull()
        ?: 8765
    checkRemoteEndpointIsFree(host, port)

    val springReady = AtomicBoolean(false)
    val context = SpringApplicationBuilder(GromozekaServerApplication::class.java)
        .web(WebApplicationType.NONE)
        .profiles(resolveSpringProfile())
        .listeners(ApplicationListener<ApplicationReadyEvent> { springReady.set(true) })
        .run()

    check(springReady.get()) { "Spring application did not publish ApplicationReadyEvent" }

    val remoteServer = context.getBean(GromozekaRemoteServer::class.java)
    val mcpServerFactory = context.getBean(GromozekaMcpServerFactory::class.java)
    val controlMcpServerFactory = context.getBean(GromozekaControlMcpServerFactory::class.java)
    val memoryToolApplicationService = context.getBean(MemoryToolApplicationService::class.java)
    val workerEnrollmentService = context.getBean(WorkerEnrollmentService::class.java)
    val workerGatewayAuthenticationService = context.getBean(WorkerGatewayAuthenticationService::class.java)
    val workerGatewayService = context.getBean(WorkerGatewayService::class.java)
    val contextStateService = context.getBean(ContextStateApplicationService::class.java)
    val authenticationService = context.getBean(AuthenticationService::class.java)
    val bootstrapToken = context.getBean(FirstUserBootstrapToken::class.java)
    val authenticationAttemptLimiter = context.getBean(AuthenticationAttemptLimiter::class.java)
    val deviceConnectionService = context.getBean(DeviceConnectionService::class.java)
    val personalAccessTokenService = context.getBean(PersonalAccessTokenService::class.java)
    val artifactService = context.getBean(ConversationArtifactApplicationService::class.java)
    val remoteAuthorization = context.getBean(GromozekaRemoteAuthorization::class.java)
    val interactiveWorkerAccessService = context.getBean(InteractiveWorkerAccessService::class.java)
    val workerAccessService = context.getBean(WorkerAccessService::class.java)
    val webRoot = resolveWebRoot()
    val secureCookie = resolveSecureCookie(host)
    val trustForwardedHttps = resolveTrustForwardedHttps(
        System.getProperty("gromozeka.trust-forwarded-https")
            ?: System.getenv("GROMOZEKA_TRUST_FORWARDED_HTTPS"),
    )
    val mcpHttpSecurity = resolveMcpHttpSecurityConfiguration(
        System.getProperty("gromozeka.mcp.allowed-hosts")
            ?: System.getenv("GROMOZEKA_MCP_ALLOWED_HOSTS"),
    )

    log.info { "Starting Gromozeka remote server on ws://$host:$port/ws" }

    val ktorServer = embeddedServer(CIO, port = port, host = host) {
        attributes.put(trustForwardedHttpsKey, trustForwardedHttps)
        install(gromozekaBrowserSecurityHeaders)
        installHttpAuthenticationErrors()
        installMcpAuthentication(authenticationService, personalAccessTokenService)
        val websocketAuthentication = createRouteScopedPlugin("GromozekaWebSocketAuthentication") {
            onCall { call ->
                val authenticatedSession = try {
                    call.requireAuthenticated(authenticationService)
                } catch (_: MissingAuthenticationException) {
                    throw HttpAuthenticationException(
                        status = HttpStatusCode.Unauthorized,
                        publicMessage = "Authentication required",
                    )
                }
                call.attributes.put(authenticatedRemoteSessionKey, authenticatedSession)
            }
        }
        val workerWebsocketAuthentication = workerGatewayAuthentication(workerGatewayAuthenticationService)
        statelessMcpStreamableHttp(
            path = "/mcp",
            allowedHosts = mcpHttpSecurity.allowedHosts,
            allowedOrigins = mcpHttpSecurity.allowedOrigins,
        ) { call ->
            mcpServerFactory.create(call.attributes[authenticatedMcpCallerKey])
        }
        statelessMcpStreamableHttp(
            path = "/mcp/control",
            allowedHosts = mcpHttpSecurity.allowedHosts,
            allowedOrigins = mcpHttpSecurity.allowedOrigins,
        ) { call ->
            controlMcpServerFactory.create(call.attributes[authenticatedMcpCallerKey])
        }
        install(WebSockets) {
            maxFrameSize = MAX_WEBSOCKET_FRAME_BYTES
            masking = false
        }
        install(ConditionalHeaders)
        routing {
            gromozekaAuthentication(
                authenticationService = authenticationService,
                bootstrapToken = bootstrapToken,
                attemptLimiter = authenticationAttemptLimiter,
                secureCookie = secureCookie,
            )
            gromozekaDeviceConnections(
                deviceConnectionService = deviceConnectionService,
                authenticationService = authenticationService,
                attemptLimiter = authenticationAttemptLimiter,
                secureCookie = secureCookie,
            )
            route("/ws") {
                install(gromozekaBrowserOriginProtection)
                install(websocketAuthentication)
                webSocket {
                    remoteServer.handle(
                        this,
                        call.attributes[authenticatedRemoteSessionKey],
                    )
                }
            }
            route("/worker/ws") {
                install(workerWebsocketAuthentication)
                webSocket {
                    workerGatewayService.handle(
                        socket = this,
                        authenticatedWorker = call.attributes[authenticatedWorkerGatewayKey],
                    )
                }
            }
            gromozekaMemoryHttp(memoryToolApplicationService, authenticationService)
            gromozekaDistributions(workerEnrollmentService, authenticationService)
            gromozekaMobileWorkers(workerGatewayAuthenticationService, contextStateService)
            gromozekaArtifacts(artifactService, authenticationService, remoteAuthorization)
            gromozekaInteractiveWorkerAccess(
                interactiveAccessService = interactiveWorkerAccessService,
                authenticationService = authenticationService,
                workerAccessService = workerAccessService,
            )
            gromozekaWeb(webRoot)
        }
    }.start(wait = false)

    val endpoints = runBlocking {
        ktorServer.engine.resolvedConnectors()
            .joinToString { "ws://${it.host}:${it.port}/ws" }
    }
    println("==== Gromozeka server started: $endpoints ====")
    println("==== Gromozeka MCP Streamable HTTP: http://$host:$port/mcp ====")
    println("==== Gromozeka Control MCP Streamable HTTP: http://$host:$port/mcp/control ====")
    println("==== Gromozeka memory HTTP: http://$host:$port/memory/status ====")
    runBlocking {
        if (authenticationService.hasUsers()) {
            bootstrapToken.disable()
        } else {
            println("==== First-user bootstrap token: ${bootstrapToken.currentToken()} ====")
        }
    }
    Thread.currentThread().join()
}

private fun checkRemoteEndpointIsFree(host: String, port: Int) {
    val probeHost = when (host) {
        "0.0.0.0", "::" -> "127.0.0.1"
        else -> host
    }
    val acceptsConnections = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(probeHost, port), 500)
        }
    }.isSuccess

    check(!acceptsConnections) {
        "Gromozeka remote endpoint is already accepting connections at ws://$probeHost:$port/ws"
    }
}

private fun resolveWebRoot(): File {
    val configured = System.getProperty("gromozeka.web.static.dir")
        ?: System.getenv("GROMOZEKA_WEB_STATIC_DIR")
    if (configured != null) return File(configured)

    val projectRoot = System.getProperty("gromozeka.project.root")
        ?: File(".").absolutePath
    return File(projectRoot, "presentation/build/dist/wasmJs/developmentExecutable")
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

private fun resolveSecureCookie(host: String): Boolean {
    val configured = System.getProperty("gromozeka.auth.secure-cookie")
        ?: System.getenv("GROMOZEKA_AUTH_SECURE_COOKIE")
    if (configured != null) {
        return configured.toBooleanStrictOrNull()
            ?: error("GROMOZEKA_AUTH_SECURE_COOKIE must be true or false")
    }
    return !host.isLoopbackBinding()
}

private fun String.isLoopbackBinding(): Boolean =
    this == "127.0.0.1" || this == "::1" || equals("localhost", ignoreCase = true)

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
            classes = [
                InternalMcpToolsRegistrar::class,
                Tool::class,
            ]
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

private const val MAX_WEBSOCKET_FRAME_BYTES = 16L * 1024 * 1024
private val authenticatedRemoteSessionKey =
    AttributeKey<AuthenticatedRemoteSession>("gromozeka-authenticated-remote-session")
