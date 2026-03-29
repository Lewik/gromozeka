package com.gromozeka.infrastructure.ai.openai.subscription

import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Service
class OpenAiSubscriptionAuthService(
    private val configService: OpenAiSubscriptionConfigService,
) {
    private val log = KLoggers.logger(this)
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder().build()

    fun getAuthorizationUrl(originator: String = "gromozeka"): OpenAiSubscriptionAuthorizationUrl {
        val config = configService.load()
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = generateState()

        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri,
            "scope" to config.scope,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "state" to state,
            "originator" to originator
        )

        val query = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }

        return OpenAiSubscriptionAuthorizationUrl(
            url = "${config.issuer}/oauth/authorize?$query",
            verifier = verifier,
            state = state
        )
    }

    suspend fun exchangeCodeForTokens(
        code: String,
        verifier: String,
    ): Result<OpenAiSubscriptionSession> = withContext(Dispatchers.IO) {
        runCatching {
            val config = configService.load()
            val actualCode = code.substringBefore("#")

            val request = HttpRequest.newBuilder()
                .uri(URI.create("${config.issuer}/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        buildFormBody(
                            mapOf(
                                "grant_type" to "authorization_code",
                                "code" to actualCode,
                                "redirect_uri" to config.redirectUri,
                                "client_id" to config.clientId,
                                "code_verifier" to verifier
                            )
                        )
                    )
                )
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) {
                "OpenAI token exchange failed: ${response.statusCode()} ${response.body()}"
            }

            val tokenResponse = json.decodeFromString<TokenResponse>(response.body())
            val session = tokenResponse.toSession()
            configService.updateSession(session)
            session
        }
    }

    suspend fun refreshTokens(
        refreshToken: String,
    ): Result<OpenAiSubscriptionSession> = withContext(Dispatchers.IO) {
        runCatching {
            val config = configService.load()

            val request = HttpRequest.newBuilder()
                .uri(URI.create("${config.issuer}/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        buildFormBody(
                            mapOf(
                                "grant_type" to "refresh_token",
                                "refresh_token" to refreshToken,
                                "client_id" to config.clientId
                            )
                        )
                    )
                )
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) {
                "OpenAI token refresh failed: ${response.statusCode()} ${response.body()}"
            }

            val tokenResponse = json.decodeFromString<TokenResponse>(response.body())
            val current = configService.getSession()
            val session = tokenResponse.toSession(
                fallbackRefreshToken = current?.refreshToken ?: refreshToken,
                fallbackIdToken = current?.idToken,
                fallbackAccountId = current?.accountId
            )
            configService.updateSession(session)
            session
        }
    }

    suspend fun getValidSession(): OpenAiSubscriptionSession {
        val current = configService.getSession()
            ?: error("OpenAI subscription is not configured. Authenticate first.")

        if (current.expiresAt > System.currentTimeMillis() + REFRESH_SAFETY_MARGIN_MS) {
            return current
        }

        log.info("OpenAI subscription access token expired or near expiry, refreshing")
        return refreshTokens(current.refreshToken).getOrThrow()
    }

    private fun buildFormBody(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(digest.digest(verifier.toByteArray()))
    }

    private fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun TokenResponse.toSession(
        fallbackRefreshToken: String? = null,
        fallbackIdToken: String? = null,
        fallbackAccountId: String? = null,
    ): OpenAiSubscriptionSession {
        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)
        val resolvedIdToken = idToken ?: fallbackIdToken
        val resolvedAccountId = extractAccountId(idToken ?: accessToken) ?: fallbackAccountId

        return OpenAiSubscriptionSession(
            accessToken = accessToken,
            refreshToken = refreshToken ?: fallbackRefreshToken
            ?: error("OpenAI subscription refresh token is missing"),
            idToken = resolvedIdToken,
            accountId = resolvedAccountId,
            expiresAt = expiresAt
        )
    }

    private fun extractAccountId(token: String?): String? {
        val claims = parseJwtClaims(token) ?: return null

        val direct = claims.chatGptAccountId
        if (!direct.isNullOrBlank()) return direct

        val nested = claims.openAiAuth?.chatGptAccountId
        if (!nested.isNullOrBlank()) return nested

        return claims.organizations.firstOrNull()?.id
    }

    private fun parseJwtClaims(token: String?): JwtClaims? {
        if (token.isNullOrBlank()) return null
        val parts = token.split(".")
        if (parts.size != 3) return null

        return runCatching {
            val payload = Base64.getUrlDecoder().decode(parts[1])
            json.decodeFromString<JwtClaims>(payload.decodeToString())
        }.getOrNull()
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("id_token") val idToken: String? = null,
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long,
    )

    @Serializable
    private data class JwtClaims(
        @SerialName("chatgpt_account_id") val chatGptAccountId: String? = null,
        @SerialName("organizations") val organizations: List<OrganizationClaim> = emptyList(),
        @SerialName("https://api.openai.com/auth") val openAiAuth: OpenAiAuthClaim? = null,
    )

    @Serializable
    private data class OrganizationClaim(
        val id: String? = null,
    )

    @Serializable
    private data class OpenAiAuthClaim(
        @SerialName("chatgpt_account_id") val chatGptAccountId: String? = null,
    )

    private companion object {
        const val REFRESH_SAFETY_MARGIN_MS = 60_000L
    }
}
