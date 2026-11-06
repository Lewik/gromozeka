package com.gromozeka.application.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualSecretArgumentsTest {
    @Test fun `visual state and markup never expand secret references`() {
        val input = """{"action":"create","state":{"form":{"text":"secret://demo"},"data":{}},"handler":{"command":"python3 handler.py","secret_env":{"VISUAL_TOKEN":"secret://demo"}}}"""
        val result = SecretArgumentSubstitutor().prepare("grz_visual", input, mapOf("demo" to "private-test-value"))
        assertEquals(input, result.arguments)
        assertTrue(result.secretEnvironment.isEmpty())
        assertFalse(result.arguments.contains("private-test-value"))
    }

    @Test fun `private handler launch resolves references separately from public spec`() {
        val spec = com.gromozeka.domain.visual.VisualHandlerSpec(
            com.gromozeka.domain.model.WorkspaceMount.Id("mount"), "python3 handler.py",
            secretEnv = mapOf("VISUAL_TOKEN" to "secret://demo"),
        )
        val environment = CommandSecretEnvironment.resolve(spec.command, spec.secretEnv, mapOf("demo" to "private-test-value"))
        assertEquals(mapOf("VISUAL_TOKEN" to "private-test-value"), environment)
        assertEquals(mapOf("VISUAL_TOKEN" to "secret://demo"), spec.secretEnv)
        assertFalse(spec.toString().contains("private-test-value"))
    }
}
