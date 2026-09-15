package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.services.theming.data.DarkTheme
import com.gromozeka.presentation.services.theming.data.LightTheme
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ActivityRenderingTest {
    private val textOrIcon = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) or
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)

    @Test
    fun toolHeadersUseThemeColorsInsteadOfInheritedColor() {
        for (theme in listOf(DarkTheme(), LightTheme())) {
            for (state in ActivityState.entries) {
                runDesktopComposeUiTest(width = 390, height = 100) {
                    var foreground = Color.Unspecified
                    setContent {
                        GromozekaTheme(theme) {
                            foreground = MaterialTheme.colorScheme.onSurfaceVariant
                            // An unrelated ancestor must not determine the tool's foreground.
                            CompositionLocalProvider(LocalContentColor provides Color.Magenta) {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                    ActivityHeader(
                                        kind = ActivityKind.Tool("grz_read_file"),
                                        title = "Read file",
                                        state = state,
                                        canExpand = true,
                                        isExpanded = false,
                                        onToggleExpanded = {},
                                    )
                                    ExpansionColorReference()
                                }
                            }
                        }
                    }
                    waitForIdle()
                    onAllNodes(textOrIcon, useUnmergedTree = true).assertForeground(foreground, onNodeWithTag("expansion-color-reference").captureToImage().toPixelMap())
                }
            }
        }
    }

    @Test
    fun activityGroupsUseThemeColorsInEverySummaryStyle() {
        val message = activityTestMessage(
            "themed", Conversation.Message.ContentItem.Thinking("Readable thought"), activityTestCall("read"),
        )
        for (theme in listOf(DarkTheme(), LightTheme())) {
            for (style in ActivitySummaryStyle.entries) {
                runDesktopComposeUiTest(width = 390, height = 200) {
                    var foreground = Color.Unspecified
                    setContent {
                        GromozekaTheme(theme) {
                            foreground = MaterialTheme.colorScheme.onSurfaceVariant
                            CompositionLocalProvider(LocalContentColor provides Color.Magenta) {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                    ActivityTimelineFixture(listOf(message), summaryStyle = style)
                                    ExpansionColorReference()
                                }
                            }
                        }
                    }
                    val groupTag = UiTestTag.ActivityGroup("themed:0:content").value
                    onNodeWithTag(groupTag).assertIsDisplayed()
                    onAllNodes(
                        textOrIcon and hasAnyAncestor(hasTestTag(groupTag)),
                        useUnmergedTree = true,
                    ).assertForeground(foreground, onNodeWithTag("expansion-color-reference").captureToImage().toPixelMap())
                }
            }
        }
    }

    @Composable
    private fun BoxScope.ExpansionColorReference() {
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).size(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .testTag("expansion-color-reference"),
        )
    }

    private fun SemanticsNodeInteractionCollection.assertForeground(expected: Color, expansionReference: PixelMap) {
        val nodes = fetchSemanticsNodes()
        assertTrue(nodes.size >= 3, "Expected text, an activity icon or summary, and an expansion icon")
        for (index in nodes.indices) {
            val pixels = get(index).captureToImage().toPixelMap()
            // Allow rasterization rounding, but inspect each text/icon separately so a
            // correctly themed label cannot hide a black icon (or the other way around).
            val hasForeground = (0 until pixels.height).any { y ->
                (0 until pixels.width).any { x ->
                    val actual = pixels[x, y]
                    abs(actual.red - expected.red) <= 0.03f &&
                        abs(actual.green - expected.green) <= 0.03f &&
                        abs(actual.blue - expected.blue) <= 0.03f
                }
            }
            // A 16dp diagonal chevron can contain only antialiased pixels: none
            // need equal its tint. Compare it against an explicitly tinted icon
            // on the same background instead of weakening the color tolerance.
            val matchesExpansionReference = pixels.width == expansionReference.width &&
                pixels.height == expansionReference.height && (0 until pixels.height).all { y ->
                    (0 until pixels.width).all { x ->
                        val actual = pixels[x, y]
                        val reference = expansionReference[x, y]
                        abs(actual.red - reference.red) <= 0.02f &&
                            abs(actual.green - reference.green) <= 0.02f &&
                            abs(actual.blue - reference.blue) <= 0.02f &&
                            abs(actual.alpha - reference.alpha) <= 0.02f
                    }
                }
            assertTrue(hasForeground || matchesExpansionReference, "Expected theme foreground $expected in ${nodes[index].config}")
        }
    }

    @Test
    fun hiddenReasoningIsCompactAndHasNoExpansionAction() = runDesktopComposeUiTest {
        val message = activityTestMessage("hidden", Conversation.Message.ContentItem.Thinking("", signature = "opaque-signature"))
        setContent { ActivityTimelineFixture(listOf(message)) }
        val header = onNodeWithTag(UiTestTag.ActivityItem("hidden:0:content").value)
        header.assertIsDisplayed()
        val bounds = header.getBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top <= 40.dp)
        header.assertHasNoClickAction()
        onNodeWithText("Hidden thinking").assertIsDisplayed()
        onNodeWithText("opaque-signature").assertDoesNotExist()
    }

    @Test
    fun streamingReasoningBecomesExpandableWhenReadableTextArrives() = runDesktopComposeUiTest {
        val thinking = mutableStateOf(Conversation.Message.ContentItem.Thinking("", state = Conversation.Message.BlockState.STREAMING))
        var entries = emptyList<MessageListEntry>()
        setContent { ActivityTimelineFixture(listOf(activityTestMessage("stream", thinking.value)), onEntries = { entries = it }) }
        val header = onNodeWithTag(UiTestTag.ActivityItem("stream:0:content").value)
        header.assertHasNoClickAction()
        onNodeWithText("Thinking").assertIsDisplayed()
        onNodeWithText("Hidden thinking").assertDoesNotExist()
        runOnIdle { thinking.value = thinking.value.copy(thinking = "Readable thought") }
        header.performClick()
        onNodeWithText("Readable thought").assertIsDisplayed()
        runOnIdle { thinking.value = thinking.value.copy(thinking = "", state = Conversation.Message.BlockState.COMPLETE) }
        onNodeWithText("Hidden thinking").assertIsDisplayed()
        header.assertHasNoClickAction()
        runOnIdle { assertEquals(1, entries.size) }
    }

    @Test
    fun compactMixedGroupsExpandInEverySummaryStyle() {
        for (style in ActivitySummaryStyle.entries) {
            runDesktopComposeUiTest(width = 390, height = 844) {
                val message = activityTestMessage("mixed", Conversation.Message.ContentItem.Thinking("Readable thought"), activityTestCall("read"))
                setContent { ActivityTimelineFixture(listOf(message), summaryStyle = style) }
                val group = onNodeWithTag(UiTestTag.ActivityGroup("mixed:0:content").value)
                group.assertIsDisplayed()
                onNodeWithText("Readable thought").assertDoesNotExist()
                group.performClick()
                onNodeWithTag(UiTestTag.ActivityGroupContent("mixed:0:content").value).assertIsDisplayed()
                onNodeWithTag(UiTestTag.ActivityItem("mixed:0:content").value).performClick()
                onNodeWithText("Readable thought").assertIsDisplayed()
                onNodeWithTag(UiTestTag.ActivityItem("mixed:1:content").value).performClick()
                onNodeWithText("Tool output read").assertIsDisplayed()
                group.performClick()
                onNodeWithText("Readable thought").assertDoesNotExist()
                group.performClick()
                onNodeWithText("Readable thought").assertIsDisplayed()
                onNodeWithText("Tool output read").assertIsDisplayed()
            }
        }
    }

    @Test
    fun expandedLongReasoningUsesDistinctLazyEntries() = runDesktopComposeUiTest(width = 390, height = 500) {
        val text = (1..60).joinToString("\n\n") { "Paragraph $it of the reasoning." }
        val message = activityTestMessage("long", Conversation.Message.ContentItem.Thinking(text), activityTestCall("read"))
        var entries = emptyList<MessageListEntry>()
        setContent { ActivityTimelineFixture(listOf(message), onEntries = { entries = it }) }
        onNodeWithTag(UiTestTag.ActivityGroup("long:0:content").value).performClick()
        onNodeWithTag(UiTestTag.ActivityItem("long:0:content").value).performClick()
        waitUntil(timeoutMillis = 10_000) { entries.count { it.segment is MessageSegment.MarkdownBlock } == 60 }
        runOnIdle { assertEquals(entries.size, entries.map { it.key }.distinct().size) }
        onNodeWithText("Paragraph 1 of the reasoning.").assertIsDisplayed()
        onNodeWithText("Paragraph 60 of the reasoning.").assertDoesNotExist()
    }

    @Test
    fun reasoningExpansionSurvivesToolCompletionAndRegrouping() = runDesktopComposeUiTest {
        val message = activityTestMessage("evolving", Conversation.Message.ContentItem.Thinking("Keep this open"), activityTestCall("read"))
        val results = mutableStateOf(emptyMap<String, Conversation.Message.ContentItem.ToolResult>())
        var entries = emptyList<MessageListEntry>()
        setContent { ActivityTimelineFixture(listOf(message), results.value, onEntries = { entries = it }) }
        onNodeWithTag(UiTestTag.ActivityItem("evolving:0:content").value).performClick()
        onNodeWithText("Keep this open").assertIsDisplayed()
        runOnIdle { results.value = activityTestResults(listOf(message)) }
        onNodeWithTag(UiTestTag.ActivityGroup("evolving:0:content").value).performClick()
        onNodeWithText("Keep this open").assertIsDisplayed()
        runOnIdle { assertTrue(entries.map { it.key }.distinct().size == entries.size) }
    }
}
