package com.gromozeka.presentation.ui.session

import com.gromozeka.presentation.services.translation.data.Translation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ToolActivityCaptionTest {
    private val translation = Translation.RuntimeTranslation()

    @Test
    fun `built-in and mcp tools use deterministic activity captions`() {
        assertEquals("Reading file", caption("grz_read_file"))
        assertEquals("Editing file", caption("grz_edit_file"))
        assertEquals("Searching the web", caption("brave_web_search"))
        assertEquals("Reading web page", caption("mcp__browser__web_fetch__v2"))
        assertEquals("Searching files", caption("mcp__github__search_code"))
        assertEquals("Capturing screen", caption("mcp__playwright__browser_take_screenshot"))
        assertEquals("Using computer", caption("grz_computer_act"))
    }

    @Test
    fun `test commands are recognized without exposing command text`() {
        val input = buildJsonObject {
            put("command", "./gradlew :server:test -Ptoken=very-secret-value")
        }

        val caption = toolActivityCaption("grz_execute_command", input, translation)

        assertEquals("Running tests", caption)
        assertFalse(caption.contains("token"))
        assertFalse(caption.contains("secret"))
    }

    @Test
    fun `unknown tools expose only a bounded sanitized metadata name`() {
        val input = buildJsonObject {
            put("url", "https://example.test/private?token=secret")
            put("password", "hunter2")
        }

        val caption = toolActivityCaption("mcp__private-host__deploy_release", input, translation)

        assertEquals("Using tool: deploy release", caption)
        assertFalse(caption.contains("private-host"))
        assertFalse(caption.contains("example.test"))
        assertFalse(caption.contains("hunter2"))
    }

    private fun caption(toolName: String): String = toolActivityCaption(toolName, null, translation)
}
