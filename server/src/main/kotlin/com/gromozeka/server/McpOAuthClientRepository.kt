package com.gromozeka.server

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.client.metadata.ClientIdMetadataDocumentResolver
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.time.Clock
import javax.sql.DataSource

/** CIMD is the normal registration path; approved snapshots survive Server restarts. */
internal class McpOAuthClientRepository(
    dataSource: DataSource,
    private val settings: McpOAuthSettings,
    private val resolver: ClientIdMetadataDocumentResolver,
    private val clock: Clock,
) : RegisteredClientRepository {
    private val jdbc = JdbcTemplate(dataSource)
    private val transactions = TransactionTemplate(JdbcTransactionManager(dataSource)).apply {
        isolationLevel = org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED
    }
    private val stored = JdbcRegisteredClientRepository(jdbc)
    private val cache = LinkedHashMap<String, CachedClient>(16, 0.75f, true)

    override fun findByClientId(clientId: String): RegisteredClient? {
        if (!clientId.startsWith("https://")) return stored.findByClientId(clientId)
        val uri = validateOAuthClientId(clientId)
        synchronized(cache) {
            cache[clientId]?.takeIf { clock.millis() < it.validUntil }?.let { return it.client }
        }
        val result = try { resolver.resolve(uri) } catch (_: Exception) {
            throw oauthFailure("invalid_client", "Client metadata is unavailable or invalid")
        }
        val client = clientFromMetadata(clientId, result.clientRegistration().claims)
        val now = clock.millis()
        val maxAge = result.responseAttributes().cacheMaxAgeSeconds().coerceIn(0, 300)
        synchronized(cache) {
            cache.entries.removeIf { it.value.retainUntil <= now }
            cache[clientId] = CachedClient(client, now + maxAge * 1000, now + 15 * 60 * 1000)
            while (cache.size > 128) cache.entries.iterator().run { next(); remove() }
        }
        return client
    }

    override fun findById(id: String): RegisteredClient? = synchronized(cache) {
        cache.values.firstOrNull { it.client.id == id && clock.millis() < it.retainUntil }?.client
    } ?: stored.findById(id)

    /** Refresh/revoke must not fetch a new URL supplied by an unauthenticated caller. */
    fun findStoredByClientId(clientId: String): RegisteredClient? = stored.findByClientId(clientId)

    override fun save(registeredClient: RegisteredClient) {
        require(registeredClient.clientAuthenticationMethods == setOf(ClientAuthenticationMethod.NONE) &&
            registeredClient.authorizationGrantTypes.all { it in SUPPORTED_GRANTS } &&
            AuthorizationGrantType.AUTHORIZATION_CODE in registeredClient.authorizationGrantTypes &&
            registeredClient.scopes == setOf(McpOAuthSettings.SCOPE) &&
            registeredClient.clientSettings.isRequireProofKey && registeredClient.clientSettings.isRequireAuthorizationConsent &&
            registeredClient.tokenSettings.accessTokenFormat == OAuth2TokenFormat.REFERENCE &&
            !registeredClient.tokenSettings.isReuseRefreshTokens) { "Unsupported MCP OAuth client policy" }
        transactions.executeWithoutResult {
            // Serialize concurrent first approvals for the same client without broad table locking.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", { _, _ -> Unit },
                "gromozeka:oauth-client:${registeredClient.clientId}")
            stored.save(registeredClient)
        }
    }

    fun persistForAuthorization(id: String) {
        val client = findById(id) ?: throw oauthFailure("invalid_client", "Client metadata is no longer available")
        save(client)
    }

    internal fun clientFromMetadata(clientId: String, metadata: Map<String, Any>): RegisteredClient {
        fun reject(): Nothing = throw oauthFailure("invalid_client", "Unsupported or invalid client metadata")
        if (metadata["client_id"] != clientId || metadata.containsKey("client_secret")) reject()
        val name = metadata["client_name"] as? String ?: reject()
        if (name.isBlank() || name.length > 200 || name.any { it < ' ' }) reject()
        val redirects = strings(metadata["redirect_uris"]) ?: reject()
        if (redirects.size !in 1..10 || redirects.sumOf { it.length + 1 } > 8192 || redirects.any { !validRedirect(it) }) reject()
        val methods = metadata["token_endpoint_auth_methods_supported"]?.let { strings(it) ?: reject() }
        if (methods != null) {
            if ("none" !in methods) reject()
        } else if ((metadata["token_endpoint_auth_method"] ?: "none") != "none") reject()
        val grants = metadata["grant_types"]?.let { strings(it) ?: reject() } ?: listOf("authorization_code")
        if ("authorization_code" !in grants) reject()
        val responses = metadata["response_types"]?.let { strings(it) ?: reject() } ?: listOf("code")
        if ("code" !in responses) reject()
        val existing = stored.findByClientId(clientId)
        return RegisteredClient.withId("cimd-" + oauthTokenHash(clientId))
            .clientId(clientId).clientIdIssuedAt(existing?.clientIdIssuedAt ?: clock.instant()).clientName(name)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .apply { if ("refresh_token" in grants) authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN) }
            .redirectUris { it.addAll(redirects) }
            .scope(McpOAuthSettings.SCOPE)
            .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
            .tokenSettings(TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                .accessTokenTimeToLive(settings.accessTokenLifetime)
                .authorizationCodeTimeToLive(settings.authorizationCodeLifetime)
                .refreshTokenTimeToLive(settings.refreshTokenLifetime).reuseRefreshTokens(false).build())
            .build()
    }

    private fun strings(value: Any?): List<String>? = (value as? List<*>)?.let { list ->
        if (list.any { it !is String }) null else list.filterIsInstance<String>()
    }

    private fun validRedirect(value: String): Boolean = runCatching {
        val uri = URI(value)
        value.length in 1..2048 && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535) &&
            (uri.scheme == "https" || (uri.scheme == "http" && isOAuthLoopbackHost(uri.host)))
    }.getOrDefault(false)

    private data class CachedClient(val client: RegisteredClient, val validUntil: Long, val retainUntil: Long)
    private companion object {
        val SUPPORTED_GRANTS = setOf(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN)
    }
}
