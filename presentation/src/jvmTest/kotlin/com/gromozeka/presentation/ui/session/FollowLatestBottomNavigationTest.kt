package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.UiTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FollowLatestBottomNavigationTest {
    @Test
    fun arrowReachesBottomWhenLastItemIsTallerThanViewport() {
        verifyArrowReachesBottom(lastItemHeight = 1200.dp, focusItem = 0)
    }

    @Test
    fun arrowReachesBottomWhenLastItemIsAlreadyPartiallyVisible() {
        verifyArrowReachesBottom(lastItemHeight = 120.dp, focusItem = 1)
    }

    @Test
    fun arrowDoesNotJumpBackwardsWhenTallLastItemIsPartiallyVisible() {
        verifyArrowReachesBottom(lastItemHeight = 1200.dp, focusItem = 3)
    }

    @Test
    fun arrowIncludesBottomContentPadding() {
        verifyArrowReachesBottom(lastItemHeight = 1200.dp, focusItem = 3, topPadding = 20.dp, bottomPadding = 36.dp)
    }

    @Test
    fun followsResizedTailAndViewportWithoutNewItems() = runDesktopComposeUiTest(width = 390, height = 500) {
        val lastItemHeight = mutableStateOf(120.dp)
        val viewportHeight = mutableStateOf(500.dp)
        setContent { TestHistory(lastItemHeight = lastItemHeight.value, viewportHeight = viewportHeight.value) }
        waitForIdle()
        assertAtLatest()

        runOnIdle { lastItemHeight.value = 1200.dp }
        waitForIdle()
        assertAtLatest()

        runOnIdle { viewportHeight.value = 350.dp }
        waitForIdle()
        assertAtLatest()
    }

    @Test
    fun tailGrowthDoesNotMoveSomeoneReadingOlderItems() = runDesktopComposeUiTest(width = 390, height = 500) {
        val lastItemHeight = mutableStateOf(1200.dp)
        val focus = mutableStateOf<Int?>(null)
        setContent {
            TestHistory(
                lastItemHeight = lastItemHeight.value,
                focusKey = focus.value,
                onFocusConsumed = { focus.value = null },
            )
        }
        waitForIdle()
        assertAtLatest()
        runOnIdle { focus.value = 0 }
        waitForIdle()
        val before = onNodeWithTag("message-0").getUnclippedBoundsInRoot()

        runOnIdle { lastItemHeight.value = 1800.dp }
        waitForIdle()
        onNodeWithTag("message-0").assertIsDisplayed()
        assertEquals(before, onNodeWithTag("message-0").getUnclippedBoundsInRoot())
        onNodeWithTag(UiTestTag.UnreadMessagesButton.value).assertIsDisplayed()
    }

    private fun verifyArrowReachesBottom(
        lastItemHeight: Dp,
        focusItem: Int,
        topPadding: Dp = 0.dp,
        bottomPadding: Dp = 0.dp,
    ) = runDesktopComposeUiTest(width = 390, height = 500) {
        val focus = mutableStateOf<Int?>(null)
        var firstVisible: Int? = null
        setContent {
            TestHistory(
                lastItemHeight = lastItemHeight,
                focusKey = focus.value,
                onFocusConsumed = { focus.value = null },
                onVisibleItemChanged = { firstVisible = it },
                contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
            )
        }
        waitForIdle()
        runOnIdle { focus.value = focusItem }
        waitForIdle()
        onNodeWithTag("message-$focusItem").assertIsDisplayed()
        if (focusItem > 0) {
            onNodeWithTag("last-item").assertIsDisplayed()
            onNodeWithTag("last-item-end").assertIsNotDisplayed()
        }
        val firstBeforeClick = requireNotNull(firstVisible)

        onNodeWithTag(UiTestTag.UnreadMessagesButton.value).performClick()
        waitForIdle()
        assertTrue(requireNotNull(firstVisible) >= firstBeforeClick, "Arrow moved backward: $firstBeforeClick -> $firstVisible")
        assertAtLatest(bottomPadding)
    }

    private fun ComposeUiTest.assertAtLatest(bottomPadding: Dp = 0.dp) {
        onNodeWithTag("last-item-end").assertIsDisplayed()
        val viewportBottom = onNodeWithTag(UiTestTag.MessageList.value).getUnclippedBoundsInRoot().bottom
        val lastItemBottom = onNodeWithTag("last-item").getUnclippedBoundsInRoot().bottom
        assertEquals(viewportBottom.value - bottomPadding.value, lastItemBottom.value, absoluteTolerance = 1f)
        onNodeWithTag(UiTestTag.UnreadMessagesButton.value).assertDoesNotExist()
    }

    @Composable
    private fun TestHistory(
        lastItemHeight: Dp,
        viewportHeight: Dp = 500.dp,
        focusKey: Int? = null,
        onFocusConsumed: () -> Unit = {},
        onVisibleItemChanged: (Int?) -> Unit = {},
        contentPadding: PaddingValues = PaddingValues(0.dp),
    ) {
        val values = remember { (0..5).toList() }
        MaterialTheme {
            FollowLatestLazyColumn(
                items = values,
                itemKey = { it },
                contentRevision = values,
                unreadLabel = { "$it new messages" },
                focusKey = focusKey,
                onFocusConsumed = onFocusConsumed,
                onVisibleItemChanged = onVisibleItemChanged,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxWidth().height(viewportHeight),
            ) { value, _ ->
                if (value == values.last()) {
                    Box(Modifier.fillMaxWidth().height(lastItemHeight).testTag("last-item")) {
                        Text("Start of last message", Modifier.align(Alignment.TopStart))
                        Text("End of last message", Modifier.align(Alignment.BottomStart).testTag("last-item-end"))
                    }
                } else {
                    Text("Message $value", Modifier.fillMaxWidth().height(120.dp).testTag("message-$value"))
                }
            }
        }
    }
}
