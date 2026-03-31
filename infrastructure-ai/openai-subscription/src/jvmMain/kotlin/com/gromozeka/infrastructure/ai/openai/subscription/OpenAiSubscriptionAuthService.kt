package com.gromozeka.infrastructure.ai.openai.subscription

import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.max

@Service
class OpenAiSubscriptionAuthService(
    private val configService: OpenAiSubscriptionConfigService,
) {
    private val log = KLoggers.logger(this)
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder().build()

    fun getAuthorizationUrl(): OpenAiSubscriptionAuthorizationUrl {
        val config = configService.load()
        return buildAuthorizationUrl(config, includeRedirectUri = config.includeRedirectUri)
    }

    fun getBrowserAuthorizationUrl(): OpenAiSubscriptionAuthorizationUrl {
        val config = configService.load()
        return buildAuthorizationUrl(config, includeRedirectUri = true)
    }

    private fun buildAuthorizationUrl(
        config: OpenAiSubscriptionConfig,
        includeRedirectUri: Boolean,
    ): OpenAiSubscriptionAuthorizationUrl {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = generateState()

        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to config.clientId,
            "scope" to config.scope,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "state" to state,
        )
        if (includeRedirectUri) {
            params["redirect_uri"] = config.redirectUri
        }

        val query = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }

        return OpenAiSubscriptionAuthorizationUrl(
            url = "${config.issuer}/oauth/authorize?$query",
            verifier = verifier,
            state = state,
        )
    }

    suspend fun requestDeviceCode(): Result<OpenAiSubscriptionPendingDeviceCode> = withContext(Dispatchers.IO) {
        runCatching {
            val config = configService.load()
            val baseUrl = config.issuer.trimEnd('/')

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/accounts/deviceauth/usercode"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.encodeToString(DeviceCodeRequest(clientId = config.clientId))
                    )
                )
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) {
                if (response.statusCode() == 404) {
                    "OpenAI device code login is not enabled for this server"
                } else {
                    "OpenAI device code request failed: ${response.statusCode()} ${response.body()}"
                }
            }

            val payload = json.parseToJsonElement(response.body()).jsonObject
            val deviceAuthId = payload["device_auth_id"]?.jsonPrimitive?.content
                ?: error("OpenAI device code response is missing device_auth_id")
            val userCode = payload["user_code"]?.jsonPrimitive?.content
                ?: payload["usercode"]?.jsonPrimitive?.content
                ?: error("OpenAI device code response is missing user_code")
            val intervalSeconds = max(1L, payload["interval"]?.jsonPrimitive?.content?.trim()?.toLongOrNull() ?: 5L)

            OpenAiSubscriptionPendingDeviceCode(
                verificationUrl = "$baseUrl/codex/device",
                userCode = userCode,
                deviceAuthId = deviceAuthId,
                intervalSeconds = intervalSeconds,
                expiresAt = System.currentTimeMillis() + DEVICE_CODE_EXPIRATION_MS,
            )
        }
    }

    suspend fun completeDeviceCodeLogin(
        deviceCode: OpenAiSubscriptionPendingDeviceCode,
    ): Result<OpenAiSubscriptionSession> = withContext(Dispatchers.IO) {
        runCatching {
            val config = configService.load()
            val baseUrl = config.issuer.trimEnd('/')
            val authorization = pollForDeviceAuthorizationCode(baseUrl, deviceCode)
            val session = exchangeAuthorizationCode(
                issuer = config.issuer,
                clientId = config.clientId,
                code = authorization.authorizationCode,
                verifier = authorization.codeVerifier,
                redirectUri = "$baseUrl/deviceauth/callback",
            )
            configService.updateSession(session)
            session
        }
    }

    suspend fun exchangeCodeForTokens(
        code: String,
        verifier: String,
    ): Result<OpenAiSubscriptionSession> = withContext(Dispatchers.IO) {
        runCatching {
            val config = configService.load()
            val session = exchangeAuthorizationCode(
                issuer = config.issuer,
                clientId = config.clientId,
                code = code,
                verifier = verifier,
                redirectUri = config.redirectUri.takeIf { config.includeRedirectUri },
            )
            configService.updateSession(session)
            session
        }
    }

    suspend fun exchangeBrowserCodeForTokens(
        code: String,
        verifier: String,
    ): Result<OpenAiSubscriptionSession> = withContext(Dispatchers.IO) {
        runCatching {
            val config = configService.load()
            val session = exchangeAuthorizationCode(
                issuer = config.issuer,
                clientId = config.clientId,
                code = code,
                verifier = verifier,
                redirectUri = config.redirectUri,
            )
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
                                "client_id" to config.clientId,
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
                fallbackAccountId = current?.accountId,
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

    private fun exchangeAuthorizationCode(
        issuer: String,
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String?,
    ): OpenAiSubscriptionSession {
        val actualCode = code.substringBefore("#")
        val params = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to actualCode,
            "client_id" to clientId,
            "code_verifier" to verifier,
        )
        if (!redirectUri.isNullOrBlank()) {
            params["redirect_uri"] = redirectUri
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${issuer.trimEnd('/')}/oauth/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(buildFormBody(params)))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "OpenAI token exchange failed: ${response.statusCode()} ${response.body()}"
        }

        val tokenResponse = json.decodeFromString<TokenResponse>(response.body())
        return tokenResponse.toSession()
    }

    private fun pollForDeviceAuthorizationCode(
        baseUrl: String,
        deviceCode: OpenAiSubscriptionPendingDeviceCode,
    ): DeviceCodeAuthorizationResponse {
        while (true) {
            if (System.currentTimeMillis() >= deviceCode.expiresAt) {
                error("OpenAI device code expired before authorization completed")
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/accounts/deviceauth/token"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.encodeToString(
                            DeviceCodePollRequest(
                                deviceAuthId = deviceCode.deviceAuthId,
                                userCode = deviceCode.userCode,
                            )
                        )
                    )
                )
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> return json.decodeFromString(response.body())
                403, 404 -> Thread.sleep(deviceCode.intervalSeconds * 1000L)
                else -> error("OpenAI device code poll failed: ${response.statusCode()} ${response.body()}")
            }
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
            expiresAt = expiresAt,
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
    private data class DeviceCodeRequest(
        @SerialName("client_id") val clientId: String,
    )

    @Serializable
    private data class DeviceCodePollRequest(
        @SerialName("device_auth_id") val deviceAuthId: String,
        @SerialName("user_code") val userCode: String,
    )

    @Serializable
    private data class DeviceCodeAuthorizationResponse(
        @SerialName("authorization_code") val authorizationCode: String,
        @SerialName("code_challenge") val codeChallenge: String? = null,
        @SerialName("code_verifier") val codeVerifier: String,
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
        const val DEVICE_CODE_EXPIRATION_MS = 15 * 60 * 1000L
    }
}
