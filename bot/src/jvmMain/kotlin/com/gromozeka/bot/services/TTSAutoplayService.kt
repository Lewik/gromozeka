package com.gromozeka.bot.services

import com.gromozeka.bot.ui.viewmodel.AppViewModel
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.TtsTask
import klog.KLoggers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

@Service
class TTSAutoplayService(
    private val appViewModel: AppViewModel,
    private val ttsQueueService: TTSQueueService,
    private val settingsService: SettingsService,
    @Qualifier("coroutineScope") private val scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)

    private var currentTabJob: Job? = null
    private var subscriptionTimestamp: Instant? = null
    private var lastProcessedMessageIndex: Int = -1

    fun start() {
        log.info("Starting auto TTS service")

        appViewModel.currentTab
            .onEach { tab ->
                currentTabJob?.cancel()
                currentTabJob = null
                lastProcessedMessageIndex = -1

                if (tab != null) {
                    subscribeToTab(tab)
                }
            }
            .launchIn(scope)
    }

    private fun subscribeToTab(tab: com.gromozeka.bot.ui.viewmodel.TabViewModel) {
        subscriptionTimestamp = Clock.System.now()

        currentTabJob = tab.allMessages
            .onEach { messages ->
                val newMessages = messages.drop(lastProcessedMessageIndex + 1)

                newMessages.forEach { message ->
                    if (shouldPlayAutoTTS(message)) {
                        val messageTime = message.createdAt
                        val subscribeTime = subscriptionTimestamp
                        if (subscribeTime == null || messageTime >= subscribeTime) {
                            playAutoTTS(message)
                        }
                    }
                }

                if (messages.isNotEmpty()) {
                    lastProcessedMessageIndex = messages.size - 1
                }
            }
            .launchIn(scope)
    }

    private fun shouldPlayAutoTTS(message: Conversation.Message): Boolean {
        val settings = settingsService.settings

        // Check if TTS is enabled at all
        if (!settings.enableTts) return false

        // Only Assistant messages can have TTS
        if (message.role != Conversation.Message.Role.ASSISTANT) return false

        // Check if message has TTS content
        return message.content
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .any { it.structured.ttsText != null }
    }

    private suspend fun playAutoTTS(message: Conversation.Message) {
        try {
            val assistantContent = message.content
                .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
                .firstOrNull() ?: return

            val ttsText = assistantContent.structured.ttsText ?: return
            val voiceTone = assistantContent.structured.voiceTone ?: ""

            ttsQueueService.enqueue(
                TtsTask(
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
        currentTabJob?.cancel()
        currentTabJob = null
    }
}