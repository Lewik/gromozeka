package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationNavigationTabsTest {
    private val alpha = ConversationTabHeader(Conversation.Id("alpha"), "Alpha")
    private val beta = ConversationTabHeader(Conversation.Id("beta"), "Beta", isUnread = true)

    @Test
    fun utilityTabsStaySquareAndHaveNoVisibleLabels() = runComposeUiTest {
        setContent { Navigation(listOf(alpha, beta), selected = 2) }
        for (tag in listOf(UiTestTag.ProjectsTab, UiTestTag.AgentsTab, UiTestTag.SettingsTab, UiTestTag.LiveTab)) {
            onNodeWithTag(tag.value).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        }
        onAllNodesWithText("Settings").assertCountEquals(0)
        onNodeWithText("Alpha").assertExists()
        onNodeWithText("Beta").assertExists()
    }

    @Test
    fun selectableConversationTabKeepsItsFullAccessibleTitleAfterRename() = runComposeUiTest {
        val headers = mutableStateOf(listOf(alpha, beta))
        setContent { Navigation(headers.value, hovered = 0) }
        val tab = onNodeWithTag(UiTestTag.SessionTab(0).value)
        tab.assertIsSelected().assertTextContains("Alpha")
        val bounds = tab.fetchSemanticsNode().boundsInRoot
        runOnIdle { headers.value = listOf(alpha.copy(title = "Renamed conversation"), beta) }
        tab.assertIsSelected().assertTextContains("Renamed conversation")
        assertEquals(bounds, tab.fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun loadingAndHoverDoNotMoveTheConversationTitle() = runComposeUiTest {
        val headers = mutableStateOf(listOf(alpha, beta))
        val hover = mutableStateOf(-1)
        setContent { Navigation(headers.value, hovered = hover.value) }
        val before = onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        runOnIdle { headers.value = listOf(alpha.copy(loadingTone = LoadingIndicatorTone.FOREGROUND), beta); hover.value = 0 }
        val after = onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        onNodeWithTag(UiTestTag.SessionTabRename(0).value, useUnmergedTree = true).assertExists()
    }

    @Test
    fun narrowLayoutUsesNamedMenuAndPreservesSelectionAndRename() = runComposeUiTest {
        val selections = mutableListOf<Int?>()
        val renames = mutableListOf<ConversationTabHeader>()
        setContent { Navigation(listOf(alpha, beta), width = 393, onSelect = { selections += it }, onRename = { renames += it }) }
        onNodeWithTag("conversation-tab-picker").assertExists()
        onNodeWithText("Alpha").assertExists()
        onNodeWithTag(UiTestTag.SessionTab(1).value).assertDoesNotExist()
        onNodeWithTag("conversation-tab-picker").performClick()
        onNodeWithTag(UiTestTag.SessionTab(1).value).assertExists().performClick()
        runOnIdle { assertEquals(listOf<Int?>(1), selections) }
        onNodeWithTag(UiTestTag.SessionTab(1).value).assertDoesNotExist()
        onNodeWithTag("conversation-tab-picker").performClick()
        onNodeWithTag(UiTestTag.SessionTabRename(1).value, useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(listOf(beta), renames) }
    }

    @Test
    fun allFiveWorkingTabsAnimateEvenWhenAUtilityTabIsSelected() = runComposeUiTest {
        mainClock.autoAdvance = false
        val headers = (0..4).map { ConversationTabHeader(Conversation.Id("busy-$it"), "Task $it", loadingTone = LoadingIndicatorTone.FOREGROUND) }
        setContent { Navigation(headers, selected = 0) }
        mainClock.advanceTimeBy(100)
        val before = headers.map { onNodeWithTag("conversation-activity-slot:${it.conversationId.value}", useUnmergedTree = true).captureToImage().toPixelMap() }
        mainClock.advanceTimeBy(700)
        headers.forEachIndexed { index, header ->
            val after = onNodeWithTag("conversation-activity-slot:${header.conversationId.value}", useUnmergedTree = true).captureToImage().toPixelMap()
            val first = before[index]
            assertTrue((0 until first.height).any { y -> (0 until first.width).any { x -> first[x, y] != after[x, y] } }, header.title)
            assertTrue((0 until after.height).none { y -> (0 until after.width).any { x -> after[x, y].blue > after[x, y].red + 0.2f } }, "No blue bulb base in tab headers")
        }
    }

    @Test
    fun attentionStaysCenteredAcrossBothLoadingTonesAndCompletionWithoutMovingTheTitle() = runComposeUiTest {
        mainClock.autoAdvance = false
        val header = mutableStateOf(alpha)
        setContent { Navigation(listOf(header.value)) }
        val title = onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val slot = onNodeWithTag("conversation-activity-slot:alpha", useUnmergedTree = true)
        val bounds = slot.fetchSemanticsNode().boundsInRoot
        assertEquals(8f, title.left - bounds.right, "Status and title must keep an eight-dp gap")
        for (tone in listOf(LoadingIndicatorTone.FOREGROUND, LoadingIndicatorTone.BACKGROUND, null)) {
            runOnIdle { header.value = alpha.copy(isUnread = true, loadingTone = tone) }
            mainClock.advanceTimeByFrame()
            val dot = onNodeWithTag(UiTestTag.ConversationUnread("alpha").value, useUnmergedTree = true)
                .assertWidthIsEqualTo(6.dp).assertHeightIsEqualTo(6.dp).fetchSemanticsNode().boundsInRoot
            assertEquals(bounds.center, dot.center)
            assertEquals(title, onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        }
        mainClock.advanceTimeBy(240)
        onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
        val finished = slot.captureToImage().toPixelMap()
        mainClock.advanceTimeBy(1_000)
        val later = slot.captureToImage().toPixelMap()
        for (y in 0 until finished.height) for (x in 0 until finished.width) {
            assertEquals(finished[x, y], later[x, y], "Unread alone must not animate")
        }
        runOnIdle { header.value = alpha }
        mainClock.advanceTimeByFrame()
        onNodeWithTag(UiTestTag.ConversationUnread("alpha").value, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(bounds, slot.fetchSemanticsNode().boundsInRoot)
        assertEquals(title, onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun ringGrowsAndShrinksUnderTheStationaryAttentionDot() = runComposeUiTest {
        mainClock.autoAdvance = false
        val header = mutableStateOf(alpha.copy(isUnread = true))
        setContent { Navigation(listOf(header.value)) }
        val slot = onNodeWithTag("conversation-activity-slot:alpha", useUnmergedTree = true)
        val dot = onNodeWithTag(UiTestTag.ConversationUnread("alpha").value, useUnmergedTree = true)
        val dotBounds = dot.fetchSemanticsNode().boundsInRoot
        val titleBounds = onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val idle = slot.captureToImage().toPixelMap()
        fun assertStationaryDot() {
            val frame = slot.captureToImage().toPixelMap()
            for (dy in -1..1) for (dx in -1..1) {
                val x = frame.width / 2 + dx
                val y = frame.height / 2 + dy
                assertEquals(idle[x, y], frame[x, y], "The ring must remain underneath the attention dot")
            }
            assertEquals(dotBounds, dot.fetchSemanticsNode().boundsInRoot)
            assertEquals(titleBounds, onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        }
        runOnIdle { header.value = header.value.copy(loadingTone = LoadingIndicatorTone.FOREGROUND) }
        for (step in listOf(16L, 32L, 64L, 240L)) { mainClock.advanceTimeBy(step); assertStationaryDot() }
        val visible = slot.captureToImage().toPixelMap()
        assertTrue((0 until visible.height).any { y -> (0 until visible.width).any { x -> visible[x, y] != idle[x, y] } })
        runOnIdle { header.value = header.value.copy(loadingTone = null) }
        for (step in listOf(16L, 32L, 64L, 240L)) { mainClock.advanceTimeBy(step); assertStationaryDot() }
        val hidden = slot.captureToImage().toPixelMap()
        for (y in 0 until hidden.height) for (x in 0 until hidden.width) assertEquals(idle[x, y], hidden[x, y])
    }

    @Test
    fun narrowPickerKeepsAggregateAttentionInsideSelectedActivitySlot() = runComposeUiTest {
        setContent { Navigation(listOf(alpha.copy(loadingTone = LoadingIndicatorTone.BACKGROUND), beta), width = 393) }
        val slot = onNodeWithTag("conversation-activity-slot:alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val attention = onNodeWithTag("conversation-picker-unread", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(8f, title.left - slot.right)
        assertEquals(slot.center, attention.center)
        onNodeWithTag("conversation-tab-picker").performClick()
        val betaSlot = onNodeWithTag("conversation-activity-slot:beta", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val betaDot = onNodeWithTag(UiTestTag.ConversationUnread("beta").value, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(betaSlot.center, betaDot.center)
    }

    @Test
    fun fullConversationTitleAppearsOnHoverWithoutChangingTabGeometryOrActions() = runComposeUiTest {
        mainClock.autoAdvance = false
        val title = "Conversation with a long shared prefix and an important distinguishing ending ".repeat(3)
        val header = alpha.copy(title = title, isUnread = true, loadingTone = LoadingIndicatorTone.BACKGROUND)
        val selected = mutableListOf<Int?>()
        val renamed = mutableListOf<ConversationTabHeader>()
        setContent { Navigation(listOf(header, beta), hovered = 0, width = 650,
            onSelect = selected::add, onRename = renamed::add) }
        val tab = onNodeWithTag(UiTestTag.SessionTab(0).value)
        val bounds = tab.fetchSemanticsNode().boundsInRoot
        val popupTitle = hasText(title) and hasAnyAncestor(isPopup())
        onNode(popupTitle, useUnmergedTree = true).assertDoesNotExist()
        tab.performMouseInput { enter(center) }
        mainClock.advanceTimeBy(1_000)
        onNode(popupTitle, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(bounds, tab.fetchSemanticsNode().boundsInRoot)
        tab.performMouseInput { click(center) }
        runOnIdle { assertEquals(listOf<Int?>(0), selected) }
        onNodeWithTag(UiTestTag.SessionTabRename(0).value, useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(listOf(header), renamed) }
        tab.performMouseInput { exit() }
        mainClock.advanceTimeBy(500)
        onNode(popupTitle, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun compactPickerAndMenuExposeFullConversationTitles() = runComposeUiTest {
        mainClock.autoAdvance = false
        val firstTitle = "A long current conversation title ".repeat(6)
        val otherTitle = "Another long conversation title ".repeat(6)
        val renamed = mutableListOf<ConversationTabHeader>()
        val other = beta.copy(title = otherTitle)
        setContent { Navigation(listOf(alpha.copy(title = firstTitle), other), width = 393, onRename = renamed::add) }
        val picker = onNodeWithTag("conversation-tab-picker")
        picker.assertTextContains(firstTitle)
        picker.performMouseInput { enter(center) }
        mainClock.advanceTimeBy(1_000)
        onNode(hasText(firstTitle) and hasAnyAncestor(isPopup()), useUnmergedTree = true).assertIsDisplayed()
        picker.performMouseInput { exit() }
        mainClock.advanceTimeBy(500)
        picker.performClick()
        mainClock.advanceTimeBy(300)
        onAllNodesWithText(firstTitle, useUnmergedTree = true).assertCountEquals(2)
        onNodeWithTag(UiTestTag.SessionTab(1).value).assertIsDisplayed().assertTextContains(otherTitle)
        onNodeWithTag(UiTestTag.SessionTab(1).value).performMouseInput { enter(center) }
        mainClock.advanceTimeBy(1_000)
        onAllNodesWithText(otherTitle, useUnmergedTree = true).assertCountEquals(2)
        onNodeWithTag(UiTestTag.SessionTabRename(1).value, useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(listOf(other), renamed) }
    }

    @Test
    fun startupLogoIsLargeAndCentered() = runComposeUiTest {
        setContent { MaterialTheme { Box(Modifier.size(400.dp, 300.dp)) { ClientStartupLoadingScreen() } } }
        onNodeWithTag("client-startup-logo").assertWidthIsEqualTo(80.dp).assertHeightIsEqualTo(80.dp)
        val container = onNodeWithTag("client-startup-loading").fetchSemanticsNode().boundsInRoot
        val logo = onNodeWithTag("client-startup-logo").fetchSemanticsNode().boundsInRoot
        assertEquals(container.center, logo.center)
    }

    @Composable
    private fun Navigation(
        headers: List<ConversationTabHeader>,
        selected: Int = 4,
        hovered: Int = -1,
        width: Int = 900,
        onSelect: (Int?) -> Unit = {},
        onRename: (ConversationTabHeader) -> Unit = {},
    ) {
        MaterialTheme {
            ConversationNavigationTabs(
                selectedTabIndex = selected, showTabsAtBottom = false, isCompactLayout = false,
                headers = headers, hoveredTabIndex = hovered, onTabSelect = onSelect,
                onTabHover = {}, onTabHoverExit = {}, onRename = onRename,
                modifier = Modifier.width(width.dp),
            )
        }
    }
}
