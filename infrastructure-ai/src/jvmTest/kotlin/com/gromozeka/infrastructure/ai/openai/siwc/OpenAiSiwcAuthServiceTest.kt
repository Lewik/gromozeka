package com.gromozeka.infrastructure.ai.openai.siwc

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiCallFailure
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.runtime.credentialFingerprint
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAiSiwcAuthServiceTest {
    private val home = Files.createTempDirectory("gromozeka-siwc-auth-test")
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val tokenCalls = AtomicInteger()
    private var nonce: String? = null
    private var jwksAvailable = true
    private var tokenFailure: TokenFailure? = null
    private var tokenBodyGate: CountDownLatch? = null
    private var tokenScopes = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    private var earliestRefreshAtSeconds: Long? = null
    private var refreshSubject = "subject-1"
    private var corruptRefreshIdToken = false
    private var now = 1_800_000_000_000L
    private val server: HttpServer
    private val issuer: String

    init {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        issuer = "http://127.0.0.1:${server.address.port}"
        server.createContext("/.well-known/openid-configuration", ::discovery)
        server.createContext("/jwks", ::jwks)
        server.createContext("/oauth/token", ::token)
        server.start()
    }

    @AfterTest
    fun close() {
        server.stop(0)
        Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    @Test
    fun `dynamic registration validates identity and durably rotates refresh token`() = runBlocking {
        val store = OpenAiSiwcCredentialStore(TestSettings(home))
        val service = OpenAiSiwcAuthService(
            store = store,
            issuer = issuer,
            httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build(),
            nowMillis = { now },
        )
        val authorization = service.prepareAuthorization("http://127.0.0.1:14555/auth/callback")
        val query = parseQuery(URI.create(authorization.url).rawQuery)
        nonce = query.getValue("nonce")
        assertEquals("dynamic_agent_client", query["client_id"])
        assertEquals("https://api.openai.com/v1", query["resource"])
        assertEquals("Gromozeka", query["agent_name_hint"])
        assertTrue(query.getValue("ext_agent_host_id").startsWith("urn:uuid:"))
        assertTrue(query.getValue("scope").contains("chatgpt.tokens.use.direct"))

        val connected = service.completeAuthorization(authorization, "one-time-code", "issued_client")
        assertEquals(OpenAiSiwcStoredState.Status.CONNECTED, connected.status)
        assertEquals("subject-1", connected.subject)
        assertEquals("lev@example.test", connected.identity?.email)
        assertEquals("access-1", service.validAccessToken(Long.MAX_VALUE))
        val returning = service.prepareAuthorization("http://127.0.0.1:14555/auth/callback")
        val returningQuery = parseQuery(URI.create(returning.url).rawQuery)
        assertEquals("issued_client", returningQuery["client_id"])
        assertFalse(returningQuery.containsKey("login_hint"))
        assertFalse(returningQuery.containsKey("agent_name_hint"))
        assertFalse(returningQuery.containsKey("prompt"))

        now += 3_600_000L
        assertEquals("access-2", service.validAccessToken(Long.MAX_VALUE))
        val refreshed = assertNotNull(store.read())
        assertEquals("refresh-2", refreshed.credentials?.refreshToken)
        assertEquals(null, refreshed.pendingRefresh)
        assertEquals(2, tokenCalls.get())

        val encrypted = Files.readString(home.resolve("openai-siwc/credentials.json"))
        listOf("access-2", "refresh-2", "lev@example.test").forEach { assertFalse(encrypted.contains(it)) }
        assertEquals(store.hostId(), OpenAiSiwcCredentialStore(TestSettings(home)).hostId())
        assertEquals(32L, Files.size(home.resolve("openai-siwc/credentials.key")))
    }

    @Test
    fun `refresh rotation survives key service outage and process restart`() = runBlocking {
        val store = OpenAiSiwcCredentialStore(TestSettings(home))
        val initial = service(store)
        val authorization = initial.prepareAuthorization("http://127.0.0.1:14555/auth/callback")
        nonce = parseQuery(URI.create(authorization.url).rawQuery).getValue("nonce")
        initial.completeAuthorization(authorization, "one-time-code", "issued_client")

        now += 3_600_000L
        jwksAvailable = false
        val unavailable = assertFailsWith<OpenAiSiwcException> { service(store).validAccessToken(Long.MAX_VALUE) }
        assertEquals("identity_verification_unavailable", unavailable.code)
        val pending = assertNotNull(store.read()?.pendingRefresh)
        assertEquals("refresh-2", pending.credentials.refreshToken)
        assertEquals("refresh-1", store.read()?.credentials?.refreshToken)

        jwksAvailable = true
        assertEquals("access-2", service(store).validAccessToken(Long.MAX_VALUE))
        assertEquals(null, store.read()?.pendingRefresh)
        assertEquals(2, tokenCalls.get(), "Recovery must not reuse the consumed refresh token")
    }

    @Test
    fun `persisted refresh account mismatch requires reauthentication after restart`() = runBlocking {
        refreshSubject = "subject-2"
        assertTerminalPendingRefreshRequiresReauthentication("account_mismatch")
    }

    @Test
    fun `persisted invalid refresh identity requires reauthentication after restart`() = runBlocking {
        corruptRefreshIdToken = true
        assertTerminalPendingRefreshRequiresReauthentication("invalid_id_token")
    }

    @Test
    fun `non-json refresh rejection preserves status and the existing grant`() = runBlocking {
        val store = OpenAiSiwcCredentialStore(TestSettings(home))
        val service = service(store)
        val authorization = service.prepareAuthorization("http://127.0.0.1:14555/auth/callback")
        nonce = parseQuery(URI.create(authorization.url).rawQuery).getValue("nonce")
        service.completeAuthorization(authorization, "one-time-code", "issued_client")

        now += 3_600_000L
        tokenFailure = TokenFailure(401, "unauthorized")
        val error = assertFailsWith<OpenAiSiwcException> { service.validAccessToken(Long.MAX_VALUE) }

        assertEquals("http_error", error.code)
        assertEquals(401, error.status)
        assertEquals(OpenAiSiwcStoredState.Status.CONNECTED, store.read()?.status)
        assertEquals("refresh-1", store.read()?.credentials?.refreshToken)
    }

    @Test
    fun `invalid client refresh rejection preserves the grant and request id`() = runBlocking {
        val store = OpenAiSiwcCredentialStore(TestSettings(home))
        val service = service(store)
        val authorization = service.prepareAuthorization("http://127.0.0.1:14555/auth/callback")
        nonce = parseQuery(URI.create(authorization.url).rawQuery).getValue("nonce")
        service.completeAuthorization(authorization, "one-time-code", "issued_client")

        now += 3_600_000L
        tokenFailure = TokenFailure(401, """{"error":"invalid_client"}""", "req_invalid-client-1")
        val error = assertFailsWith<OpenAiSiwcException> { service.validAccessToken(Long.MAX_VALUE) }

        assertEquals("invalid_client", error.code)
        assertEquals("req_invalid-client-1", error.requestId)
        assertEquals(OpenAiSiwcStoredState.Status.CONNECTED, store.read()?.status)
        assertEquals("refresh-1", store.read()?.credentials?.refreshToken)
        assertEquals(AiCallFailure.Kind.CONFIGURATION, error.toAiCallException().failure.kind)
    }

    @Test
    fun `invalid grant refresh rejection clears unusable credentials`() = runBlocking {
        val store = OpenAiSiwcCredentialStore(TestSettings(home))
        val service = service(store)
        val browser = OpenAiSiwcBrowserAuthService(service)
        val authorization = service.prepareAuthorization("http://127.0.0.1:14555/auth/callback")
        nonce = parseQuery(URI.create(authorization.url).rawQuery).getValue("nonce")
        service.completeAuthorization(authorization, "one-time-code", "issued_client")

        try {
            now += 3_600_000L
            tokenFailure = TokenFailure(400, """{"error":"invalid_grant"}""")
            val error = assertFailsWith<OpenAiSiwcException> { browser.validAccessToken(Long.MAX_VALUE) }

            assertEquals("invalid_grant", error.code)
            assertEquals(OpenAiSiwcStoredState.Status.REAUTH_REQUIRED, store.read()?.status)
            assertEquals(null, store.read()?.credentials)
            assertTrue(browser.isLoginPending())
            val retry = parseQuery(URI.create(service.prepareAuthorization(
                "http://127.0.0.1:14555/auth/callback").url).rawQuery)
            assertFalse(retry.containsKey("prompt"))
        } finally {
            browser.shutdown()
        }
    }

    @Test
    fun `refresh without plan scope discards the rotated grant and starts reauthentication`() = runBlocking {
        val store = OpenAiSiwcCredentialStore(TestSettings(home))
        val service = service(store)
        val authorization = service.prepareAuthorization("http://127.0.0.1:14555/auth/callback")
        nonce = parseQuery(URI.create(authorization.url).rawQuery).getValue("nonce")
        service.completeAuthorization(authorization, "one-time-code", "issued_client")
        val browser = OpenAiSiwcBrowserAuthService(service)

        try {
            now += 3_600_000L
            tokenScopes = "openid profile email offline_access resource.invoke"
            val error = assertFailsWith<OpenAiSiwcException> { browser.validAccessToken(Long.MAX_VALUE) }

            assertEquals("sharing_not_enabled", error.code)
            val reauthentication = assertNotNull(store.read())
            assertEquals(OpenAiSiwcStoredState.Status.DISCONNECTED, reauthentication.status)
            assertTrue(reauthentication.scopes.isEmpty())
            assertEquals(null, reauthentication.credentials)
            assertEquals(null, reauthentication.pendingRefresh)
            assertTrue(browser.isLoginPending())
            assertEquals(2, tokenCalls.get())
            val retry = parseQuery(URI.create(service.prepareAuthorization(
                "http://127.0.0.1:14555/auth/callback").url).rawQuery)
            assertEquals("consent", retry["prompt"])

            val restarted = service(OpenAiSiwcCredentialStore(TestSettings(home)))
            val signedOut = assertFailsWith<OpenAiSiwcException> { restarted.validAccessToken(Long.MAX_VALUE) }
            assertEquals("sign_in_required", signedOut.code)
            assertEquals(2, tokenCalls.get(), "Recovery must not retry the consumed refresh token")
        } finally {
            browser.shutdown()
        }
    }

    @Test
    fun `resource rejection refreshes only the access token that was actually rejected`() = runBlocking {
        val store = OpenAiSiwcCredentialStore(TestSettings(home))
        val service = service(store)
        val authorization = service.prepareAuthorization("http://127.0.0.1:14555/auth/callback")
        nonce = parseQuery(URI.create(authorization.url).rawQuery).getValue("nonce")
        service.completeAuthorization(authorization, "one-time-code", "issued_client")
        val originalExpiry = assertNotNull(store.read()?.credentials).expiresAtMillis
        val rejection = OpenAiSiwcResourceError(
            status = 401,
            code = "subscription_sharing_invalid_user",
            parameter = null,
            requestId = "req_resource-1",
        )

        service.resourceRejectedAccessToken(credentialFingerprint("access-from-an-old-call"), rejection, Long.MAX_VALUE)
        assertEquals(originalExpiry, store.read()?.credentials?.expiresAtMillis)

        service.resourceRejectedAccessToken(
            credentialFingerprint("access-1"),
            rejection.copy(status = 200, code = "subscription_sharing_usage_limit_exceeded"),
            Long.MAX_VALUE,
        )
        assertEquals(originalExpiry, store.read()?.credentials?.expiresAtMillis)

        val browser = OpenAiSiwcBrowserAuthService(service)
        try {
            browser.resourceRejectedAccessToken(
                credentialFingerprint("access-1"),
                rejection.copy(status = 200),
                Long.MAX_VALUE,
            )
            assertEquals(now, store.read()?.credentials?.expiresAtMillis)
            assertEquals("access-2", browser.validAccessToken(Long.MAX_VALUE))
            assertEquals(OpenAiSiwcStoredState.Status.CONNECTED, store.read()?.status)
            assertEquals("refresh-2", store.read()?.credentials?.refreshToken)
            assertEquals(null, store.read()?.accessTokenRejection)
        } finally {
            browser.shutdown()
        }
    }

    @Test
    fun `rejected access token retains diagnostics until scheduled refresh after restart`() = runBlocking {
        earliestRefreshAtSeconds = now / 1000 + 60
        val store = OpenAiSiwcCredentialStore(TestSettings(home))
        val initial = service(store)
        val authorization = initial.prepareAuthorization("http://127.0.0.1:14555/auth/callback")
        nonce = parseQuery(URI.create(authorization.url).rawQuery).getValue("nonce")
        initial.completeAuthorization(authorization, "one-time-code", "issued_client")
        val rejection = OpenAiSiwcResourceError(
            status = 200,
            code = "subscription_sharing_invalid_user",
            parameter = "account",
            requestId = "req_rejected-1",
        )

        initial.resourceRejectedAccessToken(credentialFingerprint("access-1"), rejection, Long.MAX_VALUE)
        assertEquals(rejection, store.read()?.accessTokenRejection)

        val restartedStore = OpenAiSiwcCredentialStore(TestSettings(home))
        val waiting = assertFailsWith<OpenAiSiwcException> {
            service(restartedStore).validAccessToken(Long.MAX_VALUE)
        }
        assertEquals("access_token_rejected", waiting.code)
        assertEquals(200, waiting.status)
        assertEquals("subscription_sharing_invalid_user", waiting.providerCode)
        assertEquals("account", waiting.providerParameter)
        assertEquals("req_rejected-1", waiting.requestId)
        val failure = waiting.toAiCallException().failure
        assertEquals(AiCallFailure.Kind.AUTHENTICATION, failure.kind)
        assertEquals(AiCallFailure.Outcome.REJECTED, failure.outcome)
        assertEquals(1, tokenCalls.get(), "Refresh must wait for earliest_refresh_at")

        now += 60_000L
        assertEquals("access-2", service(restartedStore).validAccessToken(Long.MAX_VALUE))
        assertEquals(2, tokenCalls.get())
        assertEquals(null, restartedStore.read()?.accessTokenRejection)
        assertEquals("refresh-2", restartedStore.read()?.credentials?.refreshToken)
    }

    @Test
    fun `refresh response body cannot outlive the inference deadline`() = runBlocking {
        val store = OpenAiSiwcCredentialStore(TestSettings(home))
        val service = service(store)
        val authorization = service.prepareAuthorization("http://127.0.0.1:14555/auth/callback")
        nonce = parseQuery(URI.create(authorization.url).rawQuery).getValue("nonce")
        service.completeAuthorization(authorization, "one-time-code", "issued_client")

        now += 3_600_000L
        val gate = CountDownLatch(1)
        tokenBodyGate = gate
        val started = System.nanoTime()
        val error = try {
            assertFailsWith<OpenAiSiwcException> {
                service.validAccessToken(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250))
            }
        } finally {
            gate.countDown()
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals("deadline_exceeded", error.code)
        assertTrue(elapsedMillis < 2_000, "Refresh exceeded its call budget: ${elapsedMillis}ms")
        assertEquals(OpenAiSiwcStoredState.Status.CONNECTED, store.read()?.status)
        assertEquals("refresh-1", store.read()?.credentials?.refreshToken)
    }

    @Test
    fun `credential lock acquisition honors each caller deadline`() = runBlocking {
        val first = OpenAiSiwcCredentialStore(TestSettings(home))
        assertLockDeadline(first, first)
        assertLockDeadline(first, OpenAiSiwcCredentialStore(TestSettings(home)))
    }

    @Test
    fun `loopback callback completes the pending dynamic registration`() {
        val store = OpenAiSiwcCredentialStore(TestSettings(home))
        val browser = OpenAiSiwcBrowserAuthService(service(store))
        try {
            val authorization = browser.beginLogin().getOrThrow()
            nonce = authorization.nonce
            val callback = URI.create("${authorization.redirectUri}?" + listOf(
                "code" to "one-time-code",
                "state" to authorization.state,
                "client_id" to "issued_client",
            ).joinToString("&") { (name, value) ->
                "$name=${java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            })

            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(callback).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("ChatGPT Connected"))
            assertEquals(OpenAiSiwcStoredState.Status.CONNECTED, store.read()?.status)
            assertEquals("issued_client", store.read()?.clientId)
        } finally {
            browser.shutdown()
        }
    }

    @Test
    fun `loopback callback discards a sign-in without plan access and makes consent retryable`() {
        val store = OpenAiSiwcCredentialStore(TestSettings(home))
        val service = service(store)
        val browser = OpenAiSiwcBrowserAuthService(service)
        try {
            tokenScopes = "openid profile email offline_access resource.invoke"
            val authorization = browser.beginLogin().getOrThrow()
            nonce = authorization.nonce
            val callback = URI.create("${authorization.redirectUri}?" + listOf(
                "code" to "one-time-code",
                "state" to authorization.state,
                "client_id" to "issued_client",
            ).joinToString("&") { (name, value) ->
                "$name=${java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            })

            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(callback).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(403, response.statusCode())
            assertTrue(response.body().contains("ChatGPT Plan Not Enabled"))
            val discarded = assertNotNull(store.read())
            assertEquals(OpenAiSiwcStoredState.Status.DISCONNECTED, discarded.status)
            assertEquals("issued_client", discarded.clientId)
            assertEquals(null, discarded.subject)
            assertEquals(null, discarded.identity)
            assertEquals(null, discarded.credentials)
            assertTrue(discarded.scopes.isEmpty())

            val retry = parseQuery(URI.create(service.prepareAuthorization(
                "http://127.0.0.1:14555/auth/callback").url).rawQuery)
            assertEquals("issued_client", retry["client_id"])
            assertEquals("consent", retry["prompt"])
            assertFalse(retry.containsKey("agent_name_hint"))
        } finally {
            browser.shutdown()
        }
    }

    private fun service(store: OpenAiSiwcCredentialStore) = OpenAiSiwcAuthService(
        store = store,
        issuer = issuer,
        httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2)).build(),
        nowMillis = { now },
    )

    private fun discovery(exchange: HttpExchange) = exchange.json(200, """{
        "issuer":"$issuer",
        "authorization_endpoint":"$issuer/api/accounts/authorize",
        "token_endpoint":"$issuer/oauth/token",
        "jwks_uri":"$issuer/jwks"
    }""")

    private fun jwks(exchange: HttpExchange) {
        if (!jwksAvailable) {
            exchange.json(503, """{"error":"unavailable"}""")
            return
        }
        val key = keyPair.public as RSAPublicKey
        exchange.json(200, """{"keys":[{
            "kty":"RSA","kid":"test-key","alg":"RS256","use":"sig",
            "n":"${unsigned(key.modulus)}","e":"${unsigned(key.publicExponent)}"
        }]}""")
    }

    private fun token(exchange: HttpExchange) {
        tokenFailure?.let { failure ->
            failure.requestId?.let { exchange.responseHeaders.set("x-request-id", it) }
            exchange.json(failure.status, failure.body)
            return
        }
        val form = parseQuery(exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8))
        assertEquals("https://api.openai.com/v1", form["resource"])
        val call = tokenCalls.incrementAndGet()
        if (call == 1) {
            assertEquals("authorization_code", form["grant_type"])
            assertEquals("issued_client", form["client_id"])
        } else {
            assertEquals("refresh_token", form["grant_type"])
            assertEquals("refresh-1", form["refresh_token"])
        }
        var idToken = signedIdToken(
            clientId = "issued_client",
            nonce = if (call == 1) nonce else null,
            subject = if (call == 1) "subject-1" else refreshSubject,
        )
        if (call > 1 && corruptRefreshIdToken) {
            idToken = "${idToken.substringBeforeLast('.')}.${base64Url(ByteArray(256))}"
        }
        exchange.json(200, """{
            "token_type":"Bearer","access_token":"access-$call","refresh_token":"refresh-$call",
            "expires_in":3600,"scope":"$tokenScopes",
            "id_token":"$idToken"${earliestRefreshAtSeconds?.let { ",\"earliest_refresh_at\":$it" }.orEmpty()}
        }""", tokenBodyGate)
    }

    private fun signedIdToken(clientId: String, nonce: String?, subject: String): String {
        val header = base64Url("""{"alg":"RS256","kid":"test-key","typ":"JWT"}""".toByteArray())
        val claims = base64Url("""{
            "iss":"$issuer","aud":"$clientId","exp":${now / 1000 + 3600},"iat":${now / 1000},
            "sub":"$subject","name":"Lev","email":"lev@example.test"${nonce?.let { ",\"nonce\":\"$it\"" }.orEmpty()}
        }""".toByteArray())
        val unsigned = "$header.$claims"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(unsigned.toByteArray(StandardCharsets.US_ASCII))
        }.sign()
        return "$unsigned.${base64Url(signature)}"
    }

    private suspend fun assertTerminalPendingRefreshRequiresReauthentication(expectedCode: String) {
        val store = OpenAiSiwcCredentialStore(TestSettings(home))
        val initial = service(store)
        val authorization = initial.prepareAuthorization("http://127.0.0.1:14555/auth/callback")
        nonce = parseQuery(URI.create(authorization.url).rawQuery).getValue("nonce")
        initial.completeAuthorization(authorization, "one-time-code", "issued_client")

        now += 3_600_000L
        jwksAvailable = false
        val unavailable = assertFailsWith<OpenAiSiwcException> { service(store).validAccessToken(Long.MAX_VALUE) }
        assertEquals("identity_verification_unavailable", unavailable.code)
        assertNotNull(store.read()?.pendingRefresh)

        jwksAvailable = true
        val restartedStore = OpenAiSiwcCredentialStore(TestSettings(home))
        val browser = OpenAiSiwcBrowserAuthService(service(restartedStore))
        try {
            val terminal = assertFailsWith<OpenAiSiwcException> { browser.validAccessToken(Long.MAX_VALUE) }
            assertEquals(expectedCode, terminal.code)
            val reauthentication = assertNotNull(restartedStore.read())
            assertEquals(OpenAiSiwcStoredState.Status.REAUTH_REQUIRED, reauthentication.status)
            assertEquals("subject-1", reauthentication.subject)
            assertEquals(null, reauthentication.credentials)
            assertEquals(null, reauthentication.pendingRefresh)
            assertTrue(browser.isLoginPending())
            assertEquals(2, tokenCalls.get(), "Recovery must not retry the consumed refresh token")
        } finally {
            browser.shutdown()
        }
    }

    private fun HttpExchange.json(
        status: Int,
        value: String,
        bodyGate: CountDownLatch? = null,
    ) {
        val body = value.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, body.size.toLong())
        bodyGate?.await(5, TimeUnit.SECONDS)
        responseBody.use { it.write(body) }
        close()
    }

    private suspend fun assertLockDeadline(
        holderStore: OpenAiSiwcCredentialStore,
        waiterStore: OpenAiSiwcCredentialStore,
    ) = coroutineScope {
        val lockAcquired = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val holder = async(Dispatchers.IO) {
            holderStore.lockedState(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)) {
                lockAcquired.countDown()
                releaseLock.await(5, TimeUnit.SECONDS)
            }
        }
        val result = try {
            assertTrue(lockAcquired.await(2, TimeUnit.SECONDS), "The first caller did not acquire the credential lock")
            val started = System.nanoTime()
            val error = assertFailsWith<OpenAiSiwcException> {
                waiterStore.lockedState(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)) { Unit }
            }
            error to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        } finally {
            releaseLock.countDown()
        }
        holder.await()

        assertEquals("deadline_exceeded", result.first.code)
        assertTrue(result.second < 2_000, "Credential-lock wait exceeded its call budget: ${result.second}ms")
    }

    private fun parseQuery(value: String): Map<String, String> = value.split('&').filter(String::isNotBlank).associate {
        URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to
            URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
    }

    private fun unsigned(value: BigInteger): String = base64Url(value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
    private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private data class TokenFailure(val status: Int, val body: String, val requestId: String? = null)

    private class TestSettings(override val homeDirectory: String) : SettingsProvider {
        constructor(home: Path) : this(home.toAbsolutePath().toString())
        override val userProfile = UserProfile()
        override val userDeviceSettings = UserDeviceSettings.Desktop()
        override val mode = AppMode.TEST
    }
}
