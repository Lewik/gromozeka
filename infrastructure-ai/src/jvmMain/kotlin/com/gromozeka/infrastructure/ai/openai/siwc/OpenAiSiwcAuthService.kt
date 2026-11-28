package com.gromozeka.infrastructure.ai.openai.siwc

import com.gromozeka.infrastructure.ai.diagnostics.AiTrafficDumpHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

internal const val OPENAI_SIWC_CALLBACK_PATH = "/auth/callback"

@Service
internal class OpenAiSiwcAuthService @Autowired constructor(
    private val store: OpenAiSiwcCredentialStore,
) : OpenAiSiwcAccessTokenProvider {
    private var issuer = DEFAULT_ISSUER
    private var nowMillis: () -> Long = System::currentTimeMillis
    private var client = AiTrafficDumpHttpClient(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build(),
        "openai-siwc-auth",
    )
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var discovered: Discovery? = null

    @Volatile
    private var signingKeys: Map<String, RSAPublicKey> = emptyMap()

    internal constructor(
        store: OpenAiSiwcCredentialStore,
        issuer: String,
        httpClient: HttpClient,
        nowMillis: () -> Long = System::currentTimeMillis,
    ) : this(store) {
        this.issuer = issuer.trimEnd('/')
        this.nowMillis = nowMillis
        this.client = AiTrafficDumpHttpClient(httpClient, "openai-siwc-auth-test")
    }

    data class Authorization(
        val url: String,
        val state: String,
        val nonce: String,
        val verifier: String,
        val redirectUri: String,
        val existingClientId: String?,
    )

    fun prepareAuthorization(redirectUri: String): Authorization {
        val callback = URI.create(redirectUri)
        require(callback.scheme == "http" && callback.host == "127.0.0.1" && callback.port in 1..65535 &&
            callback.path == OPENAI_SIWC_CALLBACK_PATH && callback.rawQuery == null && callback.rawFragment == null) {
            "SIWC callback must use an IPv4 loopback URL ending in $OPENAI_SIWC_CALLBACK_PATH"
        }
        val provider = discovery()
        val saved = store.read()
        val state = randomValue()
        val nonce = randomValue()
        val verifier = randomValue()
        val clientId = saved?.clientId
        val parameters = linkedMapOf(
            "client_id" to (clientId ?: DYNAMIC_CLIENT_ID),
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "scope" to SCOPES.joinToString(" "),
            "resource" to RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())),
            "ext_agent_host_id" to store.hostId(),
        )
        if (clientId == null) parameters["agent_name_hint"] = APP_NAME
        val query = parameters.entries.joinToString("&") { (name, value) -> "${encoded(name)}=${encoded(value)}" }
        return Authorization(
            url = "${provider.authorizationEndpoint}?$query",
            state = state,
            nonce = nonce,
            verifier = verifier,
            redirectUri = redirectUri,
            existingClientId = clientId,
        )
    }

    fun completeAuthorization(authorization: Authorization, code: String, returnedClientId: String?): OpenAiSiwcStoredState {
        val clientId = returnedClientId ?: authorization.existingClientId
            ?: throw OpenAiSiwcException("registration_incomplete", "ChatGPT did not complete app registration.")
        validateIssuedClientId(clientId)
        if (authorization.existingClientId != null && returnedClientId != null && authorization.existingClientId != returnedClientId) {
            throw OpenAiSiwcException("registration_mismatch", "ChatGPT returned a different app registration.")
        }

        // The authorization code is short-lived; retain a newly issued registration before exchanging it.
        store.update { current ->
            require(current?.clientId == null || current.clientId == clientId) { "The saved SIWC registration changed" }
            (current ?: OpenAiSiwcStoredState()).copy(clientId = clientId, savedAt = Instant.ofEpochMilli(nowMillis()).toString()) to Unit
        }

        val token = tokenRequest(linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "code_verifier" to authorization.verifier,
            "redirect_uri" to authorization.redirectUri,
            "resource" to RESOURCE,
        ), previousScopes = null, requireRefresh = true)
        val idToken = token.idToken
            ?: throw OpenAiSiwcException("invalid_id_token", "ChatGPT did not return a verifiable identity.")
        val identity = verifyIdentity(idToken, clientId, authorization.nonce, nowMillis())
        requireSharingScope(token.scopes)
        return store.update { current ->
            require(current?.clientId == clientId) { "The saved SIWC registration changed" }
            if (current.subject != null && current.subject != identity.subject) {
                throw OpenAiSiwcException("account_mismatch", "This sign-in returned a different ChatGPT account.")
            }
            val connected = current.copy(
                status = OpenAiSiwcStoredState.Status.CONNECTED,
                scopes = token.scopes,
                subject = identity.subject,
                identity = identity.identity,
                credentials = token.credentials,
                pendingRefresh = null,
                savedAt = Instant.ofEpochMilli(nowMillis()).toString(),
            )
            connected to connected
        }
    }

    override suspend fun validAccessToken(): String = withContext(Dispatchers.IO) {
        store.lockedState {
            var current = state ?: throw OpenAiSiwcException("sign_in_required", "Sign in with ChatGPT to continue.")
            if (current.status != OpenAiSiwcStoredState.Status.CONNECTED || current.clientId == null) {
                throw OpenAiSiwcException("sign_in_required", "Sign in with ChatGPT to continue.")
            }
            try {
                current.pendingRefresh?.let { pending ->
                    current = finishPendingRefresh(current, pending)
                    save(current)
                }
                requireSharingScope(current.scopes)
                val credentials = current.credentials
                    ?: throw OpenAiSiwcException("sign_in_required", "The ChatGPT connection has no usable credentials.")
                val now = nowMillis()
                if (credentials.expiresAtMillis > now + REFRESH_MARGIN_MILLIS) return@lockedState credentials.accessToken
                val earliest = credentials.earliestRefreshAtMillis
                if (earliest != null && earliest > now) {
                    if (credentials.expiresAtMillis <= now) {
                        throw OpenAiSiwcException("refresh_not_ready", "The ChatGPT connection cannot refresh yet.", retryable = true)
                    }
                    return@lockedState credentials.accessToken
                }
                current = refresh(current)
                save(current)
                val refreshed = current.credentials
                    ?: throw OpenAiSiwcException("sign_in_required", "The ChatGPT connection has no usable credentials.")
                if (refreshed.expiresAtMillis <= nowMillis()) {
                    throw OpenAiSiwcException("refresh_not_ready", "The ChatGPT connection was recovered. Try again.", retryable = true)
                }
                refreshed.accessToken
            } catch (error: OpenAiSiwcException) {
                if (error.code in TERMINAL_REFRESH_CODES || error.status == 400 || error.status == 401) {
                    save((state ?: current).copy(
                        status = OpenAiSiwcStoredState.Status.REAUTH_REQUIRED,
                        scopes = emptyList(),
                        credentials = null,
                        pendingRefresh = null,
                        savedAt = Instant.ofEpochMilli(nowMillis()).toString(),
                    ))
                }
                throw error
            }
        }
    }

    fun session(): OpenAiSiwcStoredState? = store.read()

    private fun OpenAiSiwcCredentialStore.LockedState.refresh(current: OpenAiSiwcStoredState): OpenAiSiwcStoredState {
        val credentials = current.credentials
            ?: throw OpenAiSiwcException("invalid_grant", "The ChatGPT connection has expired. Sign in again.")
        val token = tokenRequest(linkedMapOf(
            "grant_type" to "refresh_token",
            "client_id" to requireNotNull(current.clientId),
            "refresh_token" to credentials.refreshToken,
            "resource" to RESOURCE,
        ), previousScopes = current.scopes, requireRefresh = true)
        requireSharingScope(token.scopes)
        val idToken = token.idToken ?: return current.copy(
            scopes = token.scopes,
            credentials = token.credentials,
            savedAt = Instant.ofEpochMilli(nowMillis()).toString(),
        )
        val pending = OpenAiSiwcStoredState.PendingRefresh(
            credentials = token.credentials,
            scopes = token.scopes,
            idToken = idToken,
            receivedAtMillis = nowMillis(),
        )
        val checkpoint = current.copy(pendingRefresh = pending, savedAt = Instant.ofEpochMilli(nowMillis()).toString())
        save(checkpoint)
        return finishPendingRefresh(checkpoint, pending)
    }

    private fun finishPendingRefresh(
        current: OpenAiSiwcStoredState,
        pending: OpenAiSiwcStoredState.PendingRefresh,
    ): OpenAiSiwcStoredState {
        val clientId = current.clientId
            ?: throw OpenAiSiwcException("invalid_grant", "The ChatGPT registration is missing. Sign in again.")
        val identity = verifyIdentity(pending.idToken, clientId, nonce = null, receivedAtMillis = pending.receivedAtMillis)
        if (current.subject != null && current.subject != identity.subject) {
            throw OpenAiSiwcException("account_mismatch", "The refreshed ChatGPT identity does not match this connection.")
        }
        requireSharingScope(pending.scopes)
        return current.copy(
            status = OpenAiSiwcStoredState.Status.CONNECTED,
            scopes = pending.scopes,
            subject = identity.subject,
            identity = identity.identity,
            credentials = pending.credentials,
            pendingRefresh = null,
            savedAt = Instant.ofEpochMilli(nowMillis()).toString(),
        )
    }

    private fun tokenRequest(
        parameters: Map<String, String>,
        previousScopes: List<String>?,
        requireRefresh: Boolean,
    ): TokenFields {
        val response = send(
            HttpRequest.newBuilder(URI.create(discovery().tokenEndpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(parameters.entries.joinToString("&") {
                    (name, value) -> "${encoded(name)}=${encoded(value)}"
                })).build(),
        )
        val payload = try {
            parseObject(response.body, "token response")
        } catch (error: OpenAiSiwcException) {
            if (response.status !in 200..299) {
                throw OpenAiSiwcException(
                    code = "http_error",
                    message = "ChatGPT authentication failed (HTTP ${response.status}).",
                    retryable = response.status >= 500,
                    status = response.status,
                    cause = error,
                )
            }
            throw error
        }
        if (response.status !in 200..299) throw apiError(payload, response.status)
        val scopes = payload.string("scope")?.split(Regex("\\s+"))?.filter(String::isNotBlank) ?: previousScopes
            ?: throw OpenAiSiwcException("invalid_token_response", "ChatGPT did not confirm the granted permissions.")
        val accessToken = payload.string("access_token")
            ?: throw OpenAiSiwcException("invalid_token_response", "ChatGPT returned incomplete credentials.")
        val tokenType = payload.string("token_type")
        val expiresIn = payload["expires_in"]?.jsonPrimitive?.longOrNull
        val refreshToken = payload.string("refresh_token")
        if (!tokenType.equals("bearer", ignoreCase = true) || expiresIn == null || expiresIn <= 0 ||
            (requireRefresh && refreshToken.isNullOrBlank())) {
            throw OpenAiSiwcException("invalid_token_response", "ChatGPT returned incomplete credentials.")
        }
        val expiresAt = try {
            Math.addExact(nowMillis(), Math.multiplyExact(expiresIn, 1000L))
        } catch (_: ArithmeticException) {
            throw OpenAiSiwcException("invalid_token_response", "ChatGPT returned an invalid credential lifetime.")
        }
        return TokenFields(
            credentials = OpenAiSiwcStoredState.Credentials(
                accessToken = accessToken,
                refreshToken = requireNotNull(refreshToken),
                expiresAtMillis = expiresAt,
                earliestRefreshAtMillis = payload["earliest_refresh_at"]?.let(::parseEarliestRefresh),
            ),
            scopes = scopes,
            idToken = payload.string("id_token"),
        )
    }

    private fun verifyIdentity(
        token: String,
        clientId: String,
        nonce: String?,
        receivedAtMillis: Long,
    ): VerifiedIdentity {
        try {
            val parts = token.split('.')
            require(parts.size == 3 && parts.sumOf(String::length) <= MAX_TOKEN_CHARS)
            val header = json.parseToJsonElement(decodeUrl(parts[0]).toString(StandardCharsets.UTF_8)).jsonObject
            require(header.string("alg") == "RS256")
            val keyId = requireNotNull(header.string("kid"))
            val key = signingKey(keyId)
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
            require(verifier.verify(decodeUrl(parts[2])))

            val claims = json.parseToJsonElement(decodeUrl(parts[1]).toString(StandardCharsets.UTF_8)).jsonObject
            val provider = discovery()
            require(claims.string("iss") == provider.issuer)
            val audiences = when (val aud = claims["aud"]) {
                is JsonPrimitive -> listOf(aud.content)
                is JsonArray -> aud.map { it.jsonPrimitive.content }
                else -> emptyList()
            }
            require(clientId in audiences)
            val authorizedParty = claims.string("azp")
            require(authorizedParty == null || authorizedParty == clientId)
            require(audiences.size <= 1 || authorizedParty == clientId)
            val checkSeconds = receivedAtMillis / 1000L
            val expiresAt = requireNotNull(claims["exp"]?.jsonPrimitive?.longOrNull)
            val issuedAt = requireNotNull(claims["iat"]?.jsonPrimitive?.longOrNull)
            require(expiresAt + CLOCK_TOLERANCE_SECONDS >= checkSeconds)
            require(issuedAt <= checkSeconds + CLOCK_TOLERANCE_SECONDS)
            claims["nbf"]?.jsonPrimitive?.longOrNull?.let { notBefore ->
                require(notBefore <= checkSeconds + CLOCK_TOLERANCE_SECONDS)
            }
            if (nonce != null) require(claims.string("nonce") == nonce)
            val subject = requireNotNull(claims.string("sub"))
            require(subject.isNotBlank())
            return VerifiedIdentity(
                subject = subject,
                identity = OpenAiSiwcStoredState.Identity(
                    name = claims.string("name"),
                    email = claims.string("email"),
                ),
            )
        } catch (error: OpenAiSiwcException) {
            throw error
        } catch (error: Exception) {
            throw OpenAiSiwcException("invalid_id_token", "The ChatGPT identity could not be verified.", cause = error)
        }
    }

    private fun signingKey(keyId: String): RSAPublicKey {
        signingKeys[keyId]?.let { return it }
        synchronized(this) {
            signingKeys[keyId]?.let { return it }
            val provider = discovery()
            val response = try {
                send(HttpRequest.newBuilder(URI.create(provider.jwksUri)).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json").GET().build())
            } catch (error: Exception) {
                throw OpenAiSiwcException("identity_verification_unavailable", "ChatGPT identity verification is temporarily unavailable.", true, cause = error)
            }
            if (response.status != 200) {
                throw OpenAiSiwcException("identity_verification_unavailable", "ChatGPT identity verification is temporarily unavailable.", true, response.status)
            }
            val parsed = try {
                val keys = parseObject(response.body, "key set")["keys"] as? JsonArray
                    ?: throw IllegalArgumentException("Missing keys")
                keys.mapNotNull { element ->
                    val value = element as? JsonObject ?: return@mapNotNull null
                    if (value.string("kty") != "RSA" || value.string("alg")?.let { it != "RS256" } == true) return@mapNotNull null
                    val kid = value.string("kid") ?: return@mapNotNull null
                    val modulus = value.string("n") ?: return@mapNotNull null
                    val exponent = value.string("e") ?: return@mapNotNull null
                    kid to KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
                        BigInteger(1, decodeUrl(modulus)), BigInteger(1, decodeUrl(exponent)),
                    )) as RSAPublicKey
                }.toMap()
            } catch (error: Exception) {
                throw OpenAiSiwcException(
                    "identity_verification_unavailable",
                    "ChatGPT identity verification is temporarily unavailable.",
                    true,
                    cause = error,
                )
            }
            if (parsed.isEmpty()) {
                throw OpenAiSiwcException("identity_verification_unavailable", "ChatGPT identity verification is temporarily unavailable.", true)
            }
            signingKeys = parsed
            return parsed[keyId]
                ?: throw OpenAiSiwcException("identity_verification_unavailable", "ChatGPT identity verification is temporarily unavailable.", true)
        }
    }

    private fun discovery(): Discovery {
        discovered?.let { return it }
        synchronized(this) {
            discovered?.let { return it }
            val response = send(HttpRequest.newBuilder(URI.create("$issuer/.well-known/openid-configuration"))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build())
            val payload = parseObject(response.body, "discovery document")
            if (response.status != 200 || payload.string("issuer") != issuer) {
                throw OpenAiSiwcException("discovery_failed", "ChatGPT sign-in configuration could not be verified.", true, response.status)
            }
            fun endpoint(name: String): String {
                val value = payload.string(name)
                    ?: throw OpenAiSiwcException("discovery_failed", "ChatGPT sign-in configuration could not be verified.", true)
                return try {
                    val uri = URI.create(value)
                    require(uri.isAbsolute && uri.host != null && origin(uri) == origin(URI.create(issuer)))
                    value
                } catch (error: Exception) {
                    throw OpenAiSiwcException(
                        "discovery_failed",
                        "ChatGPT sign-in configuration could not be verified.",
                        true,
                        cause = error,
                    )
                }
            }
            return Discovery(
                issuer = issuer,
                authorizationEndpoint = endpoint("authorization_endpoint"),
                tokenEndpoint = endpoint("token_endpoint"),
                jwksUri = endpoint("jwks_uri"),
            ).also { discovered = it }
        }
    }

    private fun send(request: HttpRequest): HttpResult = try {
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            val body = input.readNBytes(MAX_HTTP_BODY_BYTES + 1)
            if (body.size > MAX_HTTP_BODY_BYTES) {
                throw OpenAiSiwcException("response_too_large", "ChatGPT returned an unexpectedly large authentication response.", true)
            }
            HttpResult(response.statusCode(), body.toString(StandardCharsets.UTF_8))
        }
    } catch (error: OpenAiSiwcException) {
        throw error
    } catch (error: Exception) {
        throw OpenAiSiwcException("network_error", "Could not reach ChatGPT.", true, cause = error)
    }

    private fun parseObject(value: String, description: String): JsonObject = try {
        json.parseToJsonElement(value) as? JsonObject
            ?: throw IllegalArgumentException("Not an object")
    } catch (error: Exception) {
        throw OpenAiSiwcException("invalid_response", "ChatGPT returned an invalid $description.", true, cause = error)
    }

    private fun apiError(payload: JsonObject, status: Int): OpenAiSiwcException {
        val error = payload["error"]
        val nested = error as? JsonObject
        val code = nested?.string("code") ?: (error as? JsonPrimitive)?.contentOrNull ?: payload.string("code") ?: "http_error"
        return OpenAiSiwcException(
            code = code.take(120),
            message = when (code) {
                "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
                "refresh_token_invalidated", "refresh_token_reused" -> "The ChatGPT connection has expired. Sign in again."
                else -> "ChatGPT authentication failed ($code)."
            },
            retryable = status >= 500,
            status = status,
        )
    }

    private fun parseEarliestRefresh(value: kotlinx.serialization.json.JsonElement): Long {
        val primitive = value as? JsonPrimitive
            ?: throw OpenAiSiwcException("invalid_token_response", "ChatGPT returned an invalid refresh schedule.")
        primitive.longOrNull?.let { seconds ->
            return try { Math.multiplyExact(seconds, 1000L) } catch (_: ArithmeticException) {
                throw OpenAiSiwcException("invalid_token_response", "ChatGPT returned an invalid refresh schedule.")
            }
        }
        val text = primitive.contentOrNull
            ?: throw OpenAiSiwcException("invalid_token_response", "ChatGPT returned an invalid refresh schedule.")
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (_: DateTimeParseException) {
            throw OpenAiSiwcException("invalid_token_response", "ChatGPT returned an invalid refresh schedule.")
        }
    }

    private fun requireSharingScope(scopes: List<String>) {
        if (SHARING_SCOPE !in scopes) {
            throw OpenAiSiwcException("sharing_not_enabled", "ChatGPT token sharing is not enabled for this connection.")
        }
    }

    private fun validateIssuedClientId(value: String) {
        require(value != DYNAMIC_CLIENT_ID && value.isNotBlank() && value.length <= 2048 &&
            value.none { it.code < 32 || it.code == 127 }) {
            "Invalid issued SIWC client ID"
        }
    }

    private fun randomValue(): String = base64Url(ByteArray(32).also(random::nextBytes))
    private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun decodeUrl(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    private fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private fun origin(uri: URI): String {
        val port = if (uri.port != -1) uri.port else when (uri.scheme.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
        return "${uri.scheme.lowercase()}://${uri.host.lowercase()}:$port"
    }
    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private data class Discovery(val issuer: String, val authorizationEndpoint: String, val tokenEndpoint: String, val jwksUri: String)
    private data class TokenFields(
        val credentials: OpenAiSiwcStoredState.Credentials,
        val scopes: List<String>,
        val idToken: String?,
    )
    private data class VerifiedIdentity(val subject: String, val identity: OpenAiSiwcStoredState.Identity)
    private data class HttpResult(val status: Int, val body: String)

    private companion object {
        const val DEFAULT_ISSUER = "https://auth.openai.com"
        const val RESOURCE = "https://api.openai.com/v1"
        const val APP_NAME = "Gromozeka"
        const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
        const val SHARING_SCOPE = "chatgpt.tokens.use.direct"
        const val REFRESH_MARGIN_MILLIS = 60_000L
        const val CLOCK_TOLERANCE_SECONDS = 5L
        const val MAX_TOKEN_CHARS = 1024 * 1024
        const val MAX_HTTP_BODY_BYTES = 1024 * 1024
        val SCOPES = listOf("openid", "profile", "email", "offline_access", "resource.invoke", SHARING_SCOPE)
        val TERMINAL_REFRESH_CODES = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused", "invalid_id_token", "invalid_token_response",
            "account_mismatch", "sharing_not_enabled",
        )
    }
}
