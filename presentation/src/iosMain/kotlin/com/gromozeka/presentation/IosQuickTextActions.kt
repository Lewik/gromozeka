package com.gromozeka.presentation

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
    iosQuickTextActionScope.launch {
        runCatching {
            executeIosQuickTextAction(actionId, text)
        }.onSuccess { result ->
            completion(result, null)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            completion(null, error.message ?: error.toString())
        }
    }
}

private suspend fun executeIosQuickTextAction(
    actionId: String,
    text: String,
): String {
    require(text.isNotBlank()) { "Quick text action input must not be blank" }

    val settingsStore = IosRemoteClientSettingsStore()
    val remoteUrl = settingsStore.resolveRemoteUrl(fallbackUrl = iosBundledRemoteUrl())
        ?: error("Gromozeka server is not configured")
    val authConnection = RemoteAuthenticationConnection(
        remoteUrl = remoteUrl,
        clientLabel = "iOS quick text action",
        sessionCredentialStore = IosRemoteSessionCredentialStore(),
    )
    var services: GromozekaRemoteServices? = null
    try {
        val status = authConnection.status()
        val authenticatedUser = checkNotNull(status.authenticatedUser) { "Gromozeka is not signed in" }
        services = GromozekaRemoteServices(
            url = remoteUrl,
            httpClient = authConnection.httpClient,
            scope = iosQuickTextActionScope,
            clientHomeDirectory = "ios",
            clientPlatform = RemoteClientPlatform.IOS,
            clientSettingsStore = settingsStore,
            authenticatedUserRole = authenticatedUser.role,
        )
        services.initialize()
        return services.quickTextActionService.runAction(QuickTextAction.Id(actionId), text).text
    } finally {
        runCatching { services?.close() }
        runCatching { authConnection.close() }
    }
}

private fun iosBundledRemoteUrl(): String? =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("GromozekaDefaultRemoteUrl") as? String)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
