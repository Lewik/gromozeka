package com.gromozeka.presentation.services

import com.gromozeka.domain.model.MessageInputContext
import com.gromozeka.presentation.services.translation.LocalizedText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputDeliveryTest {
    @Test fun `draft policy survives changing both selected tab and its mode`() = capturedPolicy(false)
    @Test fun `auto send policy survives changing both selected tab and its mode`() = capturedPolicy(true)

    private fun capturedPolicy(autoSend: Boolean) = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, !autoSend)
        fixture.open("a")
        fixture.open("b")
        fixture.app.selectTab(0)
        runCurrent()
        val first = fixture.app.tabs.value[0]
        val second = fixture.app.tabs.value[1]
        first.setVoiceAutoSend(autoSend)
        val delivery = VoiceInputDelivery(fixture.app, fixture.settings, MessageInputContext.ClientPlatform.WEB_DESKTOP)
        val target = assertNotNull(delivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK))
        assertEquals(autoSend, target.autoSend)
        first.setVoiceAutoSend(!autoSend)
        fixture.app.selectTab(1)
        second.setVoiceAutoSend(!autoSend)
        runCurrent()
        assertTrue(delivery.deliver(target, "Captured phrase"))
        runCurrent()
        assertEquals("", second.userInput)
        if (autoSend) {
            assertEquals(first.conversationId, fixture.submitted.single().conversationId)
            assertEquals("", first.userInput)
        } else {
            assertEquals("Captured phrase", first.userInput)
            assertTrue(fixture.submitted.isEmpty())
        }
    }

    @Test
    fun `processing records retain their destination and finish independently`() = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, false)
        val delivery = VoiceInputDelivery(fixture.app, fixture.settings, MessageInputContext.ClientPlatform.WEB_DESKTOP)
        fixture.open("a")
        runCurrent()
        val first = assertNotNull(delivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK))
        delivery.transcriptionStarted("a", first)
        fixture.open("b")
        runCurrent()
        val second = assertNotNull(delivery.captureTarget(MessageInputContext.Source.LIVE_VOICE))
        delivery.transcriptionStarted("b", second)
        delivery.transcriptionStarted("b", second)
        assertEquals(listOf(first, second), delivery.transcriptions.value.map { it.target })
        delivery.transcriptionFinished("a")
        assertEquals(listOf("b"), delivery.transcriptions.value.map { it.id })
        delivery.cancelTranscriptions(MessageInputContext.Source.LIVE_VOICE)
        assertTrue(delivery.transcriptions.value.isEmpty())
    }

    @Test
    fun `failed processing can be dismissed or replaced only in its own tab`() = runTest {
        val fixture = VoiceInputTestFixture(backgroundScope, false)
        val delivery = VoiceInputDelivery(fixture.app, fixture.settings, MessageInputContext.ClientPlatform.WEB_DESKTOP)
        fixture.open("a")
        runCurrent()
        val first = assertNotNull(delivery.captureTarget(MessageInputContext.Source.LIVE_VOICE))
        delivery.transcriptionStarted("failed", first)
        delivery.transcriptionFinished("failed", LocalizedText.Literal("Recognition failed"))
        fixture.open("b")
        runCurrent()
        val second = assertNotNull(delivery.captureTarget(MessageInputContext.Source.PUSH_TO_TALK))
        delivery.transcriptionStarted("other", second)
        assertNotNull(delivery.transcriptions.value.first().error)
        delivery.transcriptionStarted("retry", first)
        assertEquals(listOf("other", "retry"), delivery.transcriptions.value.map { it.id })
        delivery.transcriptionFinished("retry", LocalizedText.Literal("Failed again"))
        delivery.transcriptionFinished("retry")
        assertEquals(listOf("other"), delivery.transcriptions.value.map { it.id })
        delivery.transcriptionStarted("unowned", null)
        assertFalse(delivery.transcriptions.value.any { it.id == "unowned" })
    }
}
