package com.gromozeka.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GromozekaTransportSecurityTest {
    @Test
    fun `direct HTTPS is secure without forwarded-header trust`() {
        assertTrue(
            isSecureTransport(
                scheme = "https",
                remoteAddress = "203.0.113.10",
                forwardedProto = null,
                trustForwardedHttps = false,
            )
        )
    }

    @Test
    fun `loopback HTTP is secure without forwarded-header trust`() {
        assertTrue(
            isSecureTransport(
                scheme = "http",
                remoteAddress = "127.0.0.1",
                forwardedProto = null,
                trustForwardedHttps = false,
            )
        )
    }

    @Test
    fun `forwarded HTTPS is rejected unless explicitly trusted`() {
        assertFalse(
            isSecureTransport(
                scheme = "http",
                remoteAddress = "172.18.0.1",
                forwardedProto = "https",
                trustForwardedHttps = false,
            )
        )
    }

    @Test
    fun `trusted forwarded HTTPS is secure`() {
        assertTrue(
            isSecureTransport(
                scheme = "http",
                remoteAddress = "172.18.0.1",
                forwardedProto = "https",
                trustForwardedHttps = true,
            )
        )
    }

    @Test
    fun `trusted proxy does not make arbitrary forwarded protocols secure`() {
        assertFalse(
            isSecureTransport(
                scheme = "http",
                remoteAddress = "172.18.0.1",
                forwardedProto = "https, http",
                trustForwardedHttps = true,
            )
        )
        assertFalse(
            isSecureTransport(
                scheme = "http",
                remoteAddress = "172.18.0.1",
                forwardedProto = "http",
                trustForwardedHttps = true,
            )
        )
    }

    @Test
    fun `forwarded HTTPS trust is disabled by default and parsed strictly`() {
        assertFalse(resolveTrustForwardedHttps(null))
        assertFalse(resolveTrustForwardedHttps("false"))
        assertTrue(resolveTrustForwardedHttps("true"))
        assertFailsWith<IllegalStateException> {
            resolveTrustForwardedHttps("yes")
        }
    }
}
