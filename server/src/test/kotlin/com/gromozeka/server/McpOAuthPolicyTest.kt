package com.gromozeka.server

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.CancellationException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class McpOAuthPolicyTest {
    @Test fun `issuer is explicit secure origin and resource cannot change audience`() {
        val settings = McpOAuthSettings.parse("https://LOCALHOST/")
        assertEquals("https://localhost", settings.issuer)
        assertTrue(settings.acceptsResource("https://localhost/mcp"))
        assertTrue(settings.acceptsResource("https://LOCALHOST/mcp"))
        for (bad in listOf(null, "https://elsewhere.invalid/mcp", "https://localhost/mcp/", "https://localhost/mcp?x=y",
            "https://localhost/mcp#x", "https://u@localhost/mcp", "https://localhost/%6dcp", "http://localhost/mcp")) {
            assertFalse(settings.acceptsResource(bad), "Unexpected accepted resource: $bad")
        }
        for (bad in listOf("", "/", "http://public.example", "https://localhost/path", "https://u@localhost", "https://localhost?x=y")) {
            assertFails { McpOAuthSettings.parse(bad) }
        }
        assertEquals("http://127.0.0.1:8771", McpOAuthSettings.parse("http://127.0.0.1:8771").issuer)
    }

    @Test fun `CIMD identifiers must be bounded HTTPS metadata URLs`() {
        assertEquals("https://app.example/client.json", validateOAuthClientId("https://app.example/client.json").toString())
        for (bad in listOf("http://app.example/client.json", "file:///tmp/client.json", "https://app.example", "https://u@app.example/client.json",
            "https://app.example/client.json#fragment", "https://app.example/" + "x".repeat(2048))) {
            val failure = assertFailsWith<OAuth2AuthenticationException> { validateOAuthClientId(bad) }
            assertEquals("invalid_client", failure.error.errorCode)
            assertNull(failure.cause)
        }
    }

    @Test fun `socket DNS policy rejects internal metadata and transition address ranges`() {
        for (ip in listOf("0.0.0.0", "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fc00::1", "2001:db8::1", "2002:7f00:1::1", "2001:0::1", "::ffff:127.0.0.1")) {
            assertFalse(isPublicOAuthMetadataAddress(InetAddress.getByName(ip)), ip)
        }
        for (ip in listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")) {
            assertTrue(isPublicOAuthMetadataAddress(InetAddress.getByName(ip)), ip)
        }
    }

    @Test fun `HTTP boundary rejects host insecure DPoP query and oversized bodies before effects`() {
        val requests = listOf(
            request().apply { removeHeader("Host"); addHeader("Host", "other.invalid") },
            request().apply { isSecure = false; scheme = "http"; remoteAddr = "203.0.113.10" },
            request().apply { addHeader("DPoP", "unsupported-proof") },
            request().apply { queryString = "resource=https%3A%2F%2Flocalhost%2Fmcp" },
            request().apply { contentType = "application/json" },
            request().apply { setContent(ByteArray(16 * 1024 + 1) { 97 }) },
        )
        for ((index, input) in requests.withIndex()) {
            var called = false
            val response = MockHttpServletResponse()
            boundary().doFilter(input, response, FilterChain { _, _ -> called = true })
            assertFalse(called, "case $index")
            assertTrue(response.status in setOf(400, 426), "case $index: ${response.status}")
            assertEquals("no-store", response.getHeader("Cache-Control"))
        }
    }

    @Test fun `form decoding retains duplicate parameters and exact Unicode without container parsing`() {
        var called = false
        val response = MockHttpServletResponse()
        boundary().doFilter(request("client_id=first&client_id=second&text=+%D7%A9%D7%9C%D7%95%D7%9D%0D%0A+"), response,
            FilterChain { raw, _ ->
                val parsed = raw as HttpServletRequest
                assertEquals(" שלום\r\n ", parsed.getParameter("text"))
                assertContentEquals(arrayOf("first", "second"), parsed.getParameterValues("client_id"))
                assertFailsWith<OAuth2AuthenticationException> { parsed.singleOAuthParameter("client_id", 2048) }
                called = true
            })
        assertTrue(called)
    }

    @Test fun `malformed UTF8 form fails with bounded error instead of reaching protocol`() {
        var called = false
        val response = MockHttpServletResponse()
        boundary().doFilter(request().apply { setContent(byteArrayOf(0xc3.toByte(), 0x28)) }, response,
            FilterChain { _, _ -> called = true })
        assertFalse(called)
        assertEquals(400, response.status)
        assertFalse(response.contentAsString.contains("Exception"))
    }

    @Test fun `HTTP boundary preserves cancellation`() {
        val cancellation = CancellationException("synthetic cancellation")
        assertSame(cancellation, assertFailsWith<CancellationException> {
            boundary().doFilter(request(), MockHttpServletResponse(), FilterChain { _, _ -> throw cancellation })
        })
    }

    @Test fun `public refresh converter identifies client without treating it as authenticated`() {
        val converter = McpOAuthPublicClientConverter()
        val input = request().apply {
            addParameter("grant_type", "refresh_token"); addParameter("client_id", "synthetic-client")
            addParameter("refresh_token", "PRIVATE_SYNTHETIC_REFRESH")
        }
        val authentication = assertNotNull(converter.convert(input))
        assertFalse(authentication.isAuthenticated)
        assertNull(authentication.credentials)
        assertFalse(authentication.toString().contains("PRIVATE_SYNTHETIC_REFRESH"))
        input.addParameter("client_id", "second")
        assertFailsWith<OAuth2AuthenticationException> { converter.convert(input) }
        input.addHeader("Authorization", "Basic synthetic")
        assertNull(converter.convert(input))
        assertNull(converter.convert(MockHttpServletRequest("GET", "/oauth2/token")))
    }

    @Test fun `public refresh tokens rotate with secure entropy and an absolute original expiry`() {
        val now = Instant.parse("2026-10-23T12:00:00Z")
        val client = oauthTestClient()
        fun context(authorization: OAuth2Authorization? = null, type: OAuth2TokenType = OAuth2TokenType.REFRESH_TOKEN) =
            DefaultOAuth2TokenContext.builder().registeredClient(client)
                .principal(UsernamePasswordAuthenticationToken.authenticated("synthetic-user", null, emptyList()))
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).tokenType(type)
                .apply { authorization?.let(::authorization) }.build()
        val generator = McpOAuthRefreshTokenGenerator(Clock.fixed(now, ZoneOffset.UTC))
        val first = assertNotNull(generator.generate(context()))
        assertEquals(now.plus(Duration.ofDays(30)), first.expiresAt)
        assertTrue(first.tokenValue.length >= 128)
        val grant = OAuth2Authorization.withRegisteredClient(client).principalName("synthetic-user")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).refreshToken(first).build()
        val later = McpOAuthRefreshTokenGenerator(Clock.fixed(now.plusSeconds(600), ZoneOffset.UTC))
        val second = assertNotNull(later.generate(context(grant)))
        assertNotEquals(first.tokenValue, second.tokenValue)
        assertEquals(first.expiresAt, second.expiresAt)
        assertNull(generator.generate(context(type = OAuth2TokenType.ACCESS_TOKEN)))
        val expired = McpOAuthRefreshTokenGenerator(Clock.fixed(checkNotNull(first.expiresAt), ZoneOffset.UTC))
        assertEquals("invalid_grant", assertFailsWith<OAuth2AuthenticationException> { expired.generate(context(grant)) }.error.errorCode)
    }

    private fun boundary() = McpOAuthBoundaryFilter(McpOAuthSettings.parse("https://localhost"), ServerHttpSettings())
    private fun request(body: String = "x=y") = MockHttpServletRequest("POST", "/oauth2/token").apply {
        isSecure = true; scheme = "https"; serverName = "localhost"; serverPort = 443
        remoteAddr = "203.0.113.10"; addHeader("Host", "localhost")
        contentType = "application/x-www-form-urlencoded"; setContent(body.toByteArray())
    }
}

internal fun oauthTestClient(id: String = "synthetic-client", redirect: String = "http://127.0.0.1/callback") =
    RegisteredClient.withId(id).clientId(id).clientName("Synthetic test app")
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
        .redirectUri(redirect).scope("mcp")
        .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
        .tokenSettings(TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.REFERENCE)
            .accessTokenTimeToLive(Duration.ofMinutes(10)).authorizationCodeTimeToLive(Duration.ofMinutes(2))
            .refreshTokenTimeToLive(Duration.ofDays(30)).reuseRefreshTokens(false).build()).build()
