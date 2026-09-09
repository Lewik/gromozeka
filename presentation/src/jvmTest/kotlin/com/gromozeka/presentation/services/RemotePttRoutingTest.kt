package com.gromozeka.presentation.services

import com.gromozeka.domain.model.MessageInputContext
import com.gromozeka.domain.model.SpeechAudioSource
import com.gromozeka.domain.model.WorkerAudioInput
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class RemotePttRoutingTest {
    @Test fun `draft stays in the recording tab when selection changes during recognition`() = routing(false, SwitchPoint.RECOGNITION)
    @Test fun `auto send stays in the recording tab when selection changes during recognition`() = routing(true, SwitchPoint.RECOGNITION)
    @Test fun `draft stays in the recording tab when selection changes during recording`() = routing(false, SwitchPoint.RECORDING)
    @Test fun `auto send stays in the recording tab when selection changes during recording`() = routing(true, SwitchPoint.RECORDING)
    @Test fun `draft target survives slow microphone preparation and early release`() = routing(false, SwitchPoint.PREPARING)
    @Test fun `auto send target survives slow microphone preparation and early release`() = routing(true, SwitchPoint.PREPARING)
    @Test fun `worker draft target is captured before hold confirmation`() = routing(false, SwitchPoint.WORKER_ARMED)
    @Test fun `worker auto send target is captured before hold confirmation`() = routing(true, SwitchPoint.WORKER_ARMED)
    @Test fun `closing the target never redirects a draft to its neighbor`() = routing(false, SwitchPoint.CLOSED)
    @Test fun `closing the target never redirects auto send to its neighbor`() = routing(true, SwitchPoint.CLOSED)
    @Test fun `recording without a target never attaches to a later selection`() = missingTarget(false)
    @Test fun `recording without a target never sends to a later selection`() = missingTarget(true)
    @Test fun `cancelled preparation cannot replace the next recording draft target`() = cancelledPreparation(false)
    @Test fun `cancelled preparation cannot replace the next recording auto send target`() = cancelledPreparation(true)

    private fun missingTarget(autoSend: Boolean) = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, autoSend)
        fixture.open("a")
        fixture.app.selectTab(-1)
        runCurrent()
        val controller = fixture.controller(backgroundScope)
        controller.handlePTTEvent(PTTEvent.BUTTON_DOWN)
        runCurrent()
        fixture.app.selectTab(0)
        runCurrent()
        val release = launch { controller.handlePTTRelease() }
        runCurrent()
        fixture.transcription.requests.receive().complete("Orphan phrase")
        release.join()
        runCurrent()
        assertEquals("", fixture.app.tabs.value.single().userInput)
        assertEquals(emptyList(), fixture.submitted)
        assertNotNull(controller.statusMessage.value)
    }

    private fun cancelledPreparation(autoSend: Boolean) = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, autoSend)
        fixture.open("a")
        fixture.open("b")
        fixture.app.selectTab(0)
        runCurrent()
        val cancelled = CompletableDeferred<Unit>()
        fixture.recorder.preparation = cancelled
        val controller = fixture.controller(backgroundScope)
        controller.handlePTTEvent(PTTEvent.BUTTON_DOWN)
        runCurrent()
        controller.handlePTTCancel()
        runCurrent()
        fixture.app.selectTab(1)
        runCurrent()
        fixture.recorder.preparation = CompletableDeferred(Unit)
        controller.handlePTTEvent(PTTEvent.BUTTON_DOWN)
        runCurrent()
        cancelled.complete(Unit)
        runCurrent()
        val release = launch { controller.handlePTTRelease() }
        runCurrent()
        fixture.transcription.requests.receive().complete("New recording")
        release.join()
        runCurrent()
        val tabs = fixture.app.tabs.value
        assertEquals("", tabs[0].userInput)
        if (autoSend) assertEquals(tabs[1].conversationId, fixture.submitted.single().conversationId)
        else assertEquals("New recording", tabs[1].userInput)
    }

    private fun routing(autoSend: Boolean, switchPoint: SwitchPoint) = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, autoSend)
        if (switchPoint == SwitchPoint.WORKER_ARMED) fixture.settings.saveSettings { copy(userProfile = userProfile.copy(
            speechSettings = userProfile.speechSettings.copy(speechToText = userProfile.speechSettings.speechToText.copy(
                audioSource = SpeechAudioSource.WorkerInput(ConversationRuntimeWorkerId("worker"), WorkerAudioInput.SystemDefault.id),
            )),
        )) }
        fixture.open("a")
        fixture.open("b")
        fixture.app.selectTab(0)
        runCurrent()
        val original = fixture.app.tabs.value[0]
        val other = fixture.app.tabs.value[1]
        original.updateUserInput("Original draft")
        other.updateUserInput("Other draft")
        val controller = fixture.controller(backgroundScope)
        if (switchPoint == SwitchPoint.PREPARING) fixture.recorder.preparation = CompletableDeferred()
        controller.handlePTTEvent(PTTEvent.BUTTON_DOWN)
        runCurrent()
        if (switchPoint in setOf(SwitchPoint.PREPARING, SwitchPoint.RECORDING, SwitchPoint.WORKER_ARMED)) {
            fixture.app.selectTab(1)
            runCurrent()
        }
        if (switchPoint == SwitchPoint.WORKER_ARMED) {
            controller.handlePTTEvent(PTTEvent.BUTTON_DOWN)
            controller.handlePTTEvent(PTTEvent.SINGLE_PUSH)
            runCurrent()
        }
        val release = launch { controller.handlePTTRelease() }
        runCurrent()
        fixture.recorder.preparation.complete(Unit)
        runCurrent()
        val recognition = fixture.transcription.requests.receive()
        if (switchPoint == SwitchPoint.RECOGNITION) { fixture.app.selectTab(1); runCurrent() }
        if (switchPoint == SwitchPoint.CLOSED) { fixture.app.closeTab(0); fixture.app.selectTab(0); runCurrent() }
        recognition.complete("Dictated text")
        release.join()
        runCurrent()

        assertEquals("Other draft", other.userInput)
        if (switchPoint == SwitchPoint.CLOSED) {
            assertEquals("Original draft", original.userInput)
            assertEquals(emptyList(), fixture.submitted)
            assertNotNull(controller.statusMessage.value)
            return@runTest
        }
        if (autoSend) {
            assertEquals(original.conversationId, fixture.submitted.single().conversationId)
        } else {
            assertEquals("Original draft Dictated text", original.userInput)
            assertEquals(emptyList(), fixture.submitted)
            assertEquals(MessageInputContext.Source.PUSH_TO_TALK, original.uiState.value.composerMessageInputContext?.source)
        }
    }

    private enum class SwitchPoint { PREPARING, RECORDING, RECOGNITION, WORKER_ARMED, CLOSED }

    private fun VoiceInputTestFixture.controller(scope: CoroutineScope) = RemotePttController(
        appViewModel = app,
        audioRecorder = recorder,
        audioTranscriptionService = transcription,
        clientSideSpeechToTextService = NoOpClientSideSpeechToTextService,
        ttsQueue = NoOpTtsQueue(),
        systemAudioMuteService = NoOpSystemAudioMuteService,
        settingsService = settings,
        uiFeedbackController = UiFeedbackController(),
        voiceInputDelivery = VoiceInputDelivery(app, settings, MessageInputContext.ClientPlatform.DESKTOP),
        scope = scope,
    )
}
