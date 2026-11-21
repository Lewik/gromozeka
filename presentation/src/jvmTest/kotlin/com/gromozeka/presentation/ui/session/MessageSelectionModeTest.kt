package com.gromozeka.presentation.ui.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.UiTestTag
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MessageSelectionModeTest {
    @Test fun storedSelectionIsHiddenAndInertOutsideTheThreadPanelThenRestored() =
        runDesktopComposeUiTest(width = 500, height = 180) {
            val enabled = mutableStateOf(false)
            var toggles = 0
            val content = Conversation.Message.ContentItem.AssistantMessage(
                Conversation.Message.StructuredText("Selected message"),
            )
            val message = activityTestMessage("selection-mode-message", content)
            setContent {
                val entry = rememberConversationTimeline(listOf(message), emptyMap(), emptyMap())
                    .filterIsInstance<ConversationTimelineItem.Message>().single().entry
                MaterialTheme(colorScheme = lightColorScheme(primary = Color.Magenta)) {
                    MessageItem(
                        entry = entry,
                        messageSelectionEnabled = enabled.value,
                        isSelected = true,
                        onToggleSelection = { _, _ -> toggles++ },
                        loadArtifactContent = { byteArrayOf() },
                    )
                }
            }
            val node = onNodeWithTag(UiTestTag.MessageItem(message.id.value).value)
            fun selectionPixels(): Int {
                val pixels = node.captureToImage().toPixelMap()
                return (0 until pixels.height).sumOf { y ->
                    (0 until pixels.width).count { x ->
                        val color = pixels[x, y]
                        abs(color.red - 1f) < 0.03f && color.green < 0.03f && abs(color.blue - 1f) < 0.03f
                    }
                }
            }

            val hidden = selectionPixels()
            node.performMouseInput { click(center) }
            runOnIdle {
                assertEquals(0, toggles)
                enabled.value = true
            }
            waitForIdle()
            val visible = selectionPixels()
            assertTrue(visible > hidden, "Stored selection must become visible in Thread mode")
            node.performMouseInput { click(center) }
            runOnIdle {
                assertEquals(1, toggles)
                enabled.value = false
            }
            waitForIdle()
            assertEquals(hidden, selectionPixels(), "Leaving Thread mode must hide but not consume stored selection")
            runOnIdle { enabled.value = true }
            waitForIdle()
            assertTrue(selectionPixels() > hidden, "Returning to Thread mode must restore the stored selection")
        }
}
