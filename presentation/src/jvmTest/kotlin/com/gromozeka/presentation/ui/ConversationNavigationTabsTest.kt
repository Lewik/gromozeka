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
    fun loadingAndHoverDoNotMoveTheConversationTitle() = runComposeUiTest {
        val headers = mutableStateOf(listOf(alpha, beta))
        val hover = mutableStateOf(-1)
        setContent { Navigation(headers.value, hovered = hover.value) }
        val before = onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        runOnIdle { headers.value = listOf(alpha.copy(isLoading = true), beta); hover.value = 0 }
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
        val headers = (0..4).map { ConversationTabHeader(Conversation.Id("busy-$it"), "Task $it", isLoading = true) }
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
