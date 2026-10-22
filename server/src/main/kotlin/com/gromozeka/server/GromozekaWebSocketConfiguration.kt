package com.gromozeka.server

import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean
import java.net.InetAddress

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
internal class GromozekaWebSocketConfiguration(
    remote: GromozekaRemoteServer,
    gateway: WorkerGatewayService,
) : WebSocketConfigurer, DisposableBean {
    private val remoteHandler = CoroutineWebSocketHandler { connection, attributes ->
        remote.handle(connection, checkNotNull(attributes[AUTHENTICATED_REMOTE_SESSION] as? AuthenticatedRemoteSession))
    }
    private val gatewayHandler = CoroutineWebSocketHandler { connection, attributes ->
        gateway.handle(connection, checkNotNull(attributes[AUTHENTICATED_WORKER_GATEWAY] as? AuthenticatedWorkerGateway))
    }

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        // The security filter authenticates the upgrade and enforces our browser-origin policy.
        // Native Worker clients do not have browser Origin headers.
        registry.addHandler(remoteHandler, "/ws")
            .addInterceptors(PrincipalHandshake(AUTHENTICATED_REMOTE_SESSION)).setAllowedOriginPatterns("*")
        registry.addHandler(gatewayHandler, "/worker/ws")
            .addInterceptors(PrincipalHandshake(AUTHENTICATED_WORKER_GATEWAY)).setAllowedOriginPatterns("*")
    }

    @Bean
    fun servletWebSocketContainer() = ServletServerContainerFactoryBean().apply {
        maxTextMessageBufferSize = MAX_WEBSOCKET_MESSAGE_BYTES
        maxBinaryMessageBufferSize = MAX_WEBSOCKET_MESSAGE_BYTES
        asyncSendTimeout = 30_000L
    }

    @Bean
    fun serverAddress(settings: ServerHttpSettings) = WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {
        // Keep the existing explicit bind address/assigned development port authoritative.
        it.setAddress(InetAddress.getByName(settings.host))
        it.setPort(settings.port)
    }

    override fun destroy() {
        try { remoteHandler.destroy() } finally { gatewayHandler.destroy() }
    }

    private class PrincipalHandshake(private val key: String) : HandshakeInterceptor {
        override fun beforeHandshake(request: ServerHttpRequest, response: ServerHttpResponse,
            handler: WebSocketHandler, attributes: MutableMap<String, Any>): Boolean {
            val servlet = (request as? ServletServerHttpRequest)?.servletRequest ?: return false
            val principal = servlet.getAttribute(key) ?: return false
            attributes[key] = principal
            return true
        }
        override fun afterHandshake(request: ServerHttpRequest, response: ServerHttpResponse,
            handler: WebSocketHandler, exception: Exception?) = Unit
    }
}
