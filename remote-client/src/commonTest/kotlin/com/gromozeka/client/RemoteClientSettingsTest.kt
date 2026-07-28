package com.gromozeka.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteClientSettingsTest {
    @Test
    fun `normalizes server addresses to websocket endpoint`() {
        assertEquals("wss://gromozeka.example/ws", normalizeRemoteUrl("gromozeka.example"))
        assertEquals("ws://localhost:8765/ws", normalizeRemoteUrl("localhost:8765"))
        assertEquals("wss://gromozeka.example/ws", normalizeRemoteUrl("https://gromozeka.example/"))
        assertEquals("ws://localhost:8765/ws", normalizeRemoteUrl("http://localhost:8765"))
        assertEquals("ws://127.0.0.1:8765/ws", normalizeRemoteUrl("ws://127.0.0.1:8765/ws"))
        assertEquals("ws://[::1]:8765/ws", normalizeRemoteUrl("[::1]:8765"))
        assertEquals("wss://localhost.example/ws", normalizeRemoteUrl("localhost.example"))
        assertEquals("wss://127.example/ws", normalizeRemoteUrl("127.example"))
    }

    @Test
    fun `rejects ambiguous or unsafe server addresses`() {
        assertFailsWith<IllegalArgumentException> { normalizeRemoteUrl("") }
        assertFailsWith<IllegalArgumentException> { normalizeRemoteUrl("ftp://gromozeka.example") }
        assertFailsWith<IllegalArgumentException> { normalizeRemoteUrl("https://user:pass@gromozeka.example") }
        assertFailsWith<IllegalArgumentException> { normalizeRemoteUrl("https://gromozeka.example/api") }
        assertFailsWith<IllegalArgumentException> { normalizeRemoteUrl("https://gromozeka.example?token=secret") }
        assertFailsWith<IllegalArgumentException> { normalizeRemoteUrl("https://:8765") }
        assertFailsWith<IllegalArgumentException> { normalizeRemoteUrl("https://gromozeka.example:not-a-port") }
        assertFailsWith<IllegalArgumentException> { normalizeRemoteUrl("https://gromozeka.example:70000") }
        assertFailsWith<IllegalArgumentException> { normalizeRemoteUrl("https://::1") }
    }

    @Test
    fun `explicit address overrides stored and fallback addresses`() {
        val store = InMemoryRemoteClientSettingsStore().also {
            it.save(RemoteClientSettings(remoteUrl = "wss://stored.example/ws"))
        }

        assertEquals(
            "wss://explicit.example/ws",
            store.resolveRemoteUrl(
                explicitUrl = "https://explicit.example",
                fallbackUrl = "https://fallback.example",
            ),
        )
    }

    @Test
    fun `stored address overrides bundled fallback`() {
        val store = InMemoryRemoteClientSettingsStore().also {
            it.save(RemoteClientSettings(remoteUrl = "wss://stored.example/ws"))
        }

        assertEquals(
            "wss://stored.example/ws",
            store.resolveRemoteUrl(fallbackUrl = "https://bundled.example"),
        )
    }
}
