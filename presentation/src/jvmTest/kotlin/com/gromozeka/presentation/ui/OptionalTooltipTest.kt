package com.gromozeka.presentation.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class OptionalTooltipTest {
    @Test
    fun nullTooltipDoesNotCreatePopupOnHover() = verifyMissingTooltip(null)

    @Test
    fun emptyTooltipDoesNotCreatePopupOnHover() = verifyMissingTooltip("")

    @Test
    fun blankTooltipDoesNotCreatePopupOnHover() = verifyMissingTooltip(" \t ")

    private fun verifyMissingTooltip(tooltip: String?) = runComposeUiTest {
        var clicks = 0
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OptionalTooltip(tooltip) {
                        Button(onClick = { clicks++ }, modifier = Modifier.testTag("anchor")) {
                            Text("Anchor")
                        }
                    }
                }
            }
        }
        onNodeWithTag("anchor").performMouseInput { enter(center) }
        mainClock.advanceTimeBy(1_000)
        onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(0)
        onNodeWithTag("anchor").assertExists().performClick()
        runOnIdle { assertEquals(1, clicks) }
        onNodeWithTag("anchor").performMouseInput { exit() }
        mainClock.advanceTimeBy(500)
        onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun popupTextIsNotRegisteredInItsAnchorsSelectionContainer() = runComposeUiTest {
        val selection = SelectionState()
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                SelectionContainer(selection) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        OptionalTooltip("Foreign popup text") { Text("Selectable anchor", Modifier.testTag("anchor")) }
                    }
                }
            }
        }
        onNodeWithTag("anchor", useUnmergedTree = true).performMouseInput { enter(center) }
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("Foreign popup text", useUnmergedTree = true).assertExists()
        runOnIdle {
            assertEquals(listOf("Selectable anchor"), selection.getSelectableTexts().map { it.text })
            selection.selectAll()
            assertEquals(listOf("Selectable anchor"), selection.selectedTexts.map { it.text })
        }
        onNodeWithTag("anchor", useUnmergedTree = true).performMouseInput { exit() }
        mainClock.advanceTimeBy(500)
    }

    @Test
    fun actualTooltipStillAppearsAndDismisses() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OptionalTooltip("Tooltip details") {
                        Button(onClick = {}, modifier = Modifier.testTag("anchor")) {
                            Text("Anchor")
                        }
                    }
                }
            }
        }
        onNodeWithTag("anchor").performMouseInput { enter(center) }
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("Tooltip details").assertExists()
        onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(1)
        mainClock.advanceTimeBy(5_000)
        onNodeWithText("Tooltip details").assertExists()
        onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(1)
        onNodeWithTag("anchor").performMouseInput { exit() }
        mainClock.advanceTimeBy(500)
        onNodeWithText("Tooltip details").assertDoesNotExist()
        onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(0)
        onNodeWithTag("anchor").assertExists()
    }
    @Test
    fun touchTapAndLongPressNeverShowTooltipOrStealTheButtonClick() = runComposeUiTest {
        var clicks = 0
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OptionalTooltip("Mouse-only details") {
                        Button(onClick = { clicks++ }, modifier = Modifier.testTag("anchor")) { Text("Anchor") }
                    }
                }
            }
        }
        val anchor = onNodeWithTag("anchor")
        anchor.performTouchInput { down(center) }
        mainClock.advanceTimeBy(3_000)
        onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(0)
        anchor.performTouchInput { up() }
        mainClock.advanceTimeBy(300)
        runOnIdle { assertEquals(1, clicks) }
        anchor.performTouchInput { click(center) }
        mainClock.advanceTimeBy(300)
        runOnIdle { assertEquals(2, clicks) }
        onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(0)

        anchor.performMouseInput { enter(center) }
        mainClock.advanceTimeBy(500)
        onNodeWithText("Mouse-only details").assertExists()
        anchor.performTouchInput { down(center) }
        mainClock.advanceTimeBy(3_000)
        onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(0)
        anchor.performTouchInput { up() }
        anchor.performMouseInput { enter(center) }
        mainClock.advanceTimeBy(500)
        onNodeWithText("Mouse-only details").assertExists()
        anchor.performMouseInput { exit() }
        mainClock.advanceTimeBy(300)
        onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun tooltipDoesNotInterceptTheAnchorsOwnTouchLongPress() = runComposeUiTest {
        var longClicks = 0
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OptionalTooltip("No touch popup") {
                        Box(Modifier.size(80.dp).testTag("anchor")
                            .combinedClickable(onClick = {}, onLongClick = { longClicks++ })) { Text("Anchor") }
                    }
                }
            }
        }
        onNodeWithTag("anchor").performTouchInput { down(center) }
        mainClock.advanceTimeBy(3_000)
        runOnIdle { assertEquals(1, longClicks) }
        onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(0)
        onNodeWithTag("anchor").performTouchInput { up() }
        mainClock.advanceTimeBy(300)
    }

    @Test
    fun clearingTooltipWhileHoveredRemovesPopupAndPreservesAnchor() = runComposeUiTest {
        var tooltip by mutableStateOf("Details before update")
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OptionalTooltip(tooltip) {
                        Button(onClick = {}, modifier = Modifier.testTag("anchor")) { Text("Anchor") }
                    }
                }
            }
        }
        onNodeWithTag("anchor").performMouseInput { enter(center) }
        mainClock.advanceTimeBy(500)
        onNodeWithText("Details before update").assertExists()
        runOnIdle { tooltip = "" }
        mainClock.advanceTimeBy(500)
        onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(0)
        onNodeWithTag("anchor").assertHasClickAction()
    }

}
