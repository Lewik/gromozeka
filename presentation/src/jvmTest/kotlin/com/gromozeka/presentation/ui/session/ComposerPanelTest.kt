package com.gromozeka.presentation.ui.session

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
import com.gromozeka.client.RemoteConnectionState
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageInstructionGroup
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
                    Input(draft, { draft = it }, options = options(listOf("Continue")), onSend = { sends++ })
                }
            }
        }
        val host = onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
        val panel = onNodeWithTag("conversation-composer-panel").fetchSemanticsNode().boundsInRoot
        val suggestions = onNodeWithTag(UiTestTag.SuggestedReplies.value).fetchSemanticsNode().boundsInRoot
        val status = onNodeWithTag("status").fetchSemanticsNode().boundsInRoot
        val editor = onNodeWithTag(UiTestTag.MessageInput.value).fetchSemanticsNode().boundsInRoot
        assertEquals(host.left, panel.left)
        assertEquals(host.right, panel.right)
        assertEquals(host.bottom, panel.bottom)
        assertTrue(suggestions.bottom <= panel.top)
        assertTrue(status.top >= panel.top && status.bottom <= editor.top)
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
                    Input(draft, { draft = it }, waiting = true, onSend = { sent += draft })
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
        assertTrue(send.left >= editor.right)
        assertEquals(editor.bottom, send.bottom)
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
                Input("", {}, options = options(emptyList()), onPick = { picked++ },
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
                Input("draft", {}, options = options(emptyList()), uploading = true, regenerating = true)
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
                Input("@Alpha hi", {}, candidates = listOf(
                    AgentMentionCandidate(AgentDefinition.Id("alpha"), "Alpha", "@Alpha", connected = false),
                ))
            }
        }
        onNodeWithTag(UiTestTag.AgentResponseHint.value).assertDoesNotExist()
        onNodeWithTag(UiTestTag.MessageSubmissionError.value).assertIsDisplayed()
    }

    @Test
    fun pauseAndResumeUseIconsAndExtraWorkDoesNotGrowPanel() = runComposeUiTest {
        var paused by mutableStateOf(false)
        var stops = 0
        setContent {
            GromozekaTheme {
                ComposerPanel(Modifier.width(500.dp)) {
                    ConversationActivityBar(
                        runtime = null, generation = null, isWaiting = true,
                        pauseRequested = paused, tokenStats = null, voice = emptyList(), tabId = "tab",
                        voiceError = null, connection = RemoteConnectionState(RemoteConnectionState.Status.CONNECTED),
                        onPause = { paused = true }, onResume = { paused = false }, onStop = { stops++ }, onDetails = {},
                        runtimePanelVisible = false, onToggleRuntimePanel = {},
                        uploadingArtifacts = true, regeneratingSuggestions = true,
                    )
                    Text("Editor", Modifier.testTag("editor"))
                }
            }
        }
        onNodeWithTag("composer-pause-execution").performClick()
        runOnIdle { assertTrue(paused) }
        onNodeWithTag("composer-pause-execution").performClick()
        runOnIdle { assertTrue(!paused) }
        onNodeWithTag("composer-stop-execution").performClick()
        runOnIdle { assertEquals(1, stops) }
        val bounds = onNodeWithTag("editor").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("composer-additional-activities").performClick()
        onNodeWithText("Generating suggested replies").assertIsDisplayed()
        assertEquals(bounds, onNodeWithTag("editor").fetchSemanticsNode().boundsInRoot)
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
                    Input("draft", {}, voiceAvailable = true, autoSend = autoSend, onAutoSend = { autoSend = it },
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
            onNodeWithTag("voice-send-mode").assertIsNotEnabled()
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
                Input("", {}, voiceAvailable = true, pttState = state)
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
        setContent { GromozekaTheme { Input("", {}, voiceAvailable = false) } }
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
                    Input("draft", {}, showLive = true, liveState = state, liveService = service, platform = ClientPlatform.WEB_TOUCH)
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
                    ConversationActivityBar(
                        runtime = null, generation = null, isWaiting = false, pauseRequested = false,
                        tokenStats = null, voice = emptyList(), tabId = "tab", voiceError = null,
                        connection = RemoteConnectionState(RemoteConnectionState.Status.CONNECTED),
                        onPause = {}, onResume = {}, onStop = {}, onDetails = { visible = true },
                        runtimePanelVisible = visible, onToggleRuntimePanel = { visible = !visible },
                    )
                    if (visible) Text("Runtime content", Modifier.testTag("runtime-content"))
                }
            }
        }
        val toggle = onNodeWithTag("composer-runtime-details")
        toggle.assertIsSelected().assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
        toggle.performClick().assertIsNotSelected()
        onNodeWithTag("runtime-content").assertDoesNotExist()
        toggle.performClick().assertIsSelected()
        onNodeWithTag("runtime-content").assertIsDisplayed()
        // Clicking a status opens details; unlike the dedicated toggle, it never closes them.
        repeat(2) { onNodeWithText("No active tasks").performClick() }
        toggle.assertIsSelected()
        toggle.performClick()
        onNodeWithText("No active tasks").performClick()
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
                    Input("draft", {}, voiceAvailable = voiceAvailable, showLive = true,
                        instructions = MessageInstructionGroup.defaults(), onInsertLocation = {},
                        platform = if (density == UiDensity.TOUCH) ClientPlatform.WEB_TOUCH else ClientPlatform.WEB_DESKTOP)
                }
            }
        }
        onNodeWithTag(UiTestTag.MessageInput.value).performClick().assertHeightIsEqualTo(controlSize)
        val editorBottom = onNodeWithTag(UiTestTag.MessageInput.value).fetchSemanticsNode().boundsInRoot.bottom
        val tags = mutableListOf("composer-more-actions", UiTestTag.PttButton.value, UiTestTag.SendButton.value, "composer-instruction:write_access")
        if (width >= 600) tags += listOf(UiTestTag.LiveVoiceButton.value, "composer-location")
        if (width >= 600 && density == UiDensity.TOUCH) tags += "composer-hide-keyboard"
        for (tag in tags) {
            val button = onNodeWithTag(tag)
            button.assertIsDisplayed().assertWidthIsEqualTo(controlSize).assertHeightIsEqualTo(controlSize)
            assertEquals(editorBottom, button.fetchSemanticsNode().boundsInRoot.bottom, tag)
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
                    Input("", {}, showLive = true, liveState = state, liveUnavailableReason = "No transcription provider")
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
    fun compactStatusAndInputShareColumnsAndVerticalSpacing() = verifyStatusGrid(UiDensity.COMPACT)

    @Test
    fun touchStatusAndInputShareColumnsAndVerticalSpacing() = verifyStatusGrid(UiDensity.TOUCH)

    private fun verifyStatusGrid(density: UiDensity) = runComposeUiTest {
        var waiting by mutableStateOf(false)
        var paused by mutableStateOf(false)
        val controlSize = if (density == UiDensity.TOUCH) 48.dp else 40.dp
        setContent {
            GromozekaTheme(density = density) {
                Box(Modifier.width(if (density == UiDensity.TOUCH) 393.dp else 880.dp)) {
                    Input("", {}, waiting = waiting, statusContent = {
                        ConversationActivityBar(
                            runtime = null, generation = null, isWaiting = waiting, pauseRequested = paused,
                            tokenStats = null, voice = emptyList(), tabId = "tab", voiceError = null,
                            connection = RemoteConnectionState(RemoteConnectionState.Status.CONNECTED),
                            onPause = {}, onResume = {}, onStop = {}, onDetails = {},
                            runtimePanelVisible = false, onToggleRuntimePanel = {},
                        )
                    })
                }
            }
        }
        for (symbol in listOf("composer-ready-symbol", "composer-loading-symbol", "composer-static-symbol")) {
            runOnIdle {
                waiting = symbol != "composer-ready-symbol"
                paused = symbol == "composer-static-symbol"
            }
            val squareNode = onNodeWithTag("composer-state-indicator")
            squareNode.assertWidthIsEqualTo(controlSize).assertHeightIsEqualTo(controlSize)
            val square = squareNode.fetchSemanticsNode().boundsInRoot
            val bulb = onNodeWithTag(symbol).fetchSemanticsNode().boundsInRoot
            val plus = onNodeWithTag("composer-more-actions").fetchSemanticsNode().boundsInRoot
            val runtime = onNodeWithTag("composer-runtime-details").fetchSemanticsNode().boundsInRoot
            val send = onNodeWithTag(UiTestTag.SendButton.value).fetchSemanticsNode().boundsInRoot
            val status = onNodeWithTag("conversation-activity-bar").fetchSemanticsNode().boundsInRoot
            val controls = onNodeWithTag("composer-controls").fetchSemanticsNode().boundsInRoot
            val panel = onNodeWithTag("conversation-composer-panel").fetchSemanticsNode().boundsInRoot
            assertEquals(plus.center.x, square.center.x)
            assertEquals(square.center.x, bulb.center.x)
            assertEquals(square.center.y, bulb.center.y)
            assertEquals(send.center.x, runtime.center.x)
            assertEquals(8f, controls.top - status.bottom)
            assertEquals(8f, panel.bottom - controls.bottom)
            assertEquals(8f, status.top - panel.top - 1f) // divider occupies one dp
            if (symbol != "composer-ready-symbol") onNodeWithTag("composer-ready-symbol").assertDoesNotExist()
        }
    }

    private fun options(values: List<String>) = SuggestedReplyOptions(Conversation.Message.Id("source"), values)

    @Composable
    private fun Input(
        draft: String,
        onDraft: (String) -> Unit,
        options: SuggestedReplyOptions? = null,
        waiting: Boolean = false,
        uploading: Boolean = false,
        regenerating: Boolean = false,
        candidates: List<AgentMentionCandidate> = emptyList(),
        instructions: List<MessageInstructionGroup> = emptyList(),
        onInsertLocation: (() -> Unit)? = null,
        voiceAvailable: Boolean = false,
        autoSend: Boolean = true,
        onAutoSend: (Boolean) -> Unit = {},
        pttHandler: PttEventHandler = NoOpPttEventHandler,
        pttState: PttState = PttState.IDLE,
        showLive: Boolean = false,
        liveState: LiveVoiceInputState = LiveVoiceInputState.IDLE,
        liveService: LiveVoiceInputService = NoOpLiveVoiceInputService(),
        liveUnavailableReason: String? = null,
        platform: ClientPlatform = ClientPlatform.DESKTOP,
        onSend: suspend () -> Unit = {},
        onPick: () -> Unit = {},
        onCapture: () -> Unit = {},
        onRegenerate: (Conversation.Message.Id) -> Unit = {},
        statusContent: @Composable () -> Unit = { Text("Status", Modifier.testTag("status")) },
    ) {
        MessageInput(
            userInput = draft, onUserInputChange = onDraft,
            isWaitingForResponse = waiting, pendingMessagesCount = 0,
            agentMentionCandidates = candidates, messageSubmissionError = null,
            suggestedReplies = options, suggestedRepliesRegenerating = regenerating,
            onRegenerateSuggestedReplies = onRegenerate, onSendMessage = onSend,
            coroutineScope = rememberCoroutineScope(), pttEventHandler = pttHandler,
            pttState = pttState, pttUnavailableReason = null,
            voiceAutoSend = autoSend, onVoiceAutoSendChange = onAutoSend,
            liveVoiceInputService = liveService, liveVoiceInputState = liveState,
            liveVoiceInputUnavailableReason = liveUnavailableReason, showLiveVoiceButton = showLive, showPttButton = voiceAvailable,
            clientPlatform = platform, instructionGroups = instructions,
            activeInstructionIds = emptySet(), onSelectInstruction = { _, _ -> },
            composerArtifacts = emptyList(), artifactUploadInProgress = uploading, artifactError = null,
            canPickAttachments = true, canCaptureScreenshot = true, onPickAttachments = onPick,
            onCaptureScreenshot = onCapture, onRemoveArtifact = {},
            onInsertCurrentLocation = onInsertLocation,
            statusContent = statusContent,
        )
    }
}
