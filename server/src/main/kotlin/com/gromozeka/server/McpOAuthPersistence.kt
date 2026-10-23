package com.gromozeka.server

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

/** Framework JDBC persistence plus the missing atomic rotation/reuse boundary. */
internal class McpOAuthAuthorizationService(
    dataSource: DataSource,
    private val clients: McpOAuthClientRepository,
    private val clock: Clock,
) : OAuth2AuthorizationService {
    private val jdbc = JdbcTemplate(dataSource)
    private val transactions = TransactionTemplate(JdbcTransactionManager(dataSource)).apply {
        isolationLevel = org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED
    }
    private val delegate = JdbcOAuth2AuthorizationService(jdbc, clients)

    override fun save(authorization: OAuth2Authorization) {
        transactions.executeWithoutResult {
            // Keep grant -> client lock ordering identical to token rotation/revocation.
            lock(authorization.id)
            // CIMD lookups before login are bounded transient data, not anonymous DB writes.
            // Persist the client snapshot for an authenticated authorization attempt.
            clients.persistForAuthorization(authorization.registeredClientId)
            delegate.save(authorization)
            authorization.refreshToken?.token?.let { token ->
                jdbc.update("""
                    INSERT INTO oauth2_refresh_history(token_hash, authorization_id, expires_at)
                    VALUES (?, ?, ?) ON CONFLICT (token_hash) DO NOTHING
                """.trimIndent(), oauthTokenHash(token.tokenValue), authorization.id,
                    Timestamp.from(checkNotNull(token.expiresAt)))
            }
        }
    }

    override fun remove(authorization: OAuth2Authorization) {
        transactions.executeWithoutResult {
            lock(authorization.id)
            delegate.remove(authorization) // Also removes its refresh history through the FK.
        }
    }

    override fun findById(id: String): OAuth2Authorization? = delegate.findById(id)

    override fun findByToken(token: String, tokenType: OAuth2TokenType?): OAuth2Authorization? {
        val current = delegate.findByToken(token, tokenType)
        if (current != null || tokenType != OAuth2TokenType.REFRESH_TOKEN) return current
        val oldGrant = historicalGrantId(token) ?: return null
        // The token endpoint has already authenticated/identified the exact client.
        // A different client cannot revoke someone's grant by presenting a token.
        val caller = SecurityContextHolder.getContext().authentication as? OAuth2ClientAuthenticationToken
        val registeredClientId = caller?.takeIf { it.isAuthenticated }?.registeredClient?.id ?: return null
        val grant = delegate.findById(oldGrant) ?: return null
        if (grant.registeredClientId == registeredClientId) {
            check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                "OAuth refresh validation requires the token transaction"
            }
            delegate.remove(grant)
        }
        return null
    }

    /** Called around the synchronous framework token/revocation filter chain, not just save(). */
    fun inTokenTransaction(presentedToken: String?, block: () -> Unit) {
        transactions.executeWithoutResult {
            if (presentedToken != null && presentedToken.length <= McpOAuthSettings.MAX_TOKEN_LENGTH) {
                grantId(presentedToken)?.let(::lock)
            }
            block()
        }
    }

    fun revokeTokenForClient(token: String, registeredClientId: String) {
        check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            "OAuth revocation requires the token transaction"
        }
        val id = grantId(token) ?: return
        val grant = delegate.findById(id) ?: return
        if (grant.registeredClientId == registeredClientId) delegate.remove(grant)
    }

    fun revokeOwned(principalName: String, authorizationId: String): Boolean = transactions.execute {
        lock(authorizationId)
        jdbc.update("DELETE FROM oauth2_authorization WHERE id = ? AND principal_name = ?",
            authorizationId, principalName) == 1
    } ?: false

    fun connections(principalName: String): List<McpOAuthConnection> = jdbc.query("""
        SELECT a.id, c.client_id, c.client_name, a.access_token_issued_at,
               GREATEST(a.refresh_token_expires_at, a.access_token_expires_at) AS expires_at
        FROM oauth2_authorization a JOIN oauth2_registered_client c ON c.id = a.registered_client_id
        WHERE a.principal_name = ? AND a.access_token_value IS NOT NULL
          AND GREATEST(a.refresh_token_expires_at, a.access_token_expires_at) > ?
        ORDER BY a.access_token_issued_at DESC LIMIT 100
    """.trimIndent(), { row, _ -> McpOAuthConnection(row.getString("id"), row.getString("client_id"),
        row.getString("client_name"), row.getTimestamp("expires_at").toInstant()) },
        principalName, Timestamp.from(clock.instant()))

    private fun grantId(token: String): String? = jdbc.query("""
        SELECT id FROM oauth2_authorization
        WHERE authorization_code_value = ? OR refresh_token_value = ? OR access_token_value = ?
        LIMIT 1
    """.trimIndent(), { row, _ -> row.getString(1) }, token, token, token).firstOrNull()
        ?: historicalGrantId(token)

    private fun historicalGrantId(token: String): String? = jdbc.query(
        "SELECT authorization_id FROM oauth2_refresh_history WHERE token_hash = ?",
        { row, _ -> row.getString(1) }, oauthTokenHash(token)).firstOrNull()

    private fun lock(authorizationId: String) {
        jdbc.query("SELECT id FROM oauth2_authorization WHERE id = ? FOR UPDATE",
            { row, _ -> row.getString(1) }, authorizationId)
    }
}

internal data class McpOAuthConnection(val id: String, val clientId: String, val clientName: String, val expiresAt: Instant)

internal fun oauthTokenHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
