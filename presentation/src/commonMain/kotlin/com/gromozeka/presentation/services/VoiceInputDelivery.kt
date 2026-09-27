package com.gromozeka.presentation.services

import com.gromozeka.domain.model.MessageInputContext
import com.gromozeka.domain.model.Tab
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant

/** Destination and send policy are captured together, not read from the next active tab. */
data class VoiceInputTarget(val tabId: Tab.Id, val source: MessageInputContext.Source, val autoSend: Boolean)

data class VoiceTranscriptionActivity(
    val id: String,
    val target: VoiceInputTarget,
    val startedAt: Instant,
    val error: LocalizedText? = null,
)

class VoiceInputDelivery(
    private val appViewModel: AppViewModel,
    private val settingsService: SettingsService,
    private val clientPlatform: MessageInputContext.ClientPlatform,
) {
    private val _transcriptions = MutableStateFlow<List<VoiceTranscriptionActivity>>(emptyList())
    val transcriptions = _transcriptions.asStateFlow()

    fun captureTarget(source: MessageInputContext.Source): VoiceInputTarget? {
        val index = appViewModel.currentTabIndex.value ?: return null
        val tab = appViewModel.tabs.value.getOrNull(index) ?: return null
        return VoiceInputTarget(
            Tab.Id(tab.uiState.value.tabId), source,
            tab.uiState.value.voiceAutoSend ?: settingsService.userDeviceSettings.voiceInputSettings.autoSend,
        )
    }

    fun transcriptionStarted(id: String, target: VoiceInputTarget?) {
        if (target == null) return
        _transcriptions.update { current ->
            if (current.any { it.id == id }) current
            else current.filterNot { it.error != null && it.target.tabId == target.tabId } +
                VoiceTranscriptionActivity(id, target, Clock.System.now())
        }
    }

    fun transcriptionFinished(id: String, error: LocalizedText? = null) {
        _transcriptions.update { current ->
            if (error == null) current.filterNot { it.id == id }
            else current.map { if (it.id == id) it.copy(error = error) else it }
        }
    }

    fun cancelTranscriptions(source: MessageInputContext.Source) {
        _transcriptions.update { current -> current.filterNot { it.target.source == source && it.error == null } }
    }

    suspend fun deliver(target: VoiceInputTarget?, text: String): Boolean {
        val tab = target?.let { appViewModel.findTabByTabId(it.tabId) } ?: return false
        val context = MessageInputContext(
            modality = MessageInputContext.Modality.SPEECH_TO_TEXT,
            source = target.source,
            clientPlatform = clientPlatform,
            reliability = MessageInputContext.Reliability.MAY_CONTAIN_RECOGNITION_ERRORS,
        )
        if (target.autoSend) tab.sendMessageToSession(text, messageInputContext = context)
        else tab.appendUserInput(text, context)
        return true
    }
}
