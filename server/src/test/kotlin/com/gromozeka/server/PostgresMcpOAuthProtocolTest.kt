package com.gromozeka.server

import com.gromozeka.application.service.AuthenticationRejectedException
import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.WorkerEnrollmentRepository
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.PersonalAccessTokenService
import com.gromozeka.domain.service.UserDirectoryService
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.mockito.Mockito
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.test.context.TestComponent
import org.springframework.core.env.MapPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockServletContext
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.client.metadata.ClientIdMetadataDocumentResolver
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.util.HtmlUtils
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import javax.sql.DataSource
import kotlin.test.*

/** Real PostgreSQL + production Spring OAuth filters/JDBC services. No sockets, model calls,
 * runtime registry or real user accounts. A deterministic CIMD resolver replaces only HTTPS. */
class PostgresMcpOAuthProtocolTest {
    @Test fun `discovery PKCE consent token and MCP admission use real framework and durable rows`() = database { db ->
        OAuthFixture(db).use { f ->
            val challenge = f.call(get("/mcp")).response
            assertEquals(401, challenge.status)
            assertTrue(assertNotNull(challenge.getHeader("WWW-Authenticate")).contains("resource_metadata=\"https://localhost/.well-known/oauth-protected-resource/mcp\""))
            val metadata = f.call(get("/.well-known/oauth-authorization-server")).response.json()
            assertEquals("https://localhost", metadata.text("issuer"))
            assertEquals("true", metadata.text("client_id_metadata_document_supported"))
            assertTrue("S256" in metadata.getValue("code_challenge_methods_supported").jsonArray.map { it.jsonPrimitive.content })
            for (unsupported in listOf("registration_endpoint", "introspection_endpoint", "device_authorization_endpoint", "dpop_signing_alg_values_supported")) {
                assertFalse(unsupported in metadata, unsupported)
            }
            val resource = f.call(get("/.well-known/oauth-protected-resource/mcp")).response.json()
            assertEquals(RESOURCE, resource.text("resource"))
            assertEquals(listOf("mcp"), resource.getValue("scopes_supported").jsonArray.map { it.jsonPrimitive.content })
            val pending = f.begin()
            val consent = f.consent(pending)
            assertTrue(consent.contains("&lt;script&gt;"))
            assertFalse(consent.contains("<script>"))
            assertTrue(consent.contains("executing tools on your Workers"))
            assertEquals(1, db.count("oauth2_registered_client"))
            val code = f.approve(pending, consent)
            val tokens = f.exchange(code).tokens()
            assertEquals("Bearer", tokens.text("token_type"))
            assertEquals("mcp", tokens.text("scope"))
            assertTrue(tokens.getValue("expires_in").jsonPrimitive.long in 590..600)
            val grant = assertNotNull(f.store.findByToken(tokens.text("access_token"), OAuth2TokenType.ACCESS_TOKEN))
            assertEquals(OWNER, grant.principalName)
            val access = assertNotNull(grant.accessToken)
            assertFalse(access.isInvalidated)
            assertTrue(checkNotNull(access.token.expiresAt).isAfter(Instant.now()))
            assertEquals(OWNER, access.claims!!["sub"])
            assertEquals("https://localhost", assertIs<java.net.URL>(access.claims!!["iss"]).toExternalForm())
            assertEquals(listOf(RESOURCE), access.claims!!["aud"])
            assertEquals(setOf("mcp"), access.token.scopes)
            assertEquals(200, f.mcp(tokens.text("access_token")).status)
            assertEquals(OWNER, f.mcp(tokens.text("access_token")).contentAsString)
            assertEquals(401, f.mcp(tokens.text("refresh_token")).status)
            assertEquals(1, db.count("oauth2_refresh_history"))
            assertEquals(1, f.store.connections(OWNER).size)
            assertTrue(f.store.connections(OTHER).isEmpty())
        }
    }

    @Test fun `login and consent require CSRF reuse existing user service and reject decline`() = database { db ->
        OAuthFixture(db).use { f ->
            val first = f.authorization(cookie = null)
            assertEquals(302, first.response.status)
            assertTrue(assertNotNull(first.response.redirectedUrl).endsWith("/oauth/login"))
            val page = f.browserGet("/oauth/login", cookie = null).response
            val csrf = csrf(page.contentAsString)
            assertEquals(403, f.browserPost("/oauth/login", mapOf("username" to "owner", "password" to "synthetic-password"), cookie = null).response.status)
            assertEquals(0, f.users.logins.get())
            val bad = f.browserPost("/oauth/login", mapOf("username" to "owner", "password" to "wrong", "_csrf" to csrf), cookie = null).response
            assertEquals(401, bad.status)
            assertNull(bad.getHeader("Set-Cookie"))
            val loggedIn = f.browserPost("/oauth/login", mapOf("username" to "owner", "password" to "synthetic-password", "_csrf" to csrf), cookie = null).response
            assertEquals(303, loggedIn.status)
            assertTrue(assertNotNull(loggedIn.getHeader("Set-Cookie")).contains("$SESSION_COOKIE_NAME=owner-session"))
            assertTrue(f.users.passwordArrays.all { chars -> chars.all { it == '\u0000' } })
            val continued = f.browserGet(assertNotNull(loggedIn.redirectedUrl)).response
            val pending = assertNotNull(continued.redirectedUrl)
            val consent = f.consent(pending)
            val fields = query(pending).filterKeys { it in setOf("state", "client_id") }
            assertEquals(403, f.browserPost("/oauth2/authorize", fields + ("scope" to "mcp")).response.status)
            val denied = f.browserPost("/oauth2/authorize", fields + ("_csrf" to csrf(consent))).response
            assertEquals("access_denied", query(assertNotNull(denied.redirectedUrl))["error"])
            assertEquals(0, db.count("oauth2_refresh_history"))
            assertTrue(f.store.connections(OWNER).isEmpty())
        }
    }

    @Test fun `invalid PKCE resource scope redirect and duplicate token inputs fail closed`() = database { db ->
        OAuthFixture(db).use { f ->
            for (changes in listOf(mapOf("code_challenge" to null), mapOf("code_challenge_method" to "plain"),
                mapOf("resource" to "https://other.invalid/mcp"), mapOf("resource" to null),
                mapOf("scope" to "admin"), mapOf("redirect_uri" to "https://unregistered.invalid/cb"))) {
                val response = f.authorization(changes).response
                val target = response.redirectedUrl
                assertTrue(response.status == 400 || (target != null && query(target)["error"] != null),
                    "Invalid request reached normal consent: ${changes.keys}, status=${response.status}")
            }
            assertEquals(0, db.count("oauth2_authorization"))
            val code = f.authorize()
            assertError(f.exchange(code, mapOf("resource" to "https://other.invalid/mcp")), "invalid_target")
            assertError(f.exchange(code, mapOf("code_verifier" to "x".repeat(64))), "invalid_grant")
            val fresh = f.authorize()
            val duplicate = f.form("/oauth2/token", exchangeFields(fresh).entries.map { it.key to it.value } + ("resource" to RESOURCE))
            assertError(duplicate, "invalid_request")
            val tokens = f.exchange(fresh).tokens()
            assertEquals(200, f.mcp(tokens.text("access_token")).status)
            assertError(f.exchange(fresh), "invalid_grant")
            // Reusing a consumed code must not create another live grant/token.
            assertEquals(1, db.count("oauth2_refresh_history"))
        }
    }

    @Test fun `rotation survives repository restart and reuse revokes the entire current grant`() = database { db ->
        val tokens = OAuthFixture(db).use { it.exchange(it.authorize()).tokens() }
        OAuthFixture(db).use { restarted ->
            restarted.metadataAvailable = false
            val before = assertNotNull(restarted.store.findByToken(tokens.text("refresh_token"), OAuth2TokenType.REFRESH_TOKEN))
            val expiry = before.refreshToken!!.token.expiresAt
            assertEquals(200, restarted.mcp(tokens.text("access_token")).status)
            val next = restarted.refresh(tokens.text("refresh_token")).tokens()
            assertNotEquals(tokens.text("refresh_token"), next.text("refresh_token"))
            assertNotEquals(tokens.text("access_token"), next.text("access_token"))
            assertEquals(401, restarted.mcp(tokens.text("access_token")).status)
            assertEquals(200, restarted.mcp(next.text("access_token")).status)
            val after = assertNotNull(restarted.store.findByToken(next.text("refresh_token"), OAuth2TokenType.REFRESH_TOKEN))
            assertEquals(expiry, after.refreshToken!!.token.expiresAt)
            assertEquals(2, db.count("oauth2_refresh_history"))
            assertEquals(0, restarted.fetches.get(), "Refresh must use persisted identity, not refetch a URL")
            assertError(restarted.refresh(tokens.text("refresh_token")), "invalid_grant")
            assertEquals(401, restarted.mcp(next.text("access_token")).status)
            assertError(restarted.refresh(next.text("refresh_token")), "invalid_grant")
            assertEquals(0, db.count("oauth2_authorization"))
            assertEquals(0, db.count("oauth2_refresh_history"))
        }
    }

    @Test fun `concurrent refresh and code exchange serialize and cannot issue two continuations`() = database { db ->
        OAuthFixture(db).use { f ->
            val code = f.authorize()
            val codeResults = concurrently { f.exchange(code) }
            assertEquals(listOf(200, 400), codeResults.map { it.status }.sorted())
            assertEquals(1, db.count("oauth2_refresh_history"))
            val first = codeResults.single { it.status == 200 }.tokens()
            // Code replay invalidates its access token by framework policy; obtain an independent grant.
            val active = f.exchange(f.authorize()).tokens()
            val results = concurrently { f.refresh(active.text("refresh_token")) }
            assertEquals(listOf(200, 400), results.map { it.status }.sorted())
            val winner = results.single { it.status == 200 }.tokens()
            assertError(results.single { it.status == 400 }, "invalid_grant")
            assertEquals(401, f.mcp(winner.text("access_token")).status)
            assertError(f.refresh(winner.text("refresh_token")), "invalid_grant")
            assertNull(f.store.findByToken(winner.text("access_token"), OAuth2TokenType.ACCESS_TOKEN))
            assertNotEquals(first.text("access_token"), winner.text("access_token"))
        }
    }

    @Test fun `client and user boundaries expiry and both revocation paths remain enforced`() = database { db ->
        OAuthFixture(db).use { f ->
            val first = f.exchange(f.authorize()).tokens()
            f.clients.save(oauthTestClient("other-client"))
            val wrong = f.refresh(first.text("refresh_token"), mapOf("client_id" to "other-client"))
            assertEquals(400, wrong.status)
            f.form("/oauth2/revoke", listOf("client_id" to "other-client", "token" to first.text("refresh_token")))
            assertEquals(200, f.mcp(first.text("access_token")).status)
            val grantId = assertNotNull(f.store.findByToken(first.text("access_token"), OAuth2TokenType.ACCESS_TOKEN)).id
            val otherPage = f.browserGet("/oauth/connections", cookie = "other-session").response.contentAsString
            assertFalse(otherPage.contains(grantId))
            val otherCsrf = csrf(f.browserGet("/oauth/login", cookie = null).response.contentAsString)
            f.browserPost("/oauth/connections/revoke", mapOf("authorization_id" to grantId, "_csrf" to otherCsrf), cookie = "other-session")
            assertEquals(200, f.mcp(first.text("access_token")).status)
            val ownPage = f.browserGet("/oauth/connections").response.contentAsString
            assertTrue(ownPage.contains(grantId))
            assertEquals(303, f.browserPost("/oauth/connections/revoke", mapOf("authorization_id" to grantId, "_csrf" to csrf(ownPage))).response.status)
            assertEquals(401, f.mcp(first.text("access_token")).status)
            assertError(f.refresh(first.text("refresh_token")), "invalid_grant")

            val second = f.exchange(f.authorize()).tokens()
            assertEquals(200, f.form("/oauth2/revoke", listOf("client_id" to CLIENT, "token" to second.text("access_token"))).status)
            assertError(f.refresh(second.text("refresh_token")), "invalid_grant")
            val third = f.exchange(f.authorize()).tokens()
            f.users.loginAllowed = false
            assertEquals(401, f.mcp(third.text("access_token")).status)
            assertError(f.refresh(third.text("refresh_token")), "invalid_grant")
            f.users.loginAllowed = true
            db.jdbc.update("UPDATE oauth2_authorization SET access_token_issued_at = ?, access_token_expires_at = ?, refresh_token_issued_at = ?, refresh_token_expires_at = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now().minusSeconds(1)),
                Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now().minusSeconds(1)))
            assertEquals(401, f.mcp(third.text("access_token")).status)
            assertError(f.refresh(third.text("refresh_token")), "invalid_grant")
        }
    }

    @Test fun `resource server enforces persisted issuer subject audience scope and last access expiry`() = database { db ->
        OAuthFixture(db).use { f ->
            val tokens = f.exchange(f.authorize()).tokens()
            val raw = tokens.text("access_token")
            val original = assertNotNull(f.store.findByToken(raw, OAuth2TokenType.ACCESS_TOKEN))
            val access = assertNotNull(original.accessToken)
            val claims = assertNotNull(access.claims)
            for ((key, value) in listOf("iss" to "https://other.invalid", "sub" to OTHER,
                "aud" to listOf("https://other.invalid/mcp"), "aud" to listOf(RESOURCE, "https://other.invalid/mcp"))) {
                val changed = org.springframework.security.oauth2.server.authorization.OAuth2Authorization.from(original)
                    .token(access.token) { it[org.springframework.security.oauth2.server.authorization.OAuth2Authorization.Token.CLAIMS_METADATA_NAME] = claims + (key to value) }.build()
                f.store.save(changed)
                assertEquals(401, f.mcp(raw).status, "Wrong $key was accepted")
            }
            f.store.save(original)
            val restricted = org.springframework.security.oauth2.core.OAuth2AccessToken(access.token.tokenType, raw,
                access.token.issuedAt, access.token.expiresAt, setOf("not-mcp"))
            f.store.save(org.springframework.security.oauth2.server.authorization.OAuth2Authorization.from(original).token(restricted).build())
            val insufficient = f.mcp(raw)
            assertEquals(403, insufficient.status)
            assertTrue(assertNotNull(insufficient.getHeader("WWW-Authenticate")).contains("insufficient_scope"))
            f.store.save(original)
            db.jdbc.update("UPDATE oauth2_authorization SET refresh_token_issued_at = ?, refresh_token_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now().minusSeconds(1)), original.id)
            // The final access token can still be live after the refresh lifetime ends.
            assertEquals(200, f.mcp(raw).status)
            assertEquals(original.id, f.store.connections(OWNER).single().id)
        }
    }

    @Test fun `transaction rollback never returns minted tokens and leaves original refresh usable`() = database { db ->
        OAuthFixture(db).use { f ->
            val original = f.exchange(f.authorize()).tokens()
            db.jdbc.execute("""CREATE FUNCTION reject_oauth_update() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION 'synthetic OAuth persistence failure'; END; ${'$'}${'$'}""")
            db.jdbc.execute("CREATE TRIGGER reject_oauth_update BEFORE UPDATE ON oauth2_authorization FOR EACH ROW EXECUTE FUNCTION reject_oauth_update()")
            val failure = f.refresh(original.text("refresh_token"))
            assertEquals(503, failure.status)
            assertEquals("server_error", failure.json().text("error"))
            assertFalse(failure.contentAsString.contains("access_token"))
            assertFalse(failure.contentAsString.contains("refresh_token"))
            assertFalse(failure.contentAsString.contains("synthetic OAuth persistence failure"))
            assertEquals(1, db.count("oauth2_refresh_history"))
            db.jdbc.execute("DROP TRIGGER reject_oauth_update ON oauth2_authorization")
            assertEquals(200, f.mcp(original.text("access_token")).status)
            f.refresh(original.text("refresh_token")).tokens()
        }
    }

    @Test fun `CIMD validation persists no anonymous rows and restricts metadata policy`() = database { db ->
        OAuthFixture(db).use { f ->
            val client = assertNotNull(f.clients.findByClientId(CLIENT))
            assertEquals(0, db.count("oauth2_registered_client"))
            assertTrue(client.clientSettings.isRequireProofKey)
            assertTrue(client.clientSettings.isRequireAuthorizationConsent)
            for (bad in listOf(metadata() + ("client_id" to "https://other.invalid/client.json"),
                metadata() + ("client_secret" to "not-allowed"), metadata() + ("redirect_uris" to listOf("http://public.example/cb")),
                metadata() + ("token_endpoint_auth_method" to "client_secret_basic"), metadata() + ("redirect_uris" to emptyList<String>()))) {
                assertFailsWith<org.springframework.security.oauth2.core.OAuth2AuthenticationException> { f.clients.clientFromMetadata(CLIENT, bad) }
            }
            f.clients.save(client)
            assertEquals(1, db.count("oauth2_registered_client"))
            assertNotNull(f.clients.findStoredByClientId(CLIENT))
        }
    }

    private fun concurrently(action: () -> MockHttpServletResponse): List<MockHttpServletResponse> {
        val workers = Executors.newFixedThreadPool(2)
        try {
            val ready = CountDownLatch(2); val start = CountDownLatch(1)
            val futures = (1..2).map { workers.submit(Callable {
                ready.countDown(); check(start.await(10, TimeUnit.SECONDS)); action()
            }) }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    private fun database(block: (OAuthDatabase) -> Unit) {
        assumeTrue(System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true", "Explicit isolated PostgreSQL opt-in required")
        val url = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL"))
        val user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
        val password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        require(url.isNotBlank() && user.isNotBlank())
        val schema = "oauth_test_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = DriverManagerDataSource(url, user, password)
        JdbcTemplate(admin).execute("CREATE SCHEMA $schema")
        try {
            val source = DriverManagerDataSource(url + (if ('?' in url) "&" else "?") + "currentSchema=$schema,public", user, password)
            val old = Flyway.configure().dataSource(source).locations("classpath:db/migration/postgres").schemas(schema).defaultSchema(schema).target("74").load()
            old.migrate()
            assertEquals("74", old.info().current().version.toString())
            val flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration/postgres").schemas(schema).defaultSchema(schema).load()
            flyway.migrate()
            assertEquals("75", flyway.info().current().version.toString())
            block(OAuthDatabase(source, flyway))
        } finally { JdbcTemplate(admin).execute("DROP SCHEMA $schema CASCADE") }
    }
}

private data class OAuthDatabase(val source: DataSource, val flyway: Flyway) {
    val jdbc = JdbcTemplate(source)
    fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))
}

private class OAuthFixture(db: OAuthDatabase) : AutoCloseable {
    val users = OAuthUsers()
    val fetches = AtomicInteger()
    var metadataAvailable = true
    private val context = AnnotationConfigWebApplicationContext()
    private val mvc: MockMvc
    private val browser: MockHttpSession
    val clients: McpOAuthClientRepository
    val store: McpOAuthAuthorizationService
    init {
        context.servletContext = MockServletContext()
        context.environment.propertySources.addFirst(MapPropertySource("oauth-test", mapOf("gromozeka.oauth.issuer" to "https://localhost")))
        context.register(SpringHttpTestFixture.MvcConfiguration::class.java, GromozekaHttpSecurityConfiguration::class.java,
            McpOAuthConfiguration::class.java, McpOAuthBrowserHttp::class.java)
        context.addBeanFactoryPostProcessor { beans ->
            beans.registerSingleton("postgresFlyway", db.flyway)
            beans.registerSingleton("testDataSource", db.source)
            beans.registerSingleton("testUsers", users)
            beans.registerSingleton("testTokens", Mockito.mock(PersonalAccessTokenService::class.java))
            beans.registerSingleton("testWorkers", WorkerGatewayAuthenticationService(Mockito.mock(WorkerEnrollmentRepository::class.java)))
            beans.registerSingleton("testHttpSettings", ServerHttpSettings())
            beans.registerSingleton("testLimiter", AuthenticationAttemptLimiter())
            beans.registerSingleton("testErrors", GromozekaHttpExceptionHandler())
            beans.registerSingleton("testMcp", OAuthMcpProbe())
            val resolver = ClientIdMetadataDocumentResolver { uri ->
                fetches.incrementAndGet(); check(metadataAvailable) { "Synthetic metadata outage" }
                check(uri.toString() == CLIENT)
                ClientIdMetadataDocumentResolver.Result(OAuth2ClientRegistration.withClaims(metadata()).build(),
                    ClientIdMetadataDocumentResolver.ResponseAttributes(300))
            }
            (beans as BeanDefinitionRegistry).registerBeanDefinition("testCimdResolver", RootBeanDefinition(ClientIdMetadataDocumentResolver::class.java).apply {
                isPrimary = true; instanceSupplier = Supplier { resolver }
            })
        }
        context.refresh()
        clients = context.getBean(McpOAuthClientRepository::class.java)
        store = context.getBean(McpOAuthAuthorizationService::class.java)
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain", Filter::class.java)).build()
        browser = MockHttpSession(context.servletContext)
    }
    fun call(builder: MockHttpServletRequestBuilder): MvcResult {
        val result = mvc.perform(builder.secureTestRequest()).andReturn()
        return if (result.request.isAsyncStarted) {
            result.getAsyncResult(10_000); mvc.perform(asyncDispatch(result)).andReturn()
        } else result
    }
    fun browserGet(location: String, cookie: String? = "owner-session"): MvcResult = call(localGet(location).session(browser).apply {
        cookie?.let { cookie(jakarta.servlet.http.Cookie(SESSION_COOKIE_NAME, it)) }
    })
    fun browserPost(path: String, values: Map<String, String>, cookie: String? = "owner-session"): MvcResult = call(
        post(path).session(browser).contentType("application/x-www-form-urlencoded").content(formBody(values.entries.map { it.key to it.value })).apply {
            cookie?.let { cookie(jakarta.servlet.http.Cookie(SESSION_COOKIE_NAME, it)) }
        })
    fun authorization(changes: Map<String, String?> = emptyMap(), cookie: String? = "owner-session"): MvcResult {
        val values = mutableMapOf("response_type" to "code", "client_id" to CLIENT, "redirect_uri" to REDIRECT,
            "scope" to "mcp", "state" to "synthetic-client-state", "resource" to RESOURCE,
            "code_challenge" to CHALLENGE, "code_challenge_method" to "S256")
        changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        return browserGet("/oauth2/authorize?" + formBody(values.entries.map { it.key to it.value }), cookie)
    }
    fun begin(): String {
        val result = authorization().response
        assertEquals(302, result.status)
        return assertNotNull(result.redirectedUrl).also { assertEquals("/oauth/consent", URI(it).path) }
    }
    fun consent(pending: String): String = browserGet(pending).response.let { assertEquals(200, it.status); it.contentAsString }
    fun approve(pending: String, html: String): String {
        val fields = query(pending).filterKeys { it in setOf("client_id", "state") } + mapOf("scope" to "mcp", "_csrf" to csrf(html))
        val response = browserPost("/oauth2/authorize", fields).response
        assertEquals(302, response.status)
        val redirect = assertNotNull(response.redirectedUrl)
        assertEquals(URI(REDIRECT).authority, URI(redirect).authority)
        assertEquals("synthetic-client-state", query(redirect)["state"])
        return assertNotNull(query(redirect)["code"], "No authorization code; error=${query(redirect)["error"]}")
    }
    fun authorize(): String {
        val result = authorization().response
        assertEquals(302, result.status)
        val target = assertNotNull(result.redirectedUrl)
        // Previously consented scopes may skip the page through the framework's ordinary policy.
        return if (URI(target).path == "/oauth/consent") approve(target, consent(target)) else assertNotNull(query(target)["code"])
    }
    fun exchange(code: String, changes: Map<String, String> = emptyMap()) = form("/oauth2/token", (exchangeFields(code) + changes).entries.map { it.key to it.value })
    fun refresh(token: String, changes: Map<String, String> = emptyMap()) = form("/oauth2/token",
        (mapOf("grant_type" to "refresh_token", "client_id" to CLIENT, "refresh_token" to token, "resource" to RESOURCE) + changes).entries.map { it.key to it.value })
    fun form(path: String, fields: List<Pair<String, String>>): MockHttpServletResponse = call(post(path)
        .contentType("application/x-www-form-urlencoded").content(formBody(fields))).response
    fun mcp(token: String): MockHttpServletResponse = call(get("/mcp").header("Authorization", "Bearer $token")).response
    override fun close() = context.close()
}

@TestComponent
@RestController
private class OAuthMcpProbe {
    @GetMapping("/mcp") fun caller(request: HttpServletRequest): String =
        (request.getAttribute(AUTHENTICATED_MCP_CALLER) as AuthenticatedMcpCaller).user.id.value
}

private class OAuthUsers : AuthenticationService, UserDirectoryService {
    var loginAllowed = true
    val logins = AtomicInteger()
    val passwordArrays = mutableListOf<CharArray>()
    private fun user(id: String) = User(User.Id(id), listOf(UserIdentity.LocalLogin(if (id == OWNER) "owner" else "other")),
        "Synthetic $id", User.Status.ACTIVE, createdAt = kotlin.time.Instant.DISTANT_PAST, updatedAt = kotlin.time.Instant.DISTANT_PAST,
        loginAllowed = loginAllowed, aiAllowed = false)
    override suspend fun findActiveById(id: User.Id): User? = if (id.value in setOf(OWNER, OTHER)) user(id.value) else null
    override suspend fun listActive() = listOf(user(OWNER), user(OTHER))
    override suspend fun authenticate(sessionToken: String): AuthenticatedUser? = when {
        !loginAllowed -> null
        sessionToken == "owner-session" -> AuthenticatedUser(user(OWNER), UserSession.Id("owner-session"))
        sessionToken == "other-session" -> AuthenticatedUser(user(OTHER), UserSession.Id("other-session"))
        else -> null
    }
    override suspend fun login(username: String, password: CharArray, clientLabel: String?): IssuedUserSession {
        logins.incrementAndGet(); passwordArrays += password
        if (username != "owner" || password.concatToString() != "synthetic-password") throw AuthenticationRejectedException()
        return IssuedUserSession(user(OWNER), UserSession.Id("owner-session"), "owner-session",
            kotlin.time.Instant.fromEpochSeconds(Instant.now().plusSeconds(3600).epochSecond))
    }
    override suspend fun hasUsers() = true
    override suspend fun createFirstUser(bootstrapToken: String, username: String, displayName: String, password: CharArray, clientLabel: String?): IssuedUserSession = error("No bootstrap in OAuth")
    override suspend fun logout(sessionToken: String) = error("Not used")
    override suspend fun revokeAllSessions(userId: User.Id) = error("Not used")
}

private const val OWNER = "synthetic-oauth-owner"
private const val OTHER = "synthetic-oauth-other"
private const val CLIENT = "https://synthetic.example/client.json"
private const val RESOURCE = "https://localhost/mcp"
private const val REDIRECT = "http://127.0.0.1:49231/callback"
private val VERIFIER = "s".repeat(64)
private val CHALLENGE = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(VERIFIER.toByteArray(UTF_8)))
private fun metadata(): Map<String, Any> = mapOf("client_id" to CLIENT, "client_name" to "Test <script> app", "redirect_uris" to listOf("http://127.0.0.1/callback"),
    "grant_types" to listOf("authorization_code", "refresh_token"), "response_types" to listOf("code"), "token_endpoint_auth_method" to "none")
private fun exchangeFields(code: String) = mapOf("grant_type" to "authorization_code", "client_id" to CLIENT, "code" to code,
    "redirect_uri" to REDIRECT, "code_verifier" to VERIFIER, "resource" to RESOURCE)
private fun formBody(fields: List<Pair<String, String>>) = fields.joinToString("&") { (key, value) -> "${URLEncoder.encode(key, UTF_8)}=${URLEncoder.encode(value, UTF_8)}" }
private fun query(value: String): Map<String, String> = URI(value).rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
    val pair = it.split('=', limit = 2); URLDecoder.decode(pair[0], UTF_8) to URLDecoder.decode(pair.getOrElse(1) { "" }, UTF_8)
}
private fun localGet(location: String): MockHttpServletRequestBuilder = get(URI(location).path).apply { query(location).forEach { (key, value) -> queryParam(key, value) } }
private fun csrf(html: String): String = HtmlUtils.htmlUnescape(assertNotNull(Regex("name=\"_csrf\" value=\"([^\"]+)\"").find(html)).groupValues[1])
private fun MockHttpServletResponse.json(): JsonObject = Json.parseToJsonElement(contentAsString).jsonObject
private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
private fun MockHttpServletResponse.tokens(): JsonObject {
    assertEquals(200, status, "Token request status=$status, error=${runCatching { json()["error"] }.getOrNull()}")
    return json().also { assertTrue(it.text("access_token").isNotBlank()); assertTrue(it.text("refresh_token").isNotBlank()) }
}
private fun assertError(response: MockHttpServletResponse, code: String) {
    assertEquals(400, response.status)
    assertEquals(code, response.json().text("error"))
    assertFalse(response.contentAsString.contains("access_token"))
}
