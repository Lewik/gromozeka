package com.gromozeka.presentation.services

import com.gromozeka.domain.model.MessageInputContext
import com.gromozeka.domain.model.Tab
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel

data class VoiceInputTarget(val tabId: Tab.Id, val source: MessageInputContext.Source)

class VoiceInputDelivery(
    private val appViewModel: AppViewModel,
    private val settingsService: SettingsService,
    private val clientPlatform: MessageInputContext.ClientPlatform,
) {
    fun captureTarget(source: MessageInputContext.Source): VoiceInputTarget? {
        val index = appViewModel.currentTabIndex.value ?: return null
        val tab = appViewModel.tabs.value.getOrNull(index) ?: return null
        return VoiceInputTarget(Tab.Id(tab.uiState.value.tabId), source)
    }

    suspend fun deliver(target: VoiceInputTarget?, text: String): Boolean {
        val tab = target?.let { appViewModel.findTabByTabId(it.tabId) } ?: return false
        val context = MessageInputContext(
            modality = MessageInputContext.Modality.SPEECH_TO_TEXT,
            source = target.source,
            clientPlatform = clientPlatform,
            reliability = MessageInputContext.Reliability.MAY_CONTAIN_RECOGNITION_ERRORS,
        )
        if (settingsService.userDeviceSettings.voiceInputSettings.autoSend) {
            tab.sendMessageToSession(text, messageInputContext = context)
        } else {
            tab.appendUserInput(text, context)
        }
        return true
    }
}
