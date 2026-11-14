package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationFooterLayoutTest {
    @Test
    fun growingDraftAndErrorsTakeSpaceOnlyFromChat() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val draft = mutableStateOf("")
        val error = mutableStateOf<String?>(null)
        val panel = mutableStateOf(true)
        setContent { Workspace(draft.value, { draft.value = it }, panel.value, error = error.value) }
        val footer = bounds("conversation-footer")
        val side = bounds("test-side-panel")
        val chat = bounds("test-chat")
        val input = bounds("conversation-composer-panel")
        assertEquals(footer.top, side.bottom)
        assertTrue(abs(input.center.y - footer.center.y) <= 1f, "Initial row centers: $input / $footer")
        runOnIdle { draft.value = (1..12).joinToString("\n") { "Line $it" } }
        assertTrue(bounds("conversation-composer-panel").height > input.height)
        assertTrue(bounds("test-chat").height < chat.height)
        runOnIdle { error.value = "Cannot upload attachment\nPlease select another file" }
        assertEquals(footer, bounds("conversation-footer"))
        assertEquals(side, bounds("test-side-panel"))
        onNodeWithTag(UiTestTag.SendButton.value).assertIsDisplayed()
        onNodeWithTag(UiTestTag.MessageInput.value).assertIsDisplayed()
        runOnIdle { panel.value = false }
        onNodeWithTag("test-side-panel").assertDoesNotExist()
        assertTrue(bounds("test-chat").bottom <= footer.top)
        assertEquals(footer, bounds("conversation-footer"))
    }

    @Test
    fun footerAndInputInitiallyAlignAcrossFontScaleAndControlDensity() = runDesktopComposeUiTest(width = 1900, height = 1000) {
        val scale = mutableFloatStateOf(1f)
        val density = mutableStateOf(UiDensity.COMPACT)
        setContent { Workspace("", {}, fontScale = scale.floatValue, density = density.value) }
        for (mode in listOf(UiDensity.COMPACT, UiDensity.TOUCH)) for (font in listOf(1f, 1.25f, 1.5f)) {
            runOnIdle { density.value = mode; scale.floatValue = font }
            val footer = bounds("conversation-footer")
            val input = bounds("conversation-composer-panel")
            assertTrue(abs(input.center.y - footer.center.y) <= 1f, "$mode/$font: $input / $footer")
            for (tag in footerTags) assertInside(footer, bounds(tag), tag)
        }
    }

    @Test
    fun phoneWithKeyboardSpaceKeepsInputSendStopAndMetricsReachable() = runDesktopComposeUiTest(width = 800, height = 700) {
        val width = mutableStateOf(390.dp)
        val scale = mutableFloatStateOf(1f)
        val draft = mutableStateOf((1..12).joinToString("\n") { "Line $it" })
        var stops = 0
        setContent { Box(Modifier.size(width.value, 440.dp)) {
            Workspace(draft.value, { draft.value = it }, narrow = true, fontScale = scale.floatValue,
                error = "Upload failed", density = UiDensity.TOUCH, onStop = { stops++ })
        } }
        for (w in listOf(390.dp, 320.dp)) for (font in listOf(1f, 1.5f)) {
            runOnIdle { width.value = w; scale.floatValue = font }
            val host = bounds("test-workspace")
            for (tag in listOf(UiTestTag.MessageInput.value, UiTestTag.SendButton.value, "composer-stop-execution") + footerTags) {
                onNodeWithTag(tag).assertIsDisplayed()
                assertInside(host, bounds(tag), "$w/$font $tag")
            }
        }
        onNodeWithTag("composer-stop-execution").performClick()
        runOnIdle { assertEquals(1, stops) }
        onNodeWithTag(UiTestTag.MessageInput.value).assertTextEquals(draft.value)
    }

    @Test
    fun panelAndResponsiveChangesPreserveDraftAndCaretWithoutBorderCaptions() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val draft = mutableStateOf("alpha\nbeta\ngamma")
        val panel = mutableStateOf(true)
        val narrow = mutableStateOf(false)
        setContent { Box(Modifier.width(if (narrow.value) 390.dp else 1400.dp).fillMaxHeight()) {
            Workspace(draft.value, { draft.value = it }, panel.value, narrow.value)
        } }
        onNodeWithTag(UiTestTag.MessageInput.value).performTextInputSelection(TextRange(7))
        for ((show, phone) in listOf(false to false, false to true, true to true, true to false)) {
            runOnIdle { panel.value = show; narrow.value = phone }
            val editor = onNodeWithTag(UiTestTag.MessageInput.value)
            editor.assertTextEquals("alpha\nbeta\ngamma")
            assertEquals(TextRange(7), editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            onNodeWithTag("composer-status-border").assertDoesNotExist()
        }
    }

    private fun ComposeUiTest.bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun assertInside(outer: Rect, inner: Rect, label: String) = assertTrue(
        inner.width > 0 && inner.height > 0 && inner.left >= outer.left && inner.right <= outer.right && inner.top >= outer.top && inner.bottom <= outer.bottom,
        "$label: $inner outside $outer")
    private val footerTags = listOf("conversation-queued-messages", "conversation-running-commands", "conversation-command-monitors", "conversation-elapsed-time", "composer-context-usage")
}

@Composable
private fun Workspace(draft: String, onDraft: (String) -> Unit, showPanel: Boolean = true, narrow: Boolean = false,
    error: String? = null, fontScale: Float = 1f, density: UiDensity = UiDensity.COMPACT, onStop: () -> Unit = {}) {
    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
        GromozekaTheme(density = density) {
            ConversationWorkspaceLayout(620.dp, showPanel, narrow, Modifier.fillMaxSize().testTag("test-workspace"),
                chat = { Box(Modifier.fillMaxSize().testTag("test-chat")) },
                sidePanel = { Box(Modifier.fillMaxSize().testTag("test-side-panel")) },
                composer = { TestMessageInput(draft, onDraft, inputError = error,
                    platform = if (narrow) ClientPlatform.ANDROID else ClientPlatform.DESKTOP,
                    leadingContent = { Box(Modifier.size(GromozekaTheme.controls.minHeight), contentAlignment = Alignment.Center) {
                        ConversationIndicator(ConversationIndicatorState(ConversationActivity.READY, ConversationPressure.NORMAL, false, false, emptySet()),
                            ConversationIndicatorDrawing.FULL, "Ready")
                    } },
                    beforeActionsContent = { ConversationExecutionControls(false, false, true, {}, {}, onStop) }) },
                summary = { TestFooterSummary(footerRuntime(), emptyList(), footerTokenStats(), null, {}) })
        }
    }
}
