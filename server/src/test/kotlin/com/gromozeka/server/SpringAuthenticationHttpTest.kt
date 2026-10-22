package com.gromozeka.server

import com.gromozeka.application.service.AuthenticationRejectedException
import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.IssuedUserSession
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.service.AuthenticationService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class SpringAuthenticationHttpTest {
    @Test
    fun `bootstrap status login and logout preserve opaque session cookies and clear password arrays`() = runBlocking<Unit> {
        val service = AuthenticationStub()
        val settings = ServerHttpSettings(secureCookie = true)
        SpringHttpTestFixture(GromozekaAuthenticationHttp(service, AuthenticationAttemptLimiter(), settings),
            authentication = service, settings = settings).use { http ->
            val initial = http.perform(get("/auth/status").secureTestRequest())
            assertEquals(200, initial.status)
            assertEquals("false", initial.json().getValue("initialized").jsonPrimitive.content)
            val created = http.perform(post("/auth/bootstrap").secureTestRequest().contentType("application/json")
                .content(buildJsonObject {
                    put("bootstrapToken", "synthetic-bootstrap"); put("username", "owner")
                    put("displayName", "Owner"); put("password", "synthetic-password"); put("clientLabel", "test")
                }.toString()))
            assertEquals(201, created.status)
            assertEquals("no-store", created.getHeader(HttpHeaders.CACHE_CONTROL))
            assertFalse(created.contentAsString.contains(service.issued.token))
            val cookie = assertNotNull(created.getHeader(HttpHeaders.SET_COOKIE))
            for (part in listOf("$SESSION_COOKIE_NAME=${service.issued.token}", "Path=/", "Secure", "HttpOnly", "SameSite=Strict")) {
                assertTrue(cookie.contains(part), cookie)
            }
            assertEquals(listOf("synthetic-password"), service.receivedPasswords)
            assertTrue(service.passwordArrays.all { chars -> chars.all { it == '\u0000' } })
            val authenticated = http.perform(get("/auth/status").secureTestRequest(service.issued.token))
            assertEquals("true", authenticated.json().getValue("initialized").jsonPrimitive.content)
            assertEquals(service.issued.user.id.value,
                authenticated.json().getValue("authenticatedUser").jsonObject.getValue("id").jsonPrimitive.content)
            val login = http.perform(post("/auth/login").secureTestRequest().contentType("application/json").content(loginBody))
            assertEquals(200, login.status)
            assertTrue(service.passwordArrays.all { chars -> chars.all { it == '\u0000' } })
            val logout = http.perform(post("/auth/logout").secureTestRequest(service.issued.token))
            assertEquals(204, logout.status)
            assertEquals(listOf(service.issued.token), service.loggedOut)
            assertTrue(assertNotNull(logout.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0"))
            assertEquals(kotlinx.serialization.json.JsonNull,
                http.perform(get("/auth/status").secureTestRequest(service.issued.token)).json()["authenticatedUser"])
        }
    }

    @Test
    fun `HTTPS and browser origin are checked before authentication effects`() = runBlocking<Unit> {
        val service = AuthenticationStub()
        val settings = ServerHttpSettings()
        SpringHttpTestFixture(GromozekaAuthenticationHttp(service, AuthenticationAttemptLimiter(), settings),
            authentication = service, settings = settings).use { http ->
            for (path in listOf("/auth/bootstrap", "/auth/login", "/auth/logout")) {
                val insecure = http.perform(post(path).secureTestRequest().secure(false).with {
                    it.scheme = "http"; it.serverPort = 80; it
                }.contentType("application/json").content(loginBody))
                assertEquals(426, insecure.status, path)
                assertEquals("HTTPS_REQUIRED", insecure.json().getValue("code").jsonPrimitive.content)
                val crossOrigin = http.perform(post(path).secureTestRequest().header("Origin", "https://foreign.invalid")
                    .contentType("application/json").content(loginBody))
                assertEquals(403, crossOrigin.status, path)
                assertEquals("CROSS_ORIGIN_REJECTED", crossOrigin.json().getValue("code").jsonPrimitive.content)
            }
            assertEquals(0, service.loginCalls)
            assertFalse(service.initialized)
            assertTrue(service.loggedOut.isEmpty())
        }
    }

    @Test
    fun `request bytes are bounded even without Content Length and malformed UTF8 is rejected`() = runBlocking<Unit> {
        val service = AuthenticationStub()
        val settings = ServerHttpSettings()
        SpringHttpTestFixture(GromozekaAuthenticationHttp(service, AuthenticationAttemptLimiter(), settings),
            authentication = service, settings = settings).use { http ->
            val tooLarge = ByteArray(16 * 1024 + 1) { 0x61 }
            for (unknownLength in listOf(false, true)) {
                val request = post("/auth/login").secureTestRequest().contentType("application/json").content(tooLarge)
                if (unknownLength) request.with { original ->
                    org.mockito.Mockito.spy(original).also { streamed ->
                        org.mockito.Mockito.doReturn(-1).`when`(streamed).contentLength
                        org.mockito.Mockito.doReturn(-1L).`when`(streamed).contentLengthLong
                    }
                }
                val response = http.perform(request)
                assertEquals(413, response.status, "unknownLength=$unknownLength")
                assertEquals("REQUEST_TOO_LARGE", response.json().getValue("code").jsonPrimitive.content)
            }
            val malformed = http.perform(post("/auth/login").secureTestRequest().contentType("application/json")
                .content(byteArrayOf(0xC3.toByte(), 0x28)))
            assertEquals(400, malformed.status)
            assertEquals("REQUEST_READ_FAILED", malformed.json().getValue("code").jsonPrimitive.content)
            assertEquals(0, service.loginCalls)
        }
    }

    @Test
    fun `failed logins preserve rate limiting and never issue a cookie`() = runBlocking<Unit> {
        val service = AuthenticationStub().apply { rejectLogin = true }
        val settings = ServerHttpSettings()
        SpringHttpTestFixture(GromozekaAuthenticationHttp(service, AuthenticationAttemptLimiter { 0L }, settings),
            authentication = service, settings = settings).use { http ->
            repeat(8) {
                val response = http.perform(post("/auth/login").secureTestRequest().contentType("application/json").content(loginBody))
                assertEquals(401, response.status)
                assertEquals("INVALID_CREDENTIALS", response.json().getValue("code").jsonPrimitive.content)
                assertEquals(null, response.getHeader(HttpHeaders.SET_COOKIE))
            }
            val blocked = http.perform(post("/auth/login").secureTestRequest().contentType("application/json").content(loginBody))
            assertEquals(429, blocked.status)
            assertEquals("900", blocked.getHeader(HttpHeaders.RETRY_AFTER))
            assertEquals(8, service.loginCalls)
            assertTrue(service.passwordArrays.all { chars -> chars.all { it == '\u0000' } })
        }
    }

    private val loginBody = """{"username":"owner","password":"synthetic-password","clientLabel":"test"}"""
    private fun org.springframework.mock.web.MockHttpServletResponse.json() = serverToolJson.parseToJsonElement(contentAsString).jsonObject

    private class AuthenticationStub : AuthenticationService {
        val issued = IssuedUserSession(testControlMcpCaller().user, UserSession.Id("synthetic-session"),
            "synthetic-opaque-token", Clock.System.now() + 1.hours)
        var initialized = false
        var rejectLogin = false
        var loginCalls = 0
        val receivedPasswords = mutableListOf<String>()
        val passwordArrays = mutableListOf<CharArray>()
        val loggedOut = mutableListOf<String>()
        override suspend fun hasUsers() = initialized
        override suspend fun createFirstUser(bootstrapToken: String, username: String, displayName: String,
            password: CharArray, clientLabel: String?): IssuedUserSession {
            check(bootstrapToken == "synthetic-bootstrap")
            check(!initialized)
            initialized = true
            receivedPasswords += password.concatToString(); passwordArrays += password
            return issued
        }
        override suspend fun login(username: String, password: CharArray, clientLabel: String?): IssuedUserSession {
            loginCalls++
            receivedPasswords += password.concatToString(); passwordArrays += password
            if (rejectLogin) throw AuthenticationRejectedException()
            return issued
        }
        override suspend fun authenticate(sessionToken: String): AuthenticatedUser? =
            if (sessionToken == issued.token && sessionToken !in loggedOut) AuthenticatedUser(issued.user, issued.sessionId) else null
        override suspend fun logout(sessionToken: String) { loggedOut += sessionToken }
        override suspend fun revokeAllSessions(userId: User.Id) = error("Not used")
    }
}
