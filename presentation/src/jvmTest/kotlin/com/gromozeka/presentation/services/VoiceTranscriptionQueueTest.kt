package com.gromozeka.presentation.services

import com.gromozeka.client.AudioTranscriptionService
import com.gromozeka.domain.model.MessageInputContext
import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.remote.protocol.RemoteAudioChunk
import com.gromozeka.remote.protocol.RemoteAudioRecording
import com.gromozeka.shared.audio.SpeechPcmWav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTranscriptionQueueTest {
    @Test
    fun `default queue preserves capture order across tabs`() = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, autoSend = false)
        fixture.open("a")
        fixture.open("b")
        runCurrent()
        val delivery = VoiceInputDelivery(fixture.app, fixture.settings, MessageInputContext.ClientPlatform.DESKTOP)
        val queue = VoiceTranscriptionQueue(
            fixture.transcription,
            delivery,
            fixture.settings,
            UiFeedbackController(),
            backgroundScope,
        )
        fixture.app.selectTab(0)
        val firstTarget = assertNotNull(delivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK))
        fixture.app.selectTab(1)
        val secondTarget = assertNotNull(delivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK))

        queue.submit(recording("first"), firstTarget)
        queue.submit(recording("second"), secondTarget)
        runCurrent()

        val first = fixture.transcription.requests.receive()
        assertEquals("first", first.recording.sessionId)
        assertTrue(fixture.transcription.requests.tryReceive().isFailure)
        first.complete("First phrase")
        runCurrent()
        val second = fixture.transcription.requests.receive()
        assertEquals("second", second.recording.sessionId)
        second.complete("Second phrase")
        runCurrent()

        assertEquals("First phrase", fixture.app.tabs.value[0].userInput)
        assertEquals("Second phrase", fixture.app.tabs.value[1].userInput)
    }

    @Test
    fun `immediate transcription completion leaves no stale activity`() = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, autoSend = false)
        fixture.open("a")
        runCurrent()
        val delivery = VoiceInputDelivery(fixture.app, fixture.settings, MessageInputContext.ClientPlatform.DESKTOP)
        val queue = VoiceTranscriptionQueue(
            immediateTranscription("Immediate phrase"),
            delivery,
            fixture.settings,
            UiFeedbackController(),
            backgroundScope,
        )
        val target = assertNotNull(delivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK))

        queue.submit(recording("immediate"), target)
        runCurrent()

        assertEquals("Immediate phrase", fixture.app.tabs.value.single().userInput)
        assertTrue(delivery.transcriptions.value.isEmpty())
    }

    @Test
    fun `independent tabs can finish in either order while each tab stays sequential`() = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, autoSend = false)
        fixture.settings.setIndependentTabs(true)
        fixture.open("a")
        fixture.open("b")
        runCurrent()
        val delivery = VoiceInputDelivery(fixture.app, fixture.settings, MessageInputContext.ClientPlatform.DESKTOP)
        val queue = VoiceTranscriptionQueue(
            fixture.transcription,
            delivery,
            fixture.settings,
            UiFeedbackController(),
            backgroundScope,
        )
        fixture.app.selectTab(0)
        val firstTarget = assertNotNull(delivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK))
        fixture.app.selectTab(1)
        val secondTarget = assertNotNull(delivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK))

        queue.submit(recording("a-1"), firstTarget)
        queue.submit(recording("a-2"), firstTarget)
        queue.submit(recording("b-1"), secondTarget)
        runCurrent()

        val started = listOf(
            fixture.transcription.requests.receive(),
            fixture.transcription.requests.receive(),
        ).associateBy { it.recording.sessionId }
        assertEquals(setOf("a-1", "b-1"), started.keys)
        assertTrue(fixture.transcription.requests.tryReceive().isFailure)

        started.getValue("b-1").complete("B phrase")
        runCurrent()
        assertEquals("B phrase", fixture.app.tabs.value[1].userInput)
        assertEquals("", fixture.app.tabs.value[0].userInput)

        started.getValue("a-1").complete("A first")
        runCurrent()
        val next = fixture.transcription.requests.receive()
        assertEquals("a-2", next.recording.sessionId)
        next.complete("A second")
        runCurrent()

        assertEquals("A first A second", fixture.app.tabs.value[0].userInput)
        assertEquals("B phrase", fixture.app.tabs.value[1].userInput)
        assertFalse(delivery.transcriptions.value.any { it.error != null })
    }

    @Test
    fun `failed transcription releases the queue for the next tab`() = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, autoSend = false)
        fixture.open("a")
        fixture.open("b")
        runCurrent()
        val delivery = VoiceInputDelivery(fixture.app, fixture.settings, MessageInputContext.ClientPlatform.DESKTOP)
        val queue = VoiceTranscriptionQueue(
            fixture.transcription,
            delivery,
            fixture.settings,
            UiFeedbackController(),
            backgroundScope,
        )
        fixture.app.selectTab(0)
        val firstTarget = assertNotNull(delivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK))
        fixture.app.selectTab(1)
        val secondTarget = assertNotNull(delivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK))

        queue.submit(recording("failed"), firstTarget)
        queue.submit(recording("next"), secondTarget)
        runCurrent()
        fixture.transcription.requests.receive().fail(IllegalStateException("synthetic failure"))
        runCurrent()

        val next = fixture.transcription.requests.receive()
        assertEquals("next", next.recording.sessionId)
        assertNotNull(delivery.transcriptions.value.single { it.target == firstTarget }.error)
        next.complete("Recovered")
        runCurrent()
        assertEquals("Recovered", fixture.app.tabs.value[1].userInput)
    }

    @Test
    fun `request cancellation releases the lane for the next transcription`() = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, autoSend = false)
        fixture.open("a")
        runCurrent()
        val delivery = VoiceInputDelivery(fixture.app, fixture.settings, MessageInputContext.ClientPlatform.DESKTOP)
        val queue = VoiceTranscriptionQueue(
            fixture.transcription,
            delivery,
            fixture.settings,
            UiFeedbackController(),
            backgroundScope,
        )
        val target = assertNotNull(delivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK))

        queue.submit(recording("cancelled"), target)
        queue.submit(recording("next"), target)
        runCurrent()
        fixture.transcription.requests.receive().fail(CancellationException("request timeout"))
        runCurrent()

        val next = fixture.transcription.requests.receive()
        assertEquals("next", next.recording.sessionId)
        next.complete("Recovered")
        runCurrent()
        assertEquals("Recovered", fixture.app.tabs.value.single().userInput)
    }

    @Test
    fun `queue mode changes take effect only after active work drains`() = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, autoSend = false)
        fixture.open("a")
        fixture.open("b")
        runCurrent()
        val delivery = VoiceInputDelivery(fixture.app, fixture.settings, MessageInputContext.ClientPlatform.DESKTOP)
        val queue = VoiceTranscriptionQueue(
            fixture.transcription,
            delivery,
            fixture.settings,
            UiFeedbackController(),
            backgroundScope,
        )
        fixture.app.selectTab(0)
        val firstTarget = assertNotNull(delivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK))
        fixture.app.selectTab(1)
        val secondTarget = assertNotNull(delivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK))

        queue.submit(recording("global-a"), firstTarget)
        runCurrent()
        val globalFirst = fixture.transcription.requests.receive()
        fixture.settings.setIndependentTabs(true)
        queue.submit(recording("global-b"), secondTarget)
        runCurrent()
        assertTrue(fixture.transcription.requests.tryReceive().isFailure)

        globalFirst.complete("Global A")
        runCurrent()
        fixture.transcription.requests.receive().complete("Global B")
        runCurrent()

        queue.submit(recording("independent-a"), firstTarget)
        queue.submit(recording("independent-b"), secondTarget)
        runCurrent()
        val independent = listOf(
            fixture.transcription.requests.receive(),
            fixture.transcription.requests.receive(),
        )
        assertEquals(
            setOf("independent-a", "independent-b"),
            independent.map { it.recording.sessionId }.toSet(),
        )
        independent.forEach { it.complete(it.recording.sessionId) }
        runCurrent()
    }

    private fun immediateTranscription(text: String): AudioTranscriptionService =
        object : AudioTranscriptionService {
            override suspend fun transcribe(recording: RemoteAudioRecording): String = text
            override suspend fun captureUnavailableReason() = null
            override suspend fun startCapture(sessionId: String) = error("Unexpected capture start")
            override suspend fun stopCapture(sessionId: String) = error("Unexpected capture stop")
            override suspend fun cancelCapture(sessionId: String) = error("Unexpected capture cancel")
        }

    private fun VoiceTestSettings.setIndependentTabs(enabled: Boolean) {
        saveSettings {
            copy(userDeviceSettings = (userDeviceSettings as UserDeviceSettings.Desktop).copy(
                voiceInputSettings = userDeviceSettings.voiceInputSettings.copy(
                    allowIndependentTranscriptionAcrossTabs = enabled,
                )
            ))
        }
    }

    private fun recording(id: String): RemoteAudioRecording = RemoteAudioRecording(
        sessionId = id,
        format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
        chunks = listOf(RemoteAudioChunk(
            sequenceNumber = 0,
            data = SpeechPcmWav.encode(ByteArray(640)),
        )),
    )
}
