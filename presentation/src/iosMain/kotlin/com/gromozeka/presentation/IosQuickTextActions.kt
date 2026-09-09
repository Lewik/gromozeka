package com.gromozeka.presentation

import androidx.compose.ui.text.intl.Locale
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText
import com.gromozeka.shared.localization.BundledTranslations
import com.gromozeka.client.GromozekaRemoteServices
import com.gromozeka.client.resolveRemoteUrl
import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.presentation.services.IosRemoteClientSettingsStore
import com.gromozeka.presentation.services.IosRemoteSessionCredentialStore
import com.gromozeka.remote.protocol.RemoteClientPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle

private val iosQuickTextActionScope = MainScope()

fun runIosQuickTextAction(
    actionId: String,
    text: String,
    completion: (String?, String?) -> Unit,
) {
    val settingsStore = IosRemoteClientSettingsStore()
    var translation = Translation(BundledTranslations.get(BundledTranslations.matchLocale(
        settingsStore.load()?.bootstrapLocale ?: Locale.current.toLanguageTag()
    )))
    if (text.isBlank()) {
        val titleKey = when (QuickTextAction.Id(actionId)) {
            QuickTextAction.FIX_TEXT_ID -> "quickText.fix"
            QuickTextAction.TRANSLATE_INTERFACE_LANGUAGE_ID -> "quickText.translate"
            else -> "settingsUi.quickTextActions"
        }
        completion(null, translation.text("quickText.noInput", "action" to translation.text(titleKey)))
        return
    }
    iosQuickTextActionScope.launch {
        runCatching {
            executeIosQuickTextAction(actionId, text, settingsStore) { translation = it }
        }.onSuccess { result ->
            completion(result, null)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            val titleKey = when (QuickTextAction.Id(actionId)) {
                QuickTextAction.FIX_TEXT_ID -> "quickText.fix"
                QuickTextAction.TRANSLATE_INTERFACE_LANGUAGE_ID -> "quickText.translate"
                else -> "settingsUi.quickTextActions"
            }
            completion(null, translation.text(
                "quickText.failed", "action" to translation.text(titleKey),
                "error" to error.localizedText().resolve(translation),
            ))
        }
    }
}

private suspend fun executeIosQuickTextAction(
    actionId: String,
    text: String,
    settingsStore: IosRemoteClientSettingsStore,
    onTranslationLoaded: (Translation) -> Unit,
): String {
    val remoteUrl = settingsStore.resolveRemoteUrl(fallbackUrl = iosBundledRemoteUrl())
        ?: throw LocalizedTextException(localizedText("quickText.serverNotConfigured"))
    val authConnection = RemoteAuthenticationConnection(
        remoteUrl = remoteUrl,
        clientLabel = "iOS quick text action",
        sessionCredentialStore = IosRemoteSessionCredentialStore(),
    )
    var services: GromozekaRemoteServices? = null
    try {
        val status = authConnection.status()
        val authenticatedUser = status.authenticatedUser
            ?: throw LocalizedTextException(localizedText("quickText.notSignedIn"))
        services = GromozekaRemoteServices(
            url = remoteUrl,
            httpClient = authConnection.httpClient,
            scope = iosQuickTextActionScope,
            clientHomeDirectory = "ios",
            clientPlatform = RemoteClientPlatform.IOS,
            clientSettingsStore = settingsStore,
            authenticatedUserId = authenticatedUser.id,
            authenticatedUserRole = authenticatedUser.role,
        )
        services.initialize()
        val translation = Translation(services.translationService.snapshot().selectedPackage)
        onTranslationLoaded(translation)
        return services.quickTextActionService.runAction(QuickTextAction.Id(actionId), text, translation.content.locale).text
    } finally {
        runCatching { services?.close() }
        runCatching { authConnection.close() }
    }
}

private fun iosBundledRemoteUrl(): String? =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("GromozekaRemoteURL") as? String)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
