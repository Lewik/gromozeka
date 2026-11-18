package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.UiTestTag
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerSubmissionUiTest {
    @Test
    fun `click and Shift Enter clear the draft while submission progress permits the next message`() = runComposeUiTest {
        var draft by mutableStateOf("")
        var submitting by mutableStateOf(false)
        val sent = mutableListOf<String>()
        setContent {
            GromozekaTheme {
                Box(Modifier.size(850.dp, 250.dp)) {
                    TestMessageInput(
                        draft, { draft = it }, submitting = submitting,
                        onSend = { sent += draft; draft = ""; submitting = true },
                    )
                }
            }
        }
        onNodeWithTag(UiTestTag.SendButton.value).assertIsNotEnabled()
        onNodeWithTag(UiTestTag.MessageInput.value).performClick().performTextInput("First message")
        onNodeWithTag(UiTestTag.SendButton.value).assertIsEnabled().performClick()
        onNodeWithTag(UiTestTag.MessageInput.value).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        onNodeWithTag("composer-submitting", useUnmergedTree = true).assertExists()
        onNodeWithTag(UiTestTag.SendButton.value).assertIsNotEnabled()

        onNodeWithTag(UiTestTag.MessageInput.value).performClick().performTextInput("Second message")
        onNodeWithTag(UiTestTag.SendButton.value).assertIsEnabled()
        onNodeWithTag(UiTestTag.MessageInput.value).performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Enter)
            keyUp(Key.ShiftLeft)
        }
        runOnIdle { assertEquals(listOf("First message", "Second message"), sent) }
        onNodeWithTag(UiTestTag.MessageInput.value).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        runOnIdle { submitting = false }
        onNodeWithTag("composer-submitting", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `upload in progress disables both click and keyboard submission`() = runComposeUiTest {
        var sends = 0
        setContent {
            GromozekaTheme {
                Box(Modifier.size(850.dp, 250.dp)) {
                    TestMessageInput("Draft", {}, uploading = true, onSend = { sends++ })
                }
            }
        }
        onNodeWithTag(UiTestTag.SendButton.value).assertIsNotEnabled().performClick()
        onNodeWithTag(UiTestTag.MessageInput.value).performClick().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Enter)
            keyUp(Key.ShiftLeft)
        }
        runOnIdle { assertEquals(0, sends) }
    }
}
