package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.*
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.UiDensity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.domain.model.MessageDelivery
import com.gromozeka.presentation.services.*
import com.gromozeka.presentation.ui.AgentMentionCandidate
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.UiTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposerPanelTest {
    @Test
    fun suggestionsAreOutsidePanelAndSelectionOnlyInsertsDraft() = runComposeUiTest {
        var draft by mutableStateOf("")
        var sends = 0
        setContent {
            GromozekaTheme {
                Box(Modifier.size(390.dp, 600.dp).testTag("host"), contentAlignment = Alignment.BottomCenter) {
                    TestMessageInput(draft, { draft = it }, options = options(listOf("Continue")), onSend = { sends++ })
                }
            }
        }
        val host = onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
        val panel = onNodeWithTag("conversation-composer-panel").fetchSemanticsNode().boundsInRoot
        val suggestions = onNodeWithTag(UiTestTag.SuggestedReplies.value).fetchSemanticsNode().boundsInRoot
        val editor = onNodeWithTag(UiTestTag.MessageInput.value).fetchSemanticsNode().boundsInRoot
        assertEquals(host.left, panel.left)
        assertEquals(host.right, panel.right)
        assertEquals(host.bottom, panel.bottom)
        assertTrue(suggestions.bottom <= panel.top)
        assertTrue(editor.top >= panel.top)
        onNodeWithTag("status").assertDoesNotExist()
        onNodeWithTag(UiTestTag.AgentResponseHint.value).assertDoesNotExist()
        onNodeWithTag(UiTestTag.SuggestedReply(0).value).performClick()
        runOnIdle { assertEquals("Continue", draft); assertEquals(0, sends) }
    }

    @Test
    fun shortViewportKeepsMultilineEditorAndControlsVisibleDuringWork() = runComposeUiTest {
        var draft by mutableStateOf((1..12).joinToString("\n") { "Line $it" })
        val sent = mutableListOf<String>()
        setContent {
            GromozekaTheme {
                Box(Modifier.size(390.dp, 350.dp).testTag("host"), contentAlignment = Alignment.BottomCenter) {
                    TestMessageInput(draft, { draft = it }, waiting = true, onSend = { sent += draft })
                }
            }
        }
        onNodeWithTag(UiTestTag.MessageInput.value).assertIsDisplayed().assertIsEnabled()
        onNodeWithTag(UiTestTag.SendButton.value).assertIsDisplayed().assertIsEnabled()
        onNodeWithTag(UiTestTag.PttButton.value).assertIsDisplayed().assertIsEnabled()
        val host = onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
        val editor = onNodeWithTag(UiTestTag.MessageInput.value).fetchSemanticsNode().boundsInRoot
        val controls = onNodeWithTag("composer-controls").fetchSemanticsNode().boundsInRoot
        assertTrue(editor.top >= host.top && editor.bottom <= controls.bottom)
        val send = onNodeWithTag(UiTestTag.SendButton.value).fetchSemanticsNode().boundsInRoot
        assertTrue(send.top >= editor.bottom)
        assertTrue(send.right <= host.right && send.bottom <= host.bottom)
        assertTrue(controls.bottom <= host.bottom)
        assertTrue(editor.height < host.height / 2)
        onNodeWithTag(UiTestTag.MessageInput.value).performTextReplacement("Steering while busy")
        onNodeWithTag(UiTestTag.SendButton.value).performClick()
        runOnIdle { assertEquals(listOf("Steering while busy"), sent) }
    }

    @Test
    fun actionsAreInMenuAndEmptySuggestionsCanStillBeRegenerated() = runComposeUiTest {
        var picked = 0
        var captured = 0
        var regenerated: Conversation.Message.Id? = null
        setContent {
            GromozekaTheme {
                TestMessageInput("", {}, options = options(emptyList()), onPick = { picked++ },
                    onCapture = { captured++ }, onRegenerate = { regenerated = it })
            }
        }
        onNodeWithTag(UiTestTag.SuggestedReplies.value).assertDoesNotExist()
        onNodeWithTag("composer-attach-file").assertDoesNotExist()
        onNodeWithTag("composer-capture-screenshot").assertDoesNotExist()
        onNodeWithTag("composer-more-actions").performClick()
        onNodeWithTag("composer-attach-file").performClick()
        runOnIdle { assertEquals(1, picked); assertEquals(0, captured) }
        onNodeWithTag("composer-more-actions").performClick()
        onNodeWithTag("composer-capture-screenshot").performClick()
        runOnIdle { assertEquals(1, captured) }
        onNodeWithTag("composer-more-actions").performClick()
        onNodeWithTag(UiTestTag.SuggestedRepliesRefresh.value).performClick()
        runOnIdle { assertEquals(Conversation.Message.Id("source"), regenerated) }
        onNodeWithTag("composer-attach-file").assertDoesNotExist()
    }

    @Test
    fun menuDisablesOnlyOperationsAlreadyInProgress() = runComposeUiTest {
        setContent {
            GromozekaTheme {
                TestMessageInput("draft", {}, options = options(emptyList()), uploading = true, regenerating = true)
            }
        }
        onNodeWithTag(UiTestTag.MessageInput.value).assertIsEnabled()
        onNodeWithTag(UiTestTag.PttButton.value).assertIsEnabled()
        onNodeWithTag("composer-more-actions").performClick()
        onNodeWithTag("composer-attach-file").assertIsNotEnabled()
        onNodeWithTag("composer-capture-screenshot").assertIsNotEnabled()
        onNodeWithTag(UiTestTag.SuggestedRepliesRefresh.value).assertIsNotEnabled()
    }

    @Test
    fun invalidMentionRemainsVisibleWithoutReplyRoutingCaption() = runComposeUiTest {
        setContent {
            GromozekaTheme {
                TestMessageInput("@Alpha hi", {}, candidates = listOf(
                    AgentMentionCandidate(AgentDefinition.Id("alpha"), "Alpha", "@Alpha", connected = false),
                ))
            }
        }
        onNodeWithTag(UiTestTag.AgentResponseHint.value).assertDoesNotExist()
        onNodeWithTag(UiTestTag.MessageSubmissionError.value).assertIsDisplayed()
    }

    @Test
    fun pauseAndResumeUseIconsWithoutChangingTheirFrame() = runComposeUiTest {
        var paused by mutableStateOf(false)
        var stops = 0
        setContent { GromozekaTheme {
            ConversationExecutionControls(paused, false, true, { paused = true }, { paused = false }, { stops++ })
        } }
        val before = onNodeWithTag("composer-pause-execution").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("composer-pause-execution").performClick()
        runOnIdle { assertTrue(paused) }
        assertEquals(before, onNodeWithTag("composer-pause-execution").fetchSemanticsNode().boundsInRoot)
        onNodeWithTag("composer-pause-execution").performClick()
        runOnIdle { assertTrue(!paused) }
        onNodeWithTag("composer-stop-execution").performClick()
        runOnIdle { assertEquals(1, stops) }
    }

    @Test
    fun joinedVoiceModeSwitchesInOneClickWithoutTriggeringPttOrSending() = runComposeUiTest {
        var autoSend by mutableStateOf(true)
        var state by mutableStateOf(PttState.IDLE)
        val events = mutableListOf<PTTEvent>()
        var sends = 0
        val handler = object : PttEventHandler by NoOpPttEventHandler {
            override suspend fun handlePTTEvent(event: PTTEvent) { events += event }
        }
        setContent {
            GromozekaTheme {
                Box(Modifier.width(390.dp)) {
                    TestMessageInput("draft", {}, voiceAvailable = true, autoSend = autoSend, onAutoSend = { autoSend = it },
                        pttHandler = handler, pttState = state, onSend = { sends++ })
                }
            }
        }
        val ptt = onNodeWithTag(UiTestTag.PttButton.value).fetchSemanticsNode().boundsInRoot
        val mode = onNodeWithTag("voice-send-mode").fetchSemanticsNode().boundsInRoot
        assertEquals(ptt.right, mode.left)
        assertEquals(ptt.top, mode.top)
        assertEquals(ptt.bottom, mode.bottom)
        onNodeWithTag("voice-send-mode").performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "To input"))
        runOnIdle { assertTrue(!autoSend); assertTrue(events.isEmpty()); assertEquals(0, sends) }
        onNodeWithTag(UiTestTag.PttButton.value).performClick()
        runOnIdle { assertEquals(listOf(PTTEvent.SINGLE_CLICK), events) }
        for (next in listOf(PttState.PREPARING, PttState.RECORDING)) {
            runOnIdle { state = next }
            val previousMode = autoSend
            onNodeWithTag("voice-send-mode").assertIsEnabled().performClick()
            runOnIdle {
                assertEquals(!previousMode, autoSend)
                assertEquals(next, state)
                assertEquals(listOf(PTTEvent.SINGLE_CLICK), events)
                assertEquals(0, sends)
            }
            onNodeWithTag(UiTestTag.PttButton.value).assertIsEnabled()
        }
    }

    @Test
    fun recordingKeepsPttSquareAndIconOnlyInBothDensities() = runComposeUiTest {
        mainClock.autoAdvance = false
        var density by mutableStateOf(UiDensity.COMPACT)
        var state by mutableStateOf(PttState.IDLE)
        setContent {
            GromozekaTheme(density = density) {
                TestMessageInput("", {}, voiceAvailable = true, pttState = state)
            }
        }
        for (profile in UiDensity.entries) {
            runOnIdle { density = profile; state = PttState.IDLE }
            mainClock.advanceTimeByFrame()
            val expected = if (profile == UiDensity.COMPACT) 40.dp else 48.dp
            val button = onNodeWithTag(UiTestTag.PttButton.value)
            button.assertWidthIsEqualTo(expected).assertHeightIsEqualTo(expected)
            val idleBounds = button.fetchSemanticsNode().boundsInRoot
            runOnIdle { state = PttState.RECORDING }
            mainClock.advanceTimeBy(5_000)
            button.assertIsEnabled().assertWidthIsEqualTo(expected).assertHeightIsEqualTo(expected)
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
            assertEquals(idleBounds, button.fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test
    fun unavailableVoiceKeepsStopButHidesDictationMode() = runComposeUiTest {
        setContent { GromozekaTheme { TestMessageInput("", {}, voiceAvailable = false) } }
        onNodeWithTag("voice-send-mode").assertDoesNotExist()
        onNodeWithTag(UiTestTag.PttButton.value).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun narrowComposerMovesIdleLiveAndKeyboardActionToMenuButActiveLiveStaysVisible() = runComposeUiTest {
        var state by mutableStateOf(LiveVoiceInputState.IDLE)
        val service = object : LiveVoiceInputService by NoOpLiveVoiceInputService() {
            override suspend fun toggle() {
                state = if (state == LiveVoiceInputState.IDLE) LiveVoiceInputState.LISTENING else LiveVoiceInputState.IDLE
            }
        }
        setContent {
            GromozekaTheme {
                Box(Modifier.size(390.dp, 350.dp)) {
                    TestMessageInput("draft", {}, showLive = true, liveState = state, liveService = service, platform = ClientPlatform.WEB_TOUCH)
                }
            }
        }
        onNodeWithTag(UiTestTag.LiveVoiceButton.value).assertDoesNotExist()
        onNodeWithTag(UiTestTag.MessageInput.value).performClick()
        onNodeWithTag("composer-more-actions").performClick()
        onNodeWithTag("composer-hide-keyboard").assertIsDisplayed()
        onNodeWithTag(UiTestTag.LiveVoiceButton.value).performClick()
        onNodeWithTag(UiTestTag.LiveVoiceButton.value).assertIsDisplayed().assertIsEnabled()
        onNodeWithTag("composer-hide-keyboard").assertDoesNotExist()
        onNodeWithTag(UiTestTag.LiveVoiceButton.value).performClick()
        runOnIdle { assertEquals(LiveVoiceInputState.IDLE, state) }
        onNodeWithTag(UiTestTag.LiveVoiceButton.value).assertDoesNotExist()
    }

    @Test
    fun runtimeButtonTogglesOpenPanelAndReflectsItsVisibility() = runComposeUiTest {
        var visible by mutableStateOf(true)
        setContent {
            GromozekaTheme {
                Column(Modifier.width(600.dp)) {
                    SidePanelToggle(visible, { visible = !visible })
                    Box(Modifier.size(40.dp).testTag("lamp-open-details").clickable { visible = true }) {
                        ConversationIndicator(ConversationIndicatorState(ConversationActivity.READY, ConversationPressure.NORMAL, false, false, emptySet()),
                            ConversationIndicatorDrawing.FULL, "Ready")
                    }
                    if (visible) Text("Runtime content", Modifier.testTag("runtime-content"))
                }
            }
        }
        val toggle = onNodeWithTag("side-panel-toggle")
        toggle.assertIsSelected().assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
        toggle.performClick().assertIsNotSelected()
        onNodeWithTag("runtime-content").assertDoesNotExist()
        toggle.performClick().assertIsSelected()
        onNodeWithTag("runtime-content").assertIsDisplayed()
        // Clicking a status opens details; unlike the dedicated toggle, it never closes them.
        repeat(2) { onNodeWithTag("lamp-open-details").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() } }
        toggle.assertIsSelected()
        toggle.performClick()
        onNodeWithTag("lamp-open-details").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        toggle.assertIsSelected()
    }

    @Test
    fun desktopComposerButtonsHaveEqualSizesAndBaseline() = verifyComposerButtonSizes(880, UiDensity.COMPACT, voiceAvailable = true)

    @Test
    fun mobileComposerButtonsHaveEqualSizesAndBaseline() = verifyComposerButtonSizes(390, UiDensity.TOUCH, voiceAvailable = true)

    @Test
    fun wideTouchComposerRetainsTouchMetrics() = verifyComposerButtonSizes(880, UiDensity.TOUCH, voiceAvailable = true)

    @Test
    fun narrowDesktopComposerRetainsCompactMetrics() = verifyComposerButtonSizes(390, UiDensity.COMPACT, voiceAvailable = true)

    @Test
    fun unavailableVoiceStopUsesSameSizeAsOtherComposerButtons() = verifyComposerButtonSizes(390, UiDensity.TOUCH, voiceAvailable = false)

    private fun verifyComposerButtonSizes(width: Int, density: UiDensity, voiceAvailable: Boolean) = runComposeUiTest {
        val controlSize = if (density == UiDensity.TOUCH) 48.dp else 40.dp
        setContent {
            GromozekaTheme(density = density) {
                Box(Modifier.width(width.dp)) {
                    TestMessageInput("draft", {}, voiceAvailable = voiceAvailable, showLive = true,
                        instructions = MessageInstructionGroup.defaults(), onInsertLocation = {},
                        platform = if (density == UiDensity.TOUCH) ClientPlatform.WEB_TOUCH else ClientPlatform.WEB_DESKTOP)
                }
            }
        }
        onNodeWithTag(UiTestTag.MessageInput.value).performClick().assertHeightIsEqualTo(controlSize)
        val editorBottom = onNodeWithTag(UiTestTag.MessageInput.value).fetchSemanticsNode().boundsInRoot.bottom
        val sendFrame = onNodeWithTag(UiTestTag.SendButton.value).fetchSemanticsNode().boundsInRoot
        if (width < 600) assertTrue(sendFrame.top >= editorBottom, "Narrow controls belong below the editor")
        val controlBottom = if (width < 600) sendFrame.bottom else editorBottom
        val tags = mutableListOf("composer-more-actions", UiTestTag.PttButton.value, UiTestTag.SendButton.value, "composer-instruction:write_access")
        if (width >= 600) tags += listOf(UiTestTag.LiveVoiceButton.value, "composer-location")
        if (width >= 600 && density == UiDensity.TOUCH) tags += "composer-hide-keyboard"
        for (tag in tags) {
            val button = onNodeWithTag(tag)
            button.assertIsDisplayed().assertWidthIsEqualTo(controlSize).assertHeightIsEqualTo(controlSize)
            assertEquals(controlBottom, button.fetchSemanticsNode().boundsInRoot.bottom, tag)
        }
        if (voiceAvailable) {
            onNodeWithTag("voice-send-mode").assertHeightIsEqualTo(controlSize)
            onNodeWithTag("voice-send-mode").assertWidthIsEqualTo(controlSize)
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        }
    }

    @Test
    fun unavailableLiveIsHiddenOnDesktopButActiveSessionCanStillBeStopped() = verifyUnavailableLive(880)

    @Test
    fun unavailableLiveIsAbsentFromMobileMenuButActiveSessionCanStillBeStopped() = verifyUnavailableLive(390)

    private fun verifyUnavailableLive(width: Int) = runComposeUiTest {
        var state by mutableStateOf(LiveVoiceInputState.IDLE)
        setContent {
            GromozekaTheme {
                Box(Modifier.width(width.dp)) {
                    TestMessageInput("", {}, showLive = true, liveState = state, liveUnavailableReason = "No transcription provider")
                }
            }
        }
        onNodeWithTag(UiTestTag.LiveVoiceButton.value).assertDoesNotExist()
        onNodeWithTag("composer-more-actions").performClick()
        onNodeWithTag(UiTestTag.LiveVoiceButton.value).assertDoesNotExist()
        onNodeWithTag("composer-attach-file").performClick() // fake callback, also dismisses the menu
        runOnIdle { state = LiveVoiceInputState.LISTENING }
        onNodeWithTag(UiTestTag.LiveVoiceButton.value).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun compactActivityFramesDoNotMoveWhileStateChanges() = verifyActivityFrames(UiDensity.COMPACT, 880)

    @Test
    fun touchActivityFramesDoNotMoveWhileStateChanges() = verifyActivityFrames(UiDensity.TOUCH, 393)

    private fun verifyActivityFrames(density: UiDensity, width: Int) = runComposeUiTest {
        var activity by mutableStateOf(ConversationActivity.READY)
        val controlSize = if (density == UiDensity.TOUCH) 48.dp else 40.dp
        setContent { GromozekaTheme(density = density) {
            Box(Modifier.width(width.dp)) {
                TestMessageInput("", {}, waiting = activity != ConversationActivity.READY,
                    leadingContent = {
                        Box(Modifier.size(controlSize).testTag("lamp-frame"), contentAlignment = Alignment.Center) {
                            ConversationIndicator(ConversationIndicatorState(activity, ConversationPressure.NORMAL, false, false, emptySet()),
                                ConversationIndicatorDrawing.FULL, activity.name, Modifier.testTag("lamp"))
                        }
                    },
                    beforeActionsContent = {
                        ConversationExecutionControls(activity == ConversationActivity.PAUSED, false,
                            activity != ConversationActivity.READY, {}, {}, {})
                        })
            }
        } }
        val initialEditor = onNodeWithTag(UiTestTag.MessageInput.value).fetchSemanticsNode().boundsInRoot
        for (value in listOf(ConversationActivity.READY, ConversationActivity.WORKING, ConversationActivity.PAUSED)) {
            runOnIdle { activity = value }
            for (tag in listOf("composer-pause-execution", "composer-stop-execution")) {
                onNodeWithTag(tag).assertWidthIsEqualTo(controlSize).assertHeightIsEqualTo(controlSize)
            }
            val frame = onNodeWithTag("lamp-frame").assertWidthIsEqualTo(controlSize).assertHeightIsEqualTo(controlSize).fetchSemanticsNode().boundsInRoot
            val bulb = onNodeWithTag("lamp").assertWidthIsEqualTo(28.dp).assertHeightIsEqualTo(28.dp).fetchSemanticsNode().boundsInRoot
            assertEquals(frame.center, bulb.center)
            assertEquals(initialEditor, onNodeWithTag(UiTestTag.MessageInput.value).fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test
    fun idleSendButtonShowsDeliveryModeOnlyInTooltip() = verifyDeliveryTooltip(waiting = false)

    @Test
    fun busySendButtonShowsDeliveryModeOnlyInTooltip() = verifyDeliveryTooltip(waiting = true)

    private fun verifyDeliveryTooltip(waiting: Boolean) = runComposeUiTest {
        var mode by mutableStateOf(MessageDelivery.STEER)
        mainClock.autoAdvance = false
        setContent {
            GromozekaTheme {
                Box(Modifier.width(880.dp)) {
                    TestMessageInput("Draft", {}, waiting = waiting, deliveryMode = mode)
                }
            }
        }
        for ((selected, label) in listOf(
            MessageDelivery.STEER to "Steer — next safe point",
            MessageDelivery.QUEUE to "After the current turn",
        )) {
            runOnIdle { mode = selected }
            mainClock.advanceTimeByFrame()
            onNodeWithTag("message-delivery-mode").assertDoesNotExist()
            onNodeWithText(label).assertDoesNotExist()
            onNodeWithTag(UiTestTag.SendButton.value).performMouseInput { enter(center) }
            mainClock.advanceTimeBy(1_000)
            onNodeWithText(label).assertExists()
            onNodeWithTag(UiTestTag.SendButton.value).performMouseInput { exit() }
            mainClock.advanceTimeBy(500)
            onNodeWithText(label).assertDoesNotExist()
        }
    }

    private fun options(values: List<String>) = SuggestedReplyOptions(Conversation.Message.Id("source"), values)

}
