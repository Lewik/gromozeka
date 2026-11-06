package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
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
        onNodeWithTag("anchor").performMouseInput { exit() }
        mainClock.advanceTimeBy(500)
        onNodeWithText("Tooltip details").assertDoesNotExist()
        onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(0)
        onNodeWithTag("anchor").assertExists()
    }
}
