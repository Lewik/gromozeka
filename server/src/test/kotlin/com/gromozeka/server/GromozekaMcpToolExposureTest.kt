package com.gromozeka.server

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GromozekaMcpToolExposureTest {
    @Test
    fun `blank configuration exposes only memory tools by default`() {
        val exposure = GromozekaMcpToolExposure.fromConfiguredValue(null)

        assertTrue(exposure.exposes("memory_queue_status"))
        assertTrue(exposure.exposes("memory_enrich_context"))
        assertTrue(exposure.exposes("memory_maintenance"))
        assertTrue(exposure.exposes("memory_remember"))
        assertTrue(exposure.exposes("memory_run_status"))
        assertFalse(exposure.exposes("unified_search"))
        assertFalse(exposure.exposes("grz_execute_command"))
    }

    @Test
    fun `all configuration exposes every tool`() {
        val exposure = GromozekaMcpToolExposure.fromConfiguredValue("all")

        assertTrue(exposure.exposes("grz_execute_command"))
        assertTrue(exposure.exposes("anything_dynamic"))
    }

    @Test
    fun `explicit configuration accepts comma and whitespace separated tool names`() {
        val exposure = GromozekaMcpToolExposure.fromConfiguredValue(
            "memory_enrich_context, memory_remember\nmemory_run_status memory_queue_status memory_maintenance"
        )

        assertTrue(exposure.exposes("memory_queue_status"))
        assertTrue(exposure.exposes("memory_enrich_context"))
        assertTrue(exposure.exposes("memory_maintenance"))
        assertTrue(exposure.exposes("memory_remember"))
        assertTrue(exposure.exposes("memory_run_status"))
        assertFalse(exposure.exposes("unified_search"))
        assertFalse(exposure.exposes("brave_web_search"))
    }

    @Test
    fun `unknown explicit tool names fail fast`() {
        val exposure = GromozekaMcpToolExposure.fromConfiguredValue("memory_enrich_context,missing_tool")

        assertFailsWith<IllegalArgumentException> {
            exposure.validateAgainst(setOf("memory_enrich_context", "memory_remember", "memory_maintenance"))
        }
    }
}
