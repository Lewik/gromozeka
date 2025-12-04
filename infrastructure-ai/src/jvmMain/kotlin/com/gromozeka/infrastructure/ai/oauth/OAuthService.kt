package com.gromozeka.infrastructure.ai.oauth

import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

@Service
class OAuthService(
    private val oauthConfigService: OAuthConfigService,
) {
    private val log = KLoggers.logger(this)
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder().build()

    @Serializable
    data class OAuthTokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
    )

    data class PKCEChallenge(
        val verifier: String,
        val challenge: String,
    )

    data class AuthorizationUrl(
        val url: String,
        val verifier: String,
    )

    fun generatePKCE(): PKCEChallenge {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        return PKCEChallenge(verifier, challenge)
    }

    private fun generateCodeVerifier(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    fun getAuthorizationUrl(mode: OAuthMode = OAuthMode.MAX): AuthorizationUrl {
        val config = oauthConfigService.getConfig()
            ?: throw IllegalStateException("OAuth config not found. Please configure oauth.json")
        val pkce = generatePKCE()
        val baseUrl = when (mode) {
            OAuthMode.MAX -> config.authorizeUrlMax
            OAuthMode.CONSOLE -> config.authorizeUrlConsole
        }

        val url = buildString {
            append(baseUrl)
            append("?code=true")
            append("&client_id=").append(java.net.URLEncoder.encode(config.clientId, "UTF-8"))
            append("&response_type=code")
            append("&redirect_uri=").append(java.net.URLEncoder.encode(config.redirectUri, "UTF-8"))
            append("&scope=").append(java.net.URLEncoder.encode(config.scope, "UTF-8"))
            append("&code_challenge=").append(java.net.URLEncoder.encode(pkce.challenge, "UTF-8"))
            append("&code_challenge_method=S256")
            append("&state=").append(java.net.URLEncoder.encode(pkce.verifier, "UTF-8"))
        }

        log.info("Generated authorization URL for mode: $mode")
        return AuthorizationUrl(url, pkce.verifier)
    }

    suspend fun exchangeCodeForTokens(code: String, verifier: String): Result<OAuthTokens> =
        withContext(Dispatchers.IO) {
            try {
                val config = oauthConfigService.getConfig()
                    ?: throw IllegalStateException("OAuth config not found. Please configure oauth.json")
                val splits = code.split("#")
                val actualCode = splits[0]
                val state = splits.getOrNull(1) ?: ""

                val requestBody = json.encodeToString(
                    TokenExchangeRequest.serializer(),
                    TokenExchangeRequest(
                        code = actualCode,
                        state = state,
                        grantType = "authorization_code",
                        clientId = config.clientId,
                        redirectUri = config.redirectUri,
                        codeVerifier = verifier
                    )
                )

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(config.tokenUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                log.info { "Token exchange response: ${response.statusCode()}" }
                log.info { "Token exchange response body: ${response.body()}" }

                if (response.statusCode() != 200) {
                    log.error { "Token exchange failed: ${response.statusCode()} - ${response.body()}" }
                    return@withContext Result.failure(Exception("Token exchange failed: ${response.statusCode()} - ${response.body()}"))
                }

                val tokenResponse = json.decodeFromString<TokenResponse>(response.body())
                val tokens = OAuthTokens(
                    accessToken = tokenResponse.access_token,
                    refreshToken = tokenResponse.refresh_token,
                    expiresAt = System.currentTimeMillis() + (tokenResponse.expires_in * 1000)
                )

                oauthConfigService.updateTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
                log.info("Successfully exchanged code for tokens")
                Result.success(tokens)
            } catch (e: Exception) {
                log.error(e) { "Failed to exchange code for tokens" }
                Result.failure(e)
            }
        }

    suspend fun refreshTokens(refreshToken: String): Result<OAuthTokens> = withContext(Dispatchers.IO) {
        try {
            val config = oauthConfigService.getConfig()
                ?: throw IllegalStateException("OAuth config not found. Please configure oauth.json")
            val requestBody = json.encodeToString(
                RefreshTokenRequest.serializer(),
                RefreshTokenRequest(
                    grantType = "refresh_token",
                    refreshToken = refreshToken,
                    clientId = config.clientId
                )
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create(config.tokenUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != 200) {
                log.error { "Token refresh failed: ${response.statusCode()} - ${response.body()}" }
                return@withContext Result.failure(Exception("Token refresh failed: ${response.statusCode()}"))
            }

            val tokenResponse = json.decodeFromString<TokenResponse>(response.body())
            val tokens = OAuthTokens(
                accessToken = tokenResponse.access_token,
                refreshToken = tokenResponse.refresh_token,
                expiresAt = System.currentTimeMillis() + (tokenResponse.expires_in * 1000)
            )

            oauthConfigService.updateTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
            log.info("Successfully refreshed tokens")
            Result.success(tokens)
        } catch (e: Exception) {
            log.error(e) { "Failed to refresh tokens" }
            Result.failure(e)
        }
    }

    enum class OAuthMode {
        MAX,
        CONSOLE
    }

    @Serializable
    private data class TokenExchangeRequest(
        val code: String,
        val state: String,
        @SerialName("grant_type") val grantType: String,
        @SerialName("client_id") val clientId: String,
        @SerialName("redirect_uri") val redirectUri: String,
        @SerialName("code_verifier") val codeVerifier: String,
    )

    @Serializable
    private data class RefreshTokenRequest(
        @SerialName("grant_type") val grantType: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("client_id") val clientId: String,
    )

    @Serializable
    private data class TokenResponse(
        val access_token: String,
        val refresh_token: String,
        val expires_in: Long,
    )
}
