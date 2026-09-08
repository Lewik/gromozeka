package com.gromozeka.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.ui.text.intl.Locale
import com.gromozeka.client.GromozekaRemoteServices
import com.gromozeka.client.resolveRemoteUrl
import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.presentation.services.AndroidRemoteClientSettingsStore
import com.gromozeka.presentation.services.AndroidRemoteSessionCredentialStore
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText
import com.gromozeka.shared.localization.BundledTranslations
import com.gromozeka.remote.protocol.RemoteClientPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class QuickTextActionActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runAction(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun runAction(intent: Intent) {
        val action = resolveQuickTextAction()
        val settingsStore = AndroidRemoteClientSettingsStore(applicationContext)
        var translation = Translation(BundledTranslations.get(BundledTranslations.matchLocale(
            settingsStore.load()?.bootstrapLocale ?: Locale.current.toLanguageTag()
        )))
        val inputText = intent.quickTextInput()
        if (inputText.isNullOrBlank()) {
            finishWithMessage(translation.text("quickText.noInput", "action" to action.localizedTitle(translation)))
            return
        }

        scope.launch {
            try {
                val result = executeQuickTextAction(action.id, inputText, settingsStore) { translation = it }
                finishWithResult(intent, action.localizedTitle(translation), result, translation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                finishWithMessage(translation.text(
                    "quickText.failed", "action" to action.localizedTitle(translation),
                    "error" to error.localizedText().resolve(translation),
                ))
            }
        }
    }

    private suspend fun executeQuickTextAction(
        quickTextActionId: QuickTextAction.Id,
        inputText: String,
        settingsStore: AndroidRemoteClientSettingsStore,
        onTranslationLoaded: (Translation) -> Unit,
    ): String {
        val context = applicationContext
        val remoteUrl = settingsStore.resolveRemoteUrl(fallbackUrl = bundledRemoteUrl())
            ?: throw LocalizedTextException(localizedText("quickText.serverNotConfigured"))
        val authConnection = RemoteAuthenticationConnection(
            remoteUrl = remoteUrl,
            clientLabel = "Android quick text action",
            sessionCredentialStore = AndroidRemoteSessionCredentialStore(context),
        )
        var services: GromozekaRemoteServices? = null
        try {
            val status = authConnection.status()
            val authenticatedUser = status.authenticatedUser
                ?: throw LocalizedTextException(localizedText("quickText.notSignedIn"))
            services = GromozekaRemoteServices(
                url = remoteUrl,
                httpClient = authConnection.httpClient,
                scope = scope,
                clientHomeDirectory = "android",
                clientPlatform = RemoteClientPlatform.ANDROID,
                clientSettingsStore = settingsStore,
                authenticatedUserId = authenticatedUser.id,
                authenticatedUserRole = authenticatedUser.role,
            )
            services.initialize()
            val translation = Translation(services.translationService.snapshot().selectedPackage)
            onTranslationLoaded(translation)
            return services.quickTextActionService.runAction(quickTextActionId, inputText, translation.content.locale).text
        } finally {
            runCatching { services?.close() }
            runCatching { authConnection.close() }
        }
    }

    private fun QuickTextAction.localizedTitle(translation: Translation): String = when (id) {
        QuickTextAction.FIX_TEXT_ID -> translation.text("quickText.fix")
        QuickTextAction.TRANSLATE_INTERFACE_LANGUAGE_ID -> translation.text("quickText.translate")
        else -> title
    }

    private fun finishWithResult(
        intent: Intent,
        quickTextActionLabel: String,
        result: String,
        translation: Translation,
    ) {
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        if (intent.action == Intent.ACTION_PROCESS_TEXT && !readOnly) {
            setResult(
                RESULT_OK,
                Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result),
            )
        } else {
            copyToClipboard(quickTextActionLabel, result)
            Toast.makeText(this, translation.text("native.copiedToClipboard", "action" to quickTextActionLabel), Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
        }
        finish()
    }

    private fun finishWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun copyToClipboard(
        quickTextActionLabel: String,
        text: String,
    ) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(quickTextActionLabel, text))
    }

    private fun Intent.quickTextInput(): String? =
        when (action) {
            Intent.ACTION_PROCESS_TEXT -> getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        }

    private fun bundledRemoteUrl(): String? =
        packageManager
            .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData
            ?.getString(METADATA_DEFAULT_REMOTE_URL)
            ?.takeIf(String::isNotBlank)

    private fun resolveQuickTextAction(): QuickTextAction {
        val actionIdValue = actionIdFromMetadata()
            ?: actionIdFromComponentName()
            ?: error("Quick text action id is not configured")
        return QuickTextAction.defaults().firstOrNull { it.id.value == actionIdValue }
            ?: error("Unknown quick text action id: $actionIdValue")
    }

    private fun actionIdFromMetadata(): String? =
        packageManager
            .getActivityInfo(componentName, PackageManager.GET_META_DATA)
            .metaData
            ?.getString(METADATA_QUICK_TEXT_ACTION_ID)
            ?.takeIf(String::isNotBlank)

    private fun actionIdFromComponentName(): String? =
        when {
            componentName.className.endsWith(".FixTextActionActivity") -> QuickTextAction.FIX_TEXT_ID.value
            componentName.className.endsWith(".TranslateTextActionActivity") -> QuickTextAction.TRANSLATE_INTERFACE_LANGUAGE_ID.value
            else -> null
        }

    private companion object {
        const val METADATA_DEFAULT_REMOTE_URL = "com.gromozeka.DEFAULT_REMOTE_URL"
        const val METADATA_QUICK_TEXT_ACTION_ID = "com.gromozeka.QUICK_TEXT_ACTION_ID"
    }
}
