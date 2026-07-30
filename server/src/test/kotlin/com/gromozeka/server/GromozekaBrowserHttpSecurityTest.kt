package com.gromozeka.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GromozekaBrowserHttpSecurityTest {
    @Test
    fun `browser security headers are added to responses`() = testApplication {
        application {
            install(gromozekaBrowserSecurityHeaders)
            routing {
                get("/") {
                    call.respondText("ok")
                }
            }
        }

        val response = client.get("/")

        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals(
            "camera=(self), microphone=(self), geolocation=(self)",
            response.headers["Permissions-Policy"],
        )
    }

    @Test
    fun `route protection rejects foreign browser origin`() = testApplication {
        application {
            installHttpAuthenticationErrors()
            routing {
                route("/protected") {
                    install(gromozekaBrowserOriginProtection)
                    get {
                        call.respondText("not reached")
                    }
                }
            }
        }

        val response = client.get("/protected") {
            header(HttpHeaders.Origin, "https://attacker.example")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
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
