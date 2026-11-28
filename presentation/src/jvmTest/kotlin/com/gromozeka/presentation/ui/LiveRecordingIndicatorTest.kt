package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveRecordingIndicatorTest {
    @Test
    fun `microphone indicator remains visible outside Live without moving navigation`() = runComposeUiTest {
        val active = mutableStateOf(true)
        setContent {
            MaterialTheme {
                Box(Modifier.width(393.dp)) {
                    ConversationNavigationTabs(
                        selectedTabIndex = 2,
                        showTabsAtBottom = false,
                        isCompactLayout = true,
                        headers = emptyList(),
                        hoveredTabIndex = -1,
                        onTabSelect = {},
                        onTabHover = {},
                        onTabHoverExit = {},
                        onRename = {},
                        liveListening = active.value,
                    )
                }
            }
        }
        val liveTab = onNodeWithTag(UiTestTag.LiveTab.value)
        liveTab.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        val bounds = liveTab.fetchSemanticsNode().boundsInRoot
        onNodeWithTag("live-microphone-active", useUnmergedTree = true).assertExists()
        runOnIdle { active.value = false }
        onNodeWithTag("live-microphone-active", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(bounds, liveTab.fetchSemanticsNode().boundsInRoot)
    }
}
