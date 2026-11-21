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
import com.gromozeka.domain.service.AiRequestWaitReason
import com.gromozeka.presentation.ui.session.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationNavigationTabsTest {
    private fun activity(phase: ConversationActivity = ConversationActivity.READY, unread: Boolean = false,
        pressure: ConversationPressure = ConversationPressure.NORMAL) = ConversationActivityPresentation(
        ConversationIndicatorState(phase, pressure, unread, false,
            if (pressure == ConversationPressure.LOCAL_WAIT) setOf(AiRequestWaitReason.TPM) else emptySet()),
        phase.name, phase != ConversationActivity.READY, null,
    )
    private val alpha = ConversationTabHeader(Conversation.Id("alpha"), "Alpha", activity = activity())
    private val beta = ConversationTabHeader(Conversation.Id("beta"), "Beta", activity = activity(unread = true))

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
        runOnIdle { headers.value = listOf(alpha.copy(activity = activity(ConversationActivity.WORKING)), beta); hover.value = 0 }
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
        val headers = (0..4).map { ConversationTabHeader(Conversation.Id("busy-$it"), "Task $it", activity = activity(ConversationActivity.WORKING)) }
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
    fun unreadStaysCenteredAcrossPressureChangesAndCompletionWithoutMovingTheTitle() = runComposeUiTest {
        mainClock.autoAdvance = false
        val header = mutableStateOf(alpha)
        setContent { Navigation(listOf(header.value)) }
        val title = onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val slot = onNodeWithTag("conversation-activity-slot:alpha", useUnmergedTree = true)
        val bounds = slot.fetchSemanticsNode().boundsInRoot
        assertEquals(8f, title.left - bounds.right, "Status and title must keep an eight-dp gap")
        for (state in listOf(activity(ConversationActivity.WORKING, true),
            activity(ConversationActivity.WORKING, true, ConversationPressure.GLOBAL_WAIT),
            activity(ConversationActivity.WORKING, true, ConversationPressure.LOCAL_WAIT), activity(unread = true))) {
            runOnIdle { header.value = alpha.copy(activity = state) }
            mainClock.advanceTimeByFrame()
            val dot = onNodeWithTag(UiTestTag.ConversationUnread("alpha").value, useUnmergedTree = true)
                .assertWidthIsEqualTo(14.dp).assertHeightIsEqualTo(14.dp).fetchSemanticsNode().boundsInRoot
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
    fun readingAfterCleanCompletionHidesTheIndicatorWithoutMovingTheTitle() = runComposeUiTest {
        mainClock.autoAdvance = false
        val header = mutableStateOf(alpha.copy(activity = activity(unread = true)))
        setContent { Navigation(listOf(header.value)) }
        val slot = onNodeWithTag("conversation-activity-slot:alpha", useUnmergedTree = true)
        val frame = slot.fetchSemanticsNode().boundsInRoot
        val title = onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val unread = slot.captureToImage().toPixelMap()
        fun green(image: androidx.compose.ui.graphics.PixelMap) = (0 until image.height).sumOf { y ->
            (0 until image.width).count { x -> image[x,y].green > image[x,y].red + 0.1f && image[x,y].green > image[x,y].blue + 0.1f }
        }
        assertEquals(0, green(unread))
        runOnIdle { header.value = alpha.copy(activity = activity(ConversationActivity.WORKING, true)) }
        mainClock.advanceTimeBy(800)
        val working = slot.captureToImage().toPixelMap()
        for (y in unread.height / 2 - 1..unread.height / 2 + 1) for (x in unread.width / 2 - 1..unread.width / 2 + 1) {
            assertEquals(unread[x,y], working[x,y], "The unread dot must remain stationary")
        }
        runOnIdle { header.value = alpha }
        mainClock.advanceTimeByFrame()
        val ready = slot.captureToImage().toPixelMap()
        for (y in 0 until ready.height) for (x in 0 until ready.width) {
            assertEquals(ready[0, 0], ready[x, y], "Clean readiness must leave no circle or check at $x/$y")
        }
        assertEquals(frame, slot.fetchSemanticsNode().boundsInRoot)
        assertEquals(title, onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        onNodeWithTag(UiTestTag.ConversationUnread("alpha").value, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun narrowPickerShowsAggregateUnreadOnItsMenuWithoutChangingTheSelectedIndicator() = runComposeUiTest {
        setContent { Navigation(listOf(alpha.copy(activity = activity(pressure = ConversationPressure.GLOBAL_WAIT)), beta), width = 393) }
        val slot = onNodeWithTag("conversation-activity-slot:alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val attention = onNodeWithTag("conversation-picker-unread", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(8f, title.left - slot.right)
        assertTrue(attention.left > slot.right)
        onNodeWithTag(UiTestTag.ConversationUnread("alpha").value, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("conversation-tab-picker").performClick()
        val betaSlot = onNodeWithTag("conversation-activity-slot:beta", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val betaDot = onNodeWithTag(UiTestTag.ConversationUnread("beta").value, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(betaSlot.center, betaDot.center)
    }

    @Test
    fun fullConversationTitleAppearsOnHoverWithoutChangingTabGeometryOrActions() = runComposeUiTest {
        mainClock.autoAdvance = false
        val title = "Conversation with a long shared prefix and an important distinguishing ending ".repeat(3)
        val header = alpha.copy(title = title, activity = activity(unread = true, pressure = ConversationPressure.GLOBAL_WAIT))
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
