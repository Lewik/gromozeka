package com.gromozeka.infrastructure.ai.openai.siwc

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.service.SettingsProvider
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
import java.util.concurrent.atomic.AtomicInteger
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
    private var tokenFailure: Pair<Int, String>? = null
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
        assertEquals("access-1", service.validAccessToken())
        val returning = service.prepareAuthorization("http://127.0.0.1:14555/auth/callback")
        val returningQuery = parseQuery(URI.create(returning.url).rawQuery)
        assertEquals("issued_client", returningQuery["client_id"])
        assertFalse(returningQuery.containsKey("login_hint"))
        assertFalse(returningQuery.containsKey("agent_name_hint"))

        now += 3_600_000L
        assertEquals("access-2", service.validAccessToken())
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
        val unavailable = assertFailsWith<OpenAiSiwcException> { service(store).validAccessToken() }
        assertEquals("identity_verification_unavailable", unavailable.code)
        val pending = assertNotNull(store.read()?.pendingRefresh)
        assertEquals("refresh-2", pending.credentials.refreshToken)
        assertEquals("refresh-1", store.read()?.credentials?.refreshToken)

        jwksAvailable = true
        assertEquals("access-2", service(store).validAccessToken())
        assertEquals(null, store.read()?.pendingRefresh)
        assertEquals(2, tokenCalls.get(), "Recovery must not reuse the consumed refresh token")
    }

    @Test
    fun `non-json refresh rejection preserves status and requires reauthorization`() = runBlocking {
        val store = OpenAiSiwcCredentialStore(TestSettings(home))
        val service = service(store)
        val authorization = service.prepareAuthorization("http://127.0.0.1:14555/auth/callback")
        nonce = parseQuery(URI.create(authorization.url).rawQuery).getValue("nonce")
        service.completeAuthorization(authorization, "one-time-code", "issued_client")

        now += 3_600_000L
        tokenFailure = 401 to "unauthorized"
        val error = assertFailsWith<OpenAiSiwcException> { service.validAccessToken() }

        assertEquals("http_error", error.code)
        assertEquals(401, error.status)
        assertEquals(OpenAiSiwcStoredState.Status.REAUTH_REQUIRED, store.read()?.status)
        assertEquals(null, store.read()?.credentials)
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
        tokenFailure?.let { (status, body) ->
            exchange.json(status, body)
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
        val idToken = signedIdToken(clientId = "issued_client", nonce = if (call == 1) nonce else null)
        exchange.json(200, """{
            "token_type":"Bearer","access_token":"access-$call","refresh_token":"refresh-$call",
            "expires_in":3600,"scope":"openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
            "id_token":"$idToken"
        }""")
    }

    private fun signedIdToken(clientId: String, nonce: String?): String {
        val header = base64Url("""{"alg":"RS256","kid":"test-key","typ":"JWT"}""".toByteArray())
        val claims = base64Url("""{
            "iss":"$issuer","aud":"$clientId","exp":${now / 1000 + 3600},"iat":${now / 1000},
            "sub":"subject-1","name":"Lev","email":"lev@example.test"${nonce?.let { ",\"nonce\":\"$it\"" }.orEmpty()}
        }""".toByteArray())
        val unsigned = "$header.$claims"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(unsigned.toByteArray(StandardCharsets.US_ASCII))
        }.sign()
        return "$unsigned.${base64Url(signature)}"
    }

    private fun HttpExchange.json(status: Int, value: String) {
        val body = value.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
        close()
    }

    private fun parseQuery(value: String): Map<String, String> = value.split('&').filter(String::isNotBlank).associate {
        URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to
            URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
    }

    private fun unsigned(value: BigInteger): String = base64Url(value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
    private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private class TestSettings(override val homeDirectory: String) : SettingsProvider {
        constructor(home: Path) : this(home.toAbsolutePath().toString())
        override val userProfile = UserProfile()
        override val userDeviceSettings = UserDeviceSettings.Desktop()
        override val mode = AppMode.TEST
    }
}
