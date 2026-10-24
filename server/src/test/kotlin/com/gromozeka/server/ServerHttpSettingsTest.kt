package com.gromozeka.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerHttpSettingsTest {
    @Test
    fun `zero requests an ephemeral listener but other invalid ports are rejected`() {
        assertEquals(0, ServerHttpSettings(port = 0).port)
        assertEquals(65535, ServerHttpSettings(port = 65535).port)
        assertFailsWith<IllegalArgumentException> { ServerHttpSettings(port = -1) }
        assertFailsWith<IllegalArgumentException> { ServerHttpSettings(port = 65536) }
    }

    @Test
    fun `ephemeral listener reports the actual bound port`() {
        assertEquals(43821, ServerHttpSettings(port = 0).requireBoundPort(43821))
    }

    @Test
    fun `fixed listener must bind exactly the configured port`() {
        assertEquals(8765, ServerHttpSettings(port = 8765).requireBoundPort(8765))
        assertFailsWith<IllegalStateException> { ServerHttpSettings(port = 8765).requireBoundPort(8766) }
    }

    @Test
    fun `missing or unstarted listeners never produce a readiness marker`() {
        for (configured in listOf(0, 8765)) {
            for (actual in listOf(null, -1, 0, 65536)) {
                assertFailsWith<IllegalStateException> { ServerHttpSettings(port = configured).requireBoundPort(actual) }
            }
        }
    }
}
