package com.gromozeka.application.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualSecretArgumentsTest {
    @Test fun `visual state and markup never expand secret references`() {
        val input = """{"action":"create","state":{"form":{"text":"secret://demo"},"data":{}},"handler":{"command":"echo secret://demo"}}"""
        val result = SecretArgumentSubstitutor().prepare("grz_visual", input, mapOf("demo" to "private-test-value"))
        assertEquals(input, result.arguments)
        assertTrue(result.secretEnvironment.isEmpty())
        assertFalse(result.arguments.contains("private-test-value"))
    }

    @Test fun `private handler launch reuses secure command substitution`() {
        val result = SecretArgumentSubstitutor(environmentNameGenerator = { "VISUAL_TEST_SECRET" }, inheritedEnvironmentNames = { emptySet() })
            .prepare("grz_execute_command", """{"command":"echo secret://demo"}""", mapOf("demo" to "private-test-value"), isWindows = false)
        assertFalse(result.arguments.contains("private-test-value"))
        assertEquals(mapOf("VISUAL_TEST_SECRET" to "private-test-value"), result.secretEnvironment)
        assertTrue(result.arguments.contains("VISUAL_TEST_SECRET"))
    }
}
