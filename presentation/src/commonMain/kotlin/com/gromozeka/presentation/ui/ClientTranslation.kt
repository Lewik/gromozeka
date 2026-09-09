package com.gromozeka.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.Locale
import com.gromozeka.client.RemoteClientSettingsStore
import com.gromozeka.presentation.services.translation.TranslationService
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.shared.localization.BundledTranslations

@Composable
fun rememberClientTranslation(service: TranslationService?, settingsStore: RemoteClientSettingsStore): Translation {
    val bootstrap = remember(settingsStore, service) {
        Translation.builtIn.getValue(BundledTranslations.matchLocale(
            settingsStore.load()?.bootstrapLocale ?: Locale.current.toLanguageTag()
        ))
    }
    return service?.currentTranslation?.collectAsState()?.value ?: bootstrap
}

@Composable
fun ClientTheme(translation: Translation, content: @Composable () -> Unit) {
    TranslationProvider(translation) {
        GromozekaTheme(content = content)
    }
}

fun Throwable.clientErrorText(translation: Translation): String =
    if (this is com.gromozeka.client.RemoteServerAddressException) translation.text(messageKey)
    else authenticationErrorText(translation)
