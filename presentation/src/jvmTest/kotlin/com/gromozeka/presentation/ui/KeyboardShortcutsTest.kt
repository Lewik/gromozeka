package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.gromozeka.domain.model.*
import com.gromozeka.presentation.services.HoldToTalkShortcutController
import com.gromozeka.presentation.services.NoOpGlobalHotkeyController
import com.gromozeka.presentation.services.NoOpPttEventHandler
import com.gromozeka.presentation.services.NoOpLiveVoiceInputService
import com.gromozeka.presentation.services.LiveVoiceInputState
import com.gromozeka.presentation.services.PttState
import com.gromozeka.presentation.ui.session.MessageInput
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyboardShortcutsTest {
    @Test
    fun composerSubmitsCurrentDraftAfterTypingAndCanSendAgain() = runComposeUiTest {
        val input = mutableStateOf("")
        val submitted = mutableListOf<String>()
        setContent {
            MaterialTheme {
                MessageInput(
                    userInput = input.value, onUserInputChange = { input.value = it },
                    isWaitingForResponse = false, pendingMessagesCount = 0,
                    agentMentionCandidates = emptyList(), messageSubmissionError = null,
                    suggestedReplies = null, suggestedRepliesRegenerating = false,
                    onRegenerateSuggestedReplies = {},
                    onSendMessage = { submitted.add(input.value); input.value = "" },
                    coroutineScope = rememberCoroutineScope(), pttEventHandler = NoOpPttEventHandler, pttState = PttState.IDLE,
                    pttStatusMessage = null, pttUnavailableReason = null,
                    liveVoiceInputService = NoOpLiveVoiceInputService(), liveVoiceInputState = LiveVoiceInputState.IDLE,
                    liveVoiceInputStatusMessage = null, liveVoiceInputUnavailableReason = null,
                    showLiveVoiceButton = false, showPttButton = false, compactVoiceMode = false,
                    clientPlatform = ClientPlatform.DESKTOP, instructionGroups = emptyList(),
                    activeInstructionIds = emptySet(), onSelectInstruction = { _, _ -> },
                    composerArtifacts = emptyList(), artifactUploadInProgress = false, artifactError = null,
                    canPickAttachments = false, canCaptureScreenshot = false, onPickAttachments = {},
                    onCaptureScreenshot = {}, onRemoveArtifact = {},
                )
            }
        }
        for (text in listOf("first draft", "second draft")) {
            onNodeWithTag(UiTestTag.MessageInput.value).performClick().performTextInput(text)
            onNodeWithTag(UiTestTag.MessageInput.value).performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.Enter)
                keyUp(Key.ShiftLeft)
            }
            runOnIdle { assertEquals("", input.value) }
        }
        runOnIdle { assertEquals(listOf("first draft", "second draft"), submitted) }
    }

    @Test
    fun bothEnterModesUseNativeNewlineWithSelectionAndUndo() = runComposeUiTest {
        val value = mutableStateOf(TextFieldValue("abcd", selection = TextRange(1, 3)))
        val action = mutableStateOf(EnterKeyAction.NEW_LINE)
        var sends = 0
        setContent {
            MaterialTheme {
                key(action.value) {
                    OutlinedTextField(
                        value = value.value,
                        onValueChange = { value.value = it },
                        modifier = Modifier.testTag("composer").messageInputShortcuts(
                            enterKeyAction = action.value,
                            isComposing = { value.value.composition != null },
                            isEmpty = { value.value.text.isEmpty() },
                            editLastMessageShortcut = null,
                            onEditLastUserMessage = { false },
                            onSubmit = { sends++ },
                        ),
                    )
                }
            }
        }
        for (mode in EnterKeyAction.entries) {
            runOnIdle {
                action.value = mode
                value.value = TextFieldValue("abcd", selection = TextRange(1, 3))
            }
            onNodeWithTag("composer").performClick()
            runOnIdle { value.value = value.value.copy(selection = TextRange(1, 3)) }
            onNodeWithTag("composer").performKeyInput {
                if (mode == EnterKeyAction.SEND_MESSAGE) keyDown(Key.ShiftLeft)
                pressKey(Key.Enter)
                if (mode == EnterKeyAction.SEND_MESSAGE) keyUp(Key.ShiftLeft)
            }
            runOnIdle {
                assertEquals("a\nd", value.value.text)
                assertEquals(TextRange(2), value.value.selection)
            }
            onNodeWithTag("composer").performKeyInput {
                val modifier = if (System.getProperty("os.name").contains("mac", true)) Key.MetaLeft else Key.CtrlLeft
                keyDown(modifier)
                pressKey(Key.Z)
                keyUp(modifier)
            }
            runOnIdle { assertEquals("abcd", value.value.text) }
            val sendsBeforeComposition = sends
            runOnIdle { value.value = TextFieldValue("input", composition = TextRange(0, 5)) }
            onNodeWithTag("composer").performKeyInput {
                if (mode == EnterKeyAction.NEW_LINE) keyDown(Key.ShiftLeft)
                pressKey(Key.Enter)
                if (mode == EnterKeyAction.NEW_LINE) keyUp(Key.ShiftLeft)
            }
            runOnIdle {
                assertEquals(sendsBeforeComposition, sends)
                value.value = TextFieldValue("ready")
            }
            onNodeWithTag("composer").performKeyInput {
                if (mode == EnterKeyAction.NEW_LINE) keyDown(Key.ShiftLeft)
                keyDown(Key.NumPadEnter)
                advanceEventTime(1_200)
                keyUp(Key.NumPadEnter)
                if (mode == EnterKeyAction.NEW_LINE) keyUp(Key.ShiftLeft)
            }
        }
        runOnIdle { assertEquals(2, sends) }
    }

    @Test
    fun extraModifiersDoNotSendAndModifierReleaseDoesNotStick() = runComposeUiTest {
        val value = mutableStateOf(TextFieldValue("draft"))
        var sends = 0
        setContent {
            MaterialTheme {
                OutlinedTextField(value.value, { value.value = it }, modifier = Modifier.testTag("composer")
                    .messageInputShortcuts(EnterKeyAction.NEW_LINE, { false }, { false }, null, { false }, { sends++ }))
            }
        }
        onNodeWithTag("composer").performClick().performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.ShiftLeft)
            pressKey(Key.Enter)
            keyUp(Key.CtrlLeft)
            keyDown(Key.Enter)
            keyUp(Key.ShiftLeft)
            keyUp(Key.Enter)
            keyDown(Key.ShiftLeft)
            pressKey(Key.Enter)
            keyUp(Key.ShiftLeft)
        }
        runOnIdle { assertEquals(2, sends) }
    }

    @Test
    fun browserDispatchesGlobalBindingBeforeTextFieldAndCanPauseCapture() = runComposeUiTest {
        val settings = KeyboardShortcutSettings(bindings = KeyboardShortcutSettings.defaultBindings().map {
            if (it.action == KeyboardShortcutAction.FIX_CLIPBOARD_TEXT) {
                it.copy(scope = KeyboardShortcutScope.GLOBAL, key = KeyboardShortcutKey.A, modifiers = setOf(KeyboardShortcutModifier.CONTROL))
            } else it
        })
        val enabled = mutableStateOf(true)
        val value = mutableStateOf("")
        var activations = 0
        setContent {
            val scope = rememberCoroutineScope()
            MaterialTheme {
                Box(Modifier.focusedKeyboardShortcuts(settings, true, enabled.value,
                    HoldToTalkShortcutController(NoOpPttEventHandler, scope), { activations++ })) {
                    OutlinedTextField(value.value, { value.value = it }, modifier = Modifier.testTag("input"))
                }
            }
        }
        onNodeWithTag("input").performClick().performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.A)
            keyUp(Key.CtrlLeft)
            keyUp(Key.A)
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            keyUp(Key.CtrlLeft)
        }
        runOnIdle { enabled.value = false }
        onNodeWithTag("input").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            keyUp(Key.CtrlLeft)
        }
        runOnIdle {
            assertEquals(2, activations)
            assertEquals(KeyboardShortcutScope.GLOBAL, settings.binding(KeyboardShortcutAction.FIX_CLIPBOARD_TEXT).scope)
        }
    }

    @Test
    fun editorDoesNotSaveReservedKeysAndRecoversAfterValidRecording() = runComposeUiTest {
        val settings = mutableStateOf(Settings(userDeviceSettings = UserDeviceSettings.Web()))
        var saves = 0
        var recording = false
        setContent {
            MaterialTheme {
                KeyboardShortcutSettingsGroup({ recording = it }, settings.value, ClientPlatform.WEB_DESKTOP, NoOpGlobalHotkeyController) {
                    settings.value = it
                    saves++
                }
            }
        }
        val capture = UiTestTag.KeyboardShortcutCapture(KeyboardShortcutAction.PUSH_TO_TALK.name).value
        onNodeWithTag(capture).performClick().performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(0, saves) }
        onNodeWithText("Enter and Shift+Enter are reserved for sending messages and inserting new lines").assertExists()
        onNodeWithTag(capture).performClick().performKeyInput { pressKey(Key.F8) }
        runOnIdle {
            assertEquals(false, recording)
            assertEquals(1, saves)
            assertEquals(KeyboardShortcutKey.F8, settings.value.userProfile.keyboardShortcuts.binding(KeyboardShortcutAction.PUSH_TO_TALK).key)
        }
    }
}
