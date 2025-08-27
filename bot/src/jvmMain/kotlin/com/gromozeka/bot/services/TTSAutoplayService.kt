package com.gromozeka.bot.services

import com.gromozeka.bot.ui.viewmodel.AppViewModel
import com.gromozeka.shared.domain.message.ChatMessage
import klog.KLoggers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.Instant

@Service
class TTSAutoplayService(
    private val appViewModel: AppViewModel,
    private val ttsQueueService: TTSQueueService,
    private val settingsService: SettingsService,
    @Qualifier("coroutineScope") private val scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)

    private var currentSessionJob: Job? = null
    private var subscriptionTimestamp: Instant? = null

    fun start() {
        log.info("Starting auto TTS service")

        // Subscribe to currentSession changes
        appViewModel.currentSession
            .onEach { session ->
                // Unsubscribe from previous session
                currentSessionJob?.cancel()
                currentSessionJob = null

                if (session != null) {
                    subscribeToSession(session)
                }
            }
            .launchIn(scope)
    }

    private fun subscribeToSession(session: com.gromozeka.bot.model.Session) {
        // Capture subscription time to filter out replay messages
        subscriptionTimestamp = Clock.System.now()

        currentSessionJob = session.messageOutputStream
            .filter { message ->
                // Skip historical messages
                if (message.isHistorical) return@filter false

                // Skip replay messages (older than subscription)
                val messageTime = message.timestamp
                val subscribeTime = subscriptionTimestamp
                if (subscribeTime != null && messageTime < subscribeTime) {
                    return@filter false
                }

                // Check if should play TTS
                shouldPlayAutoTTS(message)
            }
            .onEach { message ->
                playAutoTTS(message)
            }
            .launchIn(scope)
    }

    private fun shouldPlayAutoTTS(message: ChatMessage): Boolean {
        val settings = settingsService.settings

        // Check if TTS is enabled at all
        if (!settings.enableTts) return false

        // Only Assistant messages can have TTS
        if (message.role != ChatMessage.Role.ASSISTANT) return false

        // Check if message has TTS content
        return message.content
            .filterIsInstance<ChatMessage.ContentItem.AssistantMessage>()
            .any { it.structured.ttsText != null }
    }

    private suspend fun playAutoTTS(message: ChatMessage) {
        try {
            val assistantContent = message.content
                .filterIsInstance<ChatMessage.ContentItem.AssistantMessage>()
                .firstOrNull() ?: return

            val ttsText = assistantContent.structured.ttsText ?: return
            val voiceTone = assistantContent.structured.voiceTone ?: ""

            ttsQueueService.enqueue(
                TTSQueueService.Task(
                    text = ttsText,
                    tone = voiceTone
                )
            )
        } catch (e: Exception) {
            log.warn("Error playing auto TTS: ${e.message}")
        }
    }

    fun shutdown() {
        log.info("Shutting down auto TTS service")
        currentSessionJob?.cancel()
        currentSessionJob = null
    }
}