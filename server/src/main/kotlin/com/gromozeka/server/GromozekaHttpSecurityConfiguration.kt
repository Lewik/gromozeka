package com.gromozeka.server

import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.PersonalAccessTokenService
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.header.writers.StaticHeadersWriter
import org.springframework.web.filter.OncePerRequestFilter

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
internal class GromozekaHttpSecurityConfiguration {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        authentication: AuthenticationService,
        tokens: PersonalAccessTokenService,
        workers: WorkerGatewayAuthenticationService,
        settings: ServerHttpSettings,
        oauth: org.springframework.beans.factory.ObjectProvider<McpOAuthAccessTokens>,
    ): SecurityFilterChain {
        // Existing opaque user sessions remain in the domain service, never HttpSession.
        // Browser mutations retain explicit Origin checks plus SameSite=Strict cookies.
        http.csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .headers {
                it.cacheControl { cache -> cache.disable() } // Resource controllers own cache policy.
                it.addHeaderWriter(StaticHeadersWriter("Referrer-Policy", "no-referrer"))
                it.addHeaderWriter(StaticHeadersWriter("Permissions-Policy",
                    "camera=(self), microphone=(self), geolocation=(self), display-capture=(self)"))
            }
            .authorizeHttpRequests {
                it.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                it.requestMatchers("/auth/**", "/api/distributions", "/api/worker-enrollments/consume", "/internal/dcv-auth").permitAll()
                it.requestMatchers("/mcp", "/mcp/**", "/ws", "/worker/ws", "/api/**", "/memory/**").authenticated()
                it.requestMatchers(HttpMethod.GET, "/**").permitAll()
                it.requestMatchers(HttpMethod.HEAD, "/**").permitAll()
                it.anyRequest().denyAll()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ -> response.writeHttpError(HttpStatus.UNAUTHORIZED, "Authentication required") }
                it.accessDeniedHandler { _, response, _ -> response.writeHttpError(HttpStatus.FORBIDDEN, "Access denied") }
            }
            .addFilterBefore(GromozekaRequestAuthenticationFilter(authentication, tokens, workers, settings, oauth.getIfAvailable()),
                AnonymousAuthenticationFilter::class.java)
        return http.build()
    }
}

/** Only this trusted HTTP boundary attaches principals; JSON/tool arguments never do. */
internal class GromozekaRequestAuthenticationFilter(
    private val authentication: AuthenticationService,
    private val tokens: PersonalAccessTokenService,
    private val workers: WorkerGatewayAuthenticationService,
    private val settings: ServerHttpSettings,
    private val oauth: McpOAuthAccessTokens? = null,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI
        val principal = try {
            runBlocking {
                when {
                    path == "/mcp" || path.startsWith("/mcp/") -> request.requireMcpCaller(authentication, tokens, settings, oauth)
                        .also { request.setAttribute(AUTHENTICATED_MCP_CALLER, it) }
                    path == "/worker/ws" || path == "/api/worker/events" || path == "/api/worker/heartbeat" -> {
                        if (path != "/worker/ws" && !request.isSecureTransport(settings)) {
                            throw HttpAuthenticationException(HttpStatus.UPGRADE_REQUIRED, "Worker synchronization requires HTTPS")
                        }
                        (workers.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION))
                            ?: throw HttpAuthenticationException(HttpStatus.UNAUTHORIZED, "Worker authentication required",
                                """Bearer realm="gromozeka-worker""""))
                            .also { request.setAttribute(AUTHENTICATED_WORKER_GATEWAY, it) }
                    }
                    path == "/ws" || path.startsWith("/memory/") ||
                        (path.startsWith("/api/") && path !in PUBLIC_API_PATHS) -> {
                        request.requireAllowedBrowserOrigin()
                        request.requireAuthenticated(authentication).also { request.setAttribute(AUTHENTICATED_REMOTE_SESSION, it) }
                    }
                    else -> null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: HttpAuthenticationException) {
            error.challenge?.let { response.setHeader(HttpHeaders.WWW_AUTHENTICATE, it) }
            response.writeHttpError(error.status, error.publicMessage)
            return
        } catch (_: MissingAuthenticationException) {
            response.writeHttpError(HttpStatus.UNAUTHORIZED, "Authentication required")
            return
        } catch (_: Exception) {
            response.writeHttpError(HttpStatus.SERVICE_UNAVAILABLE, "Authentication is unavailable")
            return
        }
        if (principal != null) {
            val context = SecurityContextHolder.createEmptyContext()
            context.authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
            SecurityContextHolder.setContext(context)
        }
        try { chain.doFilter(request, response) } finally { SecurityContextHolder.clearContext() }
    }

    private companion object {
        val PUBLIC_API_PATHS = setOf("/api/distributions", "/api/worker-enrollments/consume")
    }
}
