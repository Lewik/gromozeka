package com.gromozeka.server

import kotlinx.coroutines.runBlocking
import org.springframework.boot.test.context.TestComponent
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GromozekaBrowserHttpSecurityTest {
    @Test
    fun `browser security headers are added to responses`() = runBlocking<Unit> {
        SpringHttpTestFixture(BrowserHeadersTestController()).use { http ->
            val response = http.perform(get("/").secureTestRequest())
            assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
            assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
            assertEquals("DENY", response.getHeader("X-Frame-Options"))
            assertEquals("camera=(self), microphone=(self), geolocation=(self), display-capture=(self)",
                response.getHeader("Permissions-Policy"))
        }
    }

    @Test
    fun `route protection rejects foreign browser origin`() = runBlocking<Unit> {
        SpringHttpTestFixture(BrowserHeadersTestController()).use { http ->
            val response = http.perform(get("/api/protected").secureTestRequest().header("Origin", "https://attacker.example"))
            assertEquals(403, response.status)
        }
    }

    @Test
    fun `native requests without origin are allowed`() {
        assertTrue(isAllowedBrowserOrigin(origin = null, host = null))
    }

    @Test
    fun `matching browser origins are allowed`() {
        assertTrue(
            isAllowedBrowserOrigin(
                origin = "https://runtime.example.com",
                host = "runtime.example.com",
            )
        )
        assertTrue(
            isAllowedBrowserOrigin(
                origin = "https://runtime.example.com",
                host = "runtime.example.com:443",
            )
        )
        assertTrue(
            isAllowedBrowserOrigin(
                origin = "http://127.0.0.1:8765",
                host = "127.0.0.1:8765",
            )
        )
    }

    @Test
    fun `foreign or opaque browser origins are rejected`() {
        assertFalse(
            isAllowedBrowserOrigin(
                origin = "https://attacker.example",
                host = "runtime.example.com",
            )
        )
        assertFalse(isAllowedBrowserOrigin(origin = "null", host = "runtime.example.com"))
        assertFalse(isAllowedBrowserOrigin(origin = "file:///tmp/client.html", host = "runtime.example.com"))
    }

    @Test
    fun `origin port must match request host port`() {
        assertFalse(
            isAllowedBrowserOrigin(
                origin = "https://runtime.example.com:8443",
                host = "runtime.example.com",
            )
        )
    }
}

@TestComponent
@RestController
private class BrowserHeadersTestController {
    @GetMapping("/", "/api/protected") fun echo(): String = "ok"
}
