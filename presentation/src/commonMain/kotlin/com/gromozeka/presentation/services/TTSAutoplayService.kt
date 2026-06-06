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

class TTSAutoplayService(
    private val appViewModel: AppViewModel,
    private val ttsQueueService: TtsQueue,
    private val settingsService: SettingsService,
    private val scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)

    private var currentTabJob: Job? = null
    private val seenMessageIds = mutableSetOf<Conversation.Message.Id>()

    fun start() {
        log.info("Starting auto TTS service")

        appViewModel.currentTab
            .onEach { tab ->
                currentTabJob?.cancel()
                currentTabJob = null

                if (tab != null) {
                    subscribeToTab(tab)
                }
            }
            .launchIn(scope)
    }

    private fun subscribeToTab(tab: com.gromozeka.presentation.ui.viewmodel.TabViewModel) {
        var historySnapshotSeen = false

        currentTabJob = tab.allMessages
            .onEach { messages ->
                if (!historySnapshotSeen) {
                    if (messages.isEmpty()) {
                        return@onEach
                    }

                    historySnapshotSeen = true
                    seenMessageIds += messages.map { it.id }
                    log.info("Auto TTS history snapshot skipped: messages=${messages.size}")
                    return@onEach
                }

                messages.forEach { message ->
                    if (!seenMessageIds.add(message.id)) {
                        return@forEach
                    }

                    if (shouldConsiderAutoTTS(message)) {
                        playAutoTTS(message)
                    }
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
