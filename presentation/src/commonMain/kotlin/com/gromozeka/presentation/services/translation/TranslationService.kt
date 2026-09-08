package com.gromozeka.presentation.services.translation

import com.gromozeka.client.RemoteClientSettingsService
import com.gromozeka.client.RemoteTranslationService
import com.gromozeka.domain.model.TranslationSnapshot
import com.gromozeka.domain.model.User
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.shared.localization.BundledTranslations
import com.gromozeka.shared.localization.TranslationJson
import com.gromozeka.shared.localization.TranslationValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TranslationService(
    private val remote: RemoteTranslationService,
    private val clientSettings: RemoteClientSettingsService,
    private val serverUrl: String,
    private val userId: User.Id,
    scope: CoroutineScope,
) {
    private val mutations = Mutex()
    private val snapshots = Mutex()
    private val cached = clientSettings.cachedTranslation(serverUrl, userId)?.selectedPackage
        ?.takeIf { TranslationValidator.validate(it).isEmpty() }
    private val initial = cached ?: BundledTranslations.get(
        BundledTranslations.matchLocale(clientSettings.settingsFlow.value.bootstrapLocale ?: androidx.compose.ui.text.intl.Locale.current.toLanguageTag())
    )
    private val _currentTranslation = MutableStateFlow(Translation(initial))
    private val _snapshot = MutableStateFlow<TranslationSnapshot?>(null)
    private val _operationError = MutableStateFlow<Throwable?>(null)
    private val _busy = MutableStateFlow(false)

    val currentTranslation = _currentTranslation.asStateFlow()
    val snapshot = _snapshot.asStateFlow()
    val operationError = _operationError.asStateFlow()
    val busy = _busy.asStateFlow()

    init {
        scope.launch {
            remote.observe().onEach(::accept).retryWhen { error, _ ->
                if (error is CancellationException) throw error
                _operationError.value = error
                delay(1_000)
                true
            }.collect {}
        }
    }

    suspend fun refreshTranslations() = operate { accept(remote.snapshot()) }

    suspend fun select(selectionId: String) = operate {
        accept(remote.select(selectionId, revision()))
    }

    suspend fun setSynchronizeClients(enabled: Boolean) = operate {
        accept(remote.setSynchronizeClients(enabled, revision()))
    }

    suspend fun importJson(json: String) = operate {
        TranslationValidator.requireValid(TranslationJson.decode(json))
        val saved = remote.savePackage(json, revision())
        accept(saved.snapshot)
        accept(remote.select(saved.packageId, saved.snapshot.revision))
    }

    suspend fun deletePackage(selectionId: String) = operate {
        val current = requireNotNull(_snapshot.value) { "Translation settings have not loaded" }
        accept(remote.deletePackage(selectionId, current.revision))
    }

    fun exportJson(): String = TranslationJson.encode(currentTranslation.value.content)

    private fun revision(): Long = requireNotNull(_snapshot.value) { "Translation settings have not loaded" }.revision

    private suspend fun accept(snapshot: TranslationSnapshot) = snapshots.withLock {
        if (snapshot.revision < (_snapshot.value?.revision ?: -1)) return@withLock
        TranslationValidator.requireValid(snapshot.selectedPackage)
        if (_currentTranslation.value.content != snapshot.selectedPackage) {
            _currentTranslation.value = Translation(snapshot.selectedPackage)
        }
        _snapshot.value = snapshot
        clientSettings.cacheTranslation(serverUrl, userId, snapshot)
        _operationError.value = null
    }

    private suspend fun operate(action: suspend () -> Unit) = mutations.withLock {
        _busy.value = true
        _operationError.value = null
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _operationError.value = error
        } finally {
            _busy.value = false
        }
    }
}
