package com.gromozeka.presentation.services

import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.TtsTask
import klog.KLoggers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class TTSAutoplayService(
    private val appViewModel: AppViewModel,
    private val ttsQueueService: TtsQueue,
    private val settingsService: SettingsService,
    private val scope: CoroutineScope,
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

    private fun subscribeToTab(tab: com.gromozeka.presentation.ui.viewmodel.TabViewModel) {
        subscriptionTimestamp = Clock.System.now()

        currentTabJob = tab.allMessages
            .onEach { messages ->
                val newMessages = messages.drop(lastProcessedMessageIndex + 1)

                newMessages.forEach { message ->
                    if (shouldConsiderAutoTTS(message)) {
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

    private fun shouldConsiderAutoTTS(message: Conversation.Message): Boolean {
        val settings = settingsService.settings

        // Check if TTS is enabled at all
        if (!settings.userProfile.speechSettings.textToSpeech.enabled) return false

        // Only Assistant messages can have TTS
        return message.role == Conversation.Message.Role.ASSISTANT
    }

    private suspend fun playAutoTTS(message: Conversation.Message) {
        try {
            val assistantContent = message.content
                .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
                .firstOrNull() ?: return

            val ttsText = assistantContent.structured.ttsText?.trim()?.takeIf { it.isNotBlank() }
            if (ttsText == null) {
                log.info(
                    "Auto TTS skipped: message=${message.id.value} reason=blank_tts_text " +
                        "fullTextChars=${assistantContent.structured.fullText.length}"
                )
                return
            }
            val voiceTone = assistantContent.structured.voiceTone ?: ""

            log.info(
                "Auto TTS enqueue: message=${message.id.value} textChars=${ttsText.length} " +
                    "voiceToneChars=${voiceTone.length}"
            )

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
