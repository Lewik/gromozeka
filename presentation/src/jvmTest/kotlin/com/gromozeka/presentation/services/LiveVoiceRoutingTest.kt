package com.gromozeka.presentation.services

import com.gromozeka.client.NoOpLiveVoiceProviderVadService
import com.gromozeka.client.LiveVoiceProviderVadService
import com.gromozeka.client.LiveVoiceProviderVadSession
import com.gromozeka.domain.model.MessageInputContext
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LiveVoiceRoutingTest {
    @Test fun `queued phrases keep their own draft targets`() = queuedPhrases(false)
    @Test fun `queued phrases keep their own auto send targets`() = queuedPhrases(true)
    @Test fun `provider phrases keep draft targets when results arrive out of order`() = providerPhrases(false)
    @Test fun `provider phrases keep auto send targets when results arrive out of order`() = providerPhrases(true)

    private fun providerPhrases(autoSend: Boolean) = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, autoSend)
        fixture.settings.saveSettings { copy(userDeviceSettings = (userDeviceSettings as UserDeviceSettings.Desktop).let {
            it.copy(voiceInputSettings = it.voiceInputSettings.copy(liveVoiceVadMode = UserDeviceSettings.VoiceInputSettings.LiveVoiceVadMode.PROVIDER_VAD))
        }) }
        fixture.open("a")
        fixture.open("b")
        fixture.app.selectTab(0)
        runCurrent()
        val providerSession = object : LiveVoiceProviderVadSession {
            override val sessionId = "provider-session"
            override val events = MutableSharedFlow<ServerPayload>(extraBufferCapacity = 16)
            override suspend fun sendAudioChunk(chunk: RemotePcmAudioChunk) = Unit
            override suspend fun stop() = Unit
            override fun closeLocally() = Unit
        }
        val provider = object : LiveVoiceProviderVadService {
            override suspend fun unavailableReason() = null
            override suspend fun start(languageCode: String?, prompt: String?) = providerSession
        }
        val controller = LiveVoiceInputController(
            VoiceInputDelivery(fixture.app, fixture.settings, MessageInputContext.ClientPlatform.DESKTOP),
            fixture.recorder, fixture.transcription, provider, NoOpClientSideSpeechToTextService,
            NoOpTtsQueue(), fixture.settings, backgroundScope,
        )
        controller.start()
        runCurrent()
        providerSession.events.emit(LiveVoiceProviderVadSpeechStartedEvent(providerSession.sessionId, "first"))
        runCurrent()
        fixture.app.selectTab(1)
        runCurrent()
        providerSession.events.emit(LiveVoiceProviderVadSpeechStartedEvent(providerSession.sessionId, "second"))
        runCurrent()
        fixture.app.selectTab(0)
        runCurrent()
        providerSession.events.emit(LiveVoiceProviderVadTranscriptCompletedEvent(providerSession.sessionId, "second", "Second phrase"))
        providerSession.events.emit(LiveVoiceProviderVadTranscriptCompletedEvent(providerSession.sessionId, "first", "First phrase"))
        runCurrent()
        val tabs = fixture.app.tabs.value
        if (autoSend) {
            assertEquals(tabs.reversed().map { it.conversationId }, fixture.submitted.map { it.conversationId })
        } else {
            assertEquals(listOf("First phrase", "Second phrase"), tabs.map { it.userInput })
        }
        providerSession.events.emit(LiveVoiceProviderVadTranscriptCompletedEvent(providerSession.sessionId, "second", "Duplicate"))
        providerSession.events.emit(LiveVoiceProviderVadTranscriptCompletedEvent(providerSession.sessionId, "unknown", "Unmatched"))
        runCurrent()
        if (autoSend) assertEquals(2, fixture.submitted.size)
        else assertEquals(listOf("First phrase", "Second phrase"), tabs.map { it.userInput })
        controller.stop()
    }

    private fun queuedPhrases(autoSend: Boolean) = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, autoSend)
        fixture.open("a")
        fixture.open("b")
        fixture.app.selectTab(0)
        runCurrent()
        val controller = LiveVoiceInputController(
            VoiceInputDelivery(fixture.app, fixture.settings, MessageInputContext.ClientPlatform.DESKTOP),
            fixture.recorder, fixture.transcription, NoOpLiveVoiceProviderVadService,
            NoOpClientSideSpeechToTextService, NoOpTtsQueue(), fixture.settings,
            backgroundScope,
        )
        controller.start()
        runCurrent()
        fixture.recorder.chunks.emit(pcm(5_000, 400))
        runCurrent()
        fixture.app.selectTab(1)
        runCurrent()
        fixture.recorder.chunks.emit(pcm(5_000, 400))
        fixture.recorder.chunks.emit(pcm(0, 1_000))
        runCurrent()
        val first = fixture.transcription.requests.receive()
        fixture.recorder.chunks.emit(pcm(5_000, 400))
        fixture.recorder.chunks.emit(pcm(5_000, 400))
        fixture.recorder.chunks.emit(pcm(0, 1_000))
        runCurrent()
        fixture.app.selectTab(0)
        runCurrent()
        first.complete("First phrase")
        runCurrent()
        fixture.transcription.requests.receive().complete("Second phrase")
        runCurrent()
        val tabs = fixture.app.tabs.value
        if (autoSend) {
            assertEquals(tabs.map { it.conversationId }, fixture.submitted.map { it.conversationId })
        } else {
            assertEquals(listOf("First phrase", "Second phrase"), tabs.map { it.userInput })
            assertEquals(MessageInputContext.Source.LIVE_VOICE, tabs[0].uiState.value.composerMessageInputContext?.source)
        }
        controller.stop()
    }

    private fun pcm(sample: Int, millis: Int): ByteArray = ByteArray(millis * 32).also { output ->
        for (index in output.indices step 2) {
            output[index] = (sample shr 8).toByte()
            output[index + 1] = sample.toByte()
        }
    }
}
