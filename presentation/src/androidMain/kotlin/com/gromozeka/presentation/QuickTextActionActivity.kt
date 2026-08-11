package com.gromozeka.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.gromozeka.client.GromozekaRemoteServices
import com.gromozeka.client.resolveRemoteUrl
import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.presentation.services.AndroidRemoteClientSettingsStore
import com.gromozeka.presentation.services.AndroidRemoteSessionCredentialStore
import com.gromozeka.remote.protocol.RemoteClientPlatform
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
        val inputText = intent.quickTextInput()
        if (inputText.isNullOrBlank()) {
            finishWithMessage("${action.title}: no text input")
            return
        }

        scope.launch {
            runCatching {
                executeQuickTextAction(action.id, inputText)
            }.onSuccess { result ->
                finishWithResult(intent, action.title, result)
            }.onFailure { error ->
                finishWithMessage("${action.title} failed: ${error.message ?: error::class.simpleName}")
            }
        }
    }

    private suspend fun executeQuickTextAction(
        quickTextActionId: QuickTextAction.Id,
        inputText: String,
    ): String {
        val context = applicationContext
        val settingsStore = AndroidRemoteClientSettingsStore(context)
        val remoteUrl = settingsStore.resolveRemoteUrl(fallbackUrl = bundledRemoteUrl())
            ?: error("Gromozeka server is not configured")
        val authConnection = RemoteAuthenticationConnection(
            remoteUrl = remoteUrl,
            clientLabel = "Android quick text action",
            sessionCredentialStore = AndroidRemoteSessionCredentialStore(context),
        )
        var services: GromozekaRemoteServices? = null
        try {
            val status = authConnection.status()
            check(status.authenticatedUser != null) { "Gromozeka is not signed in" }
            services = GromozekaRemoteServices(
                url = remoteUrl,
                httpClient = authConnection.httpClient,
                scope = scope,
                clientHomeDirectory = "android",
                clientPlatform = RemoteClientPlatform.ANDROID,
                clientSettingsStore = settingsStore,
            )
            services.initialize()
            return services.quickTextActionService.runAction(quickTextActionId, inputText).text
        } finally {
            runCatching { services?.close() }
            runCatching { authConnection.close() }
        }
    }

    private fun finishWithResult(
        intent: Intent,
        quickTextActionLabel: String,
        result: String,
    ) {
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        if (intent.action == Intent.ACTION_PROCESS_TEXT && !readOnly) {
            setResult(
                RESULT_OK,
                Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result),
            )
        } else {
            copyToClipboard(quickTextActionLabel, result)
            Toast.makeText(this, "$quickTextActionLabel copied to clipboard", Toast.LENGTH_SHORT).show()
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
            componentName.className.endsWith(".TranslateTextActionActivity") -> QuickTextAction.TRANSLATE_RU_EN_ID.value
            else -> null
        }

    private companion object {
        const val METADATA_DEFAULT_REMOTE_URL = "com.gromozeka.DEFAULT_REMOTE_URL"
        const val METADATA_QUICK_TEXT_ACTION_ID = "com.gromozeka.QUICK_TEXT_ACTION_ID"
    }
}
