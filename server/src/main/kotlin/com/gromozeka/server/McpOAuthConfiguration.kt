package com.gromozeka.server

import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.UserDirectoryService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.springaicommunity.mcp.security.authorizationserver.config.LocalhostWildcardPortValidator
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.web.servlet.ServletContextInitializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator
import org.springframework.security.oauth2.server.authorization.web.OAuth2ClientAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.oauth2.server.authorization.web.OAuth2AuthorizationEndpointFilter
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.context.NullSecurityContextRepository
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.util.function.Consumer
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "gromozeka.oauth", name = ["issuer"])
internal class McpOAuthConfiguration {
    private val clock: Clock = Clock.systemUTC()
    @Bean
    fun mcpOAuthSettings(@Value("\${gromozeka.oauth.issuer}") issuer: String) = McpOAuthSettings.parse(issuer)

    @Bean(destroyMethod = "close")
    fun mcpOAuthMetadataFetcher() = McpOAuthMetadataFetcher()

    @Bean
    @DependsOn("postgresFlyway")
    fun mcpOAuthClients(dataSource: DataSource, settings: McpOAuthSettings,
        fetcher: org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.client.metadata.ClientIdMetadataDocumentResolver) =
        McpOAuthClientRepository(dataSource, settings, fetcher, clock)

    @Bean
    @DependsOn("postgresFlyway")
    fun mcpOAuthAuthorizations(dataSource: DataSource, clients: McpOAuthClientRepository) =
        McpOAuthAuthorizationService(dataSource, clients, clock)

    @Bean
    @DependsOn("postgresFlyway")
    fun mcpOAuthConsents(dataSource: DataSource, clients: McpOAuthClientRepository): OAuth2AuthorizationConsentService =
        JdbcOAuth2AuthorizationConsentService(JdbcTemplate(dataSource), clients)

    @Bean
    fun mcpOAuthAccessTokens(store: McpOAuthAuthorizationService, clients: McpOAuthClientRepository,
        users: UserDirectoryService, settings: McpOAuthSettings) =
        McpOAuthAccessTokens(store, clients, users, settings, clock)

    @Bean
    fun authorizationServerSettings(settings: McpOAuthSettings): AuthorizationServerSettings =
        AuthorizationServerSettings.builder().issuer(settings.issuer).build()

    @Bean
    fun mcpOAuthRequestCache() = HttpSessionRequestCache().apply {
        setRequestMatcher { it.method == "GET" && it.requestURI == "/oauth2/authorize" }
    }

    @Bean
    fun mcpOAuthSessionCookies(settings: McpOAuthSettings) = ServletContextInitializer { context ->
        context.sessionCookieConfig.name = "gromozeka_oauth_session"
        context.sessionCookieConfig.isHttpOnly = true
        context.sessionCookieConfig.isSecure = settings.issuer.startsWith("https://")
        context.sessionCookieConfig.setAttribute("SameSite", "Lax")
        context.sessionTimeout = 15
    }

    @Bean
    @Order(1)
    fun mcpOAuthSecurityFilterChain(
        http: HttpSecurity, settings: McpOAuthSettings, serverHttp: ServerHttpSettings,
        clients: McpOAuthClientRepository, store: McpOAuthAuthorizationService,
        consents: OAuth2AuthorizationConsentService, authorizationServerSettings: AuthorizationServerSettings,
        authentication: AuthenticationService, users: UserDirectoryService,
        mcpOAuthRequestCache: HttpSessionRequestCache,
    ): SecurityFilterChain {
        val asConfigurer = OAuth2AuthorizationServerConfigurer()
        val paths = PathPatternRequestMatcher.withDefaults()
        val failed = AuthenticationFailureHandler { _, response, exception ->
            response.writeOAuthError((exception as? OAuth2AuthenticationException)?.error?.errorCode ?: "invalid_request")
        }
        val access = OAuth2AccessTokenGenerator().apply {
            setClock(clock)
            setAccessTokenCustomizer { context ->
                val userId = context.getPrincipal<org.springframework.security.core.Authentication>()?.name
                    ?: throw oauthFailure("invalid_grant", "User is unavailable")
                val user = runBlocking { users.findActiveById(User.Id(userId)) }?.takeIf { it.canLogin }
                    ?: throw oauthFailure("invalid_grant", "User is unavailable")
                val original = context.authorization?.getAttribute<OAuth2AuthorizationRequest>(OAuth2AuthorizationRequest::class.java.name)
                if (original == null || !settings.acceptsResource(original.additionalParameters["resource"] as? String)) {
                    throw oauthFailure("invalid_target", "Invalid resource")
                }
                if (McpOAuthSettings.SCOPE !in context.authorizedScopes) throw oauthFailure("invalid_scope", "MCP scope is required")
                context.claims.issuer(settings.issuer).subject(user.id.value).audience(listOf(settings.resource))
            }
        }
        http.securityMatcher(OrRequestMatcher(asConfigurer.endpointsMatcher,
            paths.matcher("/oauth/**"), paths.matcher("/.well-known/oauth-protected-resource"),
            paths.matcher("/.well-known/oauth-protected-resource/mcp")))
            .with(asConfigurer) { server ->
                server.registeredClientRepository(clients).authorizationService(store).authorizationConsentService(consents)
                    .authorizationServerSettings(authorizationServerSettings)
                    .tokenGenerator(DelegatingOAuth2TokenGenerator(access, McpOAuthRefreshTokenGenerator(clock)))
                    .clientAuthentication { client ->
                        client.authenticationConverters { it.add(0, McpOAuthPublicClientConverter()) }
                        client.authenticationProviders { it.add(0, McpOAuthPublicClientProvider(clients, store)) }
                        client.errorResponseHandler(failed)
                    }
                    .authorizationEndpoint { endpoint ->
                        endpoint.consentPage("/oauth/consent")
                        endpoint.authenticationProviders { providers ->
                            providers.filterIsInstance<OAuth2AuthorizationCodeRequestAuthenticationProvider>().forEach { provider ->
                                provider.setAuthenticationValidator(LocalhostWildcardPortValidator()
                                    .andThen(OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR)
                                    .andThen(Consumer { context ->
                                        val request = context.getAuthentication<org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken>()
                                        fun reject(code: String): Nothing = throw org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException(
                                            org.springframework.security.oauth2.core.OAuth2Error(code, "OAuth request rejected", null), request)
                                        if (!settings.acceptsResource(request.additionalParameters["resource"] as? String)) reject("invalid_target")
                                        if (request.scopes != setOf(McpOAuthSettings.SCOPE)) reject("invalid_scope")
                                    }))
                            }
                        }
                    }
                    .tokenEndpoint { it.errorResponseHandler(failed) }
                    .tokenRevocationEndpoint { it.errorResponseHandler(failed) }
                    .authorizationServerMetadataEndpoint { endpoint ->
                        endpoint.authorizationServerMetadataCustomizer { metadata ->
                            metadata.claim("client_id_metadata_document_supported", true)
                                .grantTypes { it.clear(); it.addAll(listOf("authorization_code", "refresh_token")) }
                                .scopes { it.clear(); it.add(McpOAuthSettings.SCOPE) }
                                .tokenEndpointAuthenticationMethods { it.clear(); it.add("none") }
                                .tokenRevocationEndpointAuthenticationMethods { it.clear(); it.add("none") }
                                .claims { claims ->
                                    listOf("registration_endpoint", "introspection_endpoint", "introspection_endpoint_auth_methods_supported",
                                        "device_authorization_endpoint", "pushed_authorization_request_endpoint",
                                        "dpop_signing_alg_values_supported").forEach(claims::remove)
                                }
                        }
                    }
            }
            .authorizeHttpRequests {
                it.requestMatchers("/oauth/login", "/.well-known/**").permitAll()
                it.anyRequest().authenticated()
            }
            .formLogin { it.disable() }.httpBasic { it.disable() }.logout { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .securityContext { it.securityContextRepository(NullSecurityContextRepository()) }
            .requestCache { it.requestCache(mcpOAuthRequestCache) }
            .exceptionHandling {
                it.authenticationEntryPoint(LoginUrlAuthenticationEntryPoint("/oauth/login"))
                it.accessDeniedHandler { _, response, _ -> response.writeOAuthError("access_denied", HttpStatus.FORBIDDEN) }
            }
            // Install the standard CSRF filter explicitly so protocol configurers cannot
            // exempt the browser consent POST together with the machine token endpoints.
            .csrf { it.disable() }
            .addFilterAt(CsrfFilter(HttpSessionCsrfTokenRepository()).apply {
                setRequireCsrfProtectionMatcher { it.method == "POST" && it.requestURI in
                    setOf("/oauth/login", "/oauth2/authorize", "/oauth/connections/revoke") }
                setAccessDeniedHandler { _, response, _ -> response.writeOAuthError("access_denied", HttpStatus.FORBIDDEN) }
            }, CsrfFilter::class.java)
            .addFilterBefore(McpOAuthBoundaryFilter(settings, serverHttp), SecurityContextHolderFilter::class.java)
            // Anchor to built-in orders: AS-specific filters are registered only during build().
            .addFilterAfter(McpOAuthBrowserSessionFilter(authentication), SecurityContextHolderFilter::class.java)
            .addFilterBefore(McpOAuthTokenTransactionFilter(store, settings),
                org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter::class.java)
        return http.build()
    }
}

internal const val MCP_OAUTH_USER_ATTRIBUTE = "gromozeka.oauth.authenticated-user"

/** Reuses the ordinary opaque User session. No duplicate users/password database or Basic login. */
private class McpOAuthBrowserSessionFilter(private val authentication: AuthenticationService) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI != "/oauth2/authorize" && !request.requestURI.startsWith("/oauth/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val principal = request.sessionToken()?.let { token -> runBlocking { authentication.authenticate(token) } }
        if (principal != null) {
            request.setAttribute(MCP_OAUTH_USER_ATTRIBUTE, principal.user)
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal.user.id.value, null, emptyList())
        }
        chain.doFilter(request, response)
    }
}
