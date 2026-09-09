package com.gromozeka.application.service

import com.gromozeka.domain.model.TranslationMetadata
import com.gromozeka.domain.model.TranslationSaveResult
import com.gromozeka.domain.model.TranslationSnapshot
import com.gromozeka.domain.model.TranslationState
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.TranslationRepository
import com.gromozeka.domain.service.DeclarativeStateInvalidator
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.domain.service.TranslationRevisionConflictException
import com.gromozeka.domain.service.UserTranslationService
import com.gromozeka.shared.localization.BundledTranslations
import com.gromozeka.shared.localization.TranslationPackage
import com.gromozeka.shared.localization.TranslationValidator
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service

@Service
class TranslationApplicationService(
    private val repository: TranslationRepository,
    private val stateInvalidator: DeclarativeStateInvalidator,
) : UserTranslationService {
    private val mutex = Mutex()
    private val builtinPackages = BundledTranslations.locales.associate { locale ->
        "builtin:$locale" to BundledTranslations.get(locale)
    }
    private val builtinMetadata = builtinPackages.map { (id, translation) -> translation.metadata(id, true) }

    override suspend fun snapshot(userId: User.Id, clientInstanceId: String?): TranslationSnapshot {
        validateClientId(clientInstanceId)
        return repository.load(userId).snapshot(clientInstanceId)
    }

    override suspend fun getPackage(userId: User.Id, selectionId: String): TranslationPackage {
        validateSelectionId(selectionId)
        return repository.load(userId).resolvePackage(selectionId)
    }

    override suspend fun savePackage(
        userId: User.Id,
        translation: TranslationPackage,
        expectedRevision: Long,
        packageId: String?,
        clientInstanceId: String?,
    ): TranslationSaveResult {
        TranslationValidator.requireValid(translation)
        val savedId = packageId ?: "personal:${uuid7()}"
        val snapshot = mutate(userId, clientInstanceId, expectedRevision) { current ->
            if (packageId == null) {
                check(savedId !in current.personalPackages) { "Generated personal translation ID already exists" }
            } else {
                current.requirePersonalPackage(packageId)
            }
            current.copy(personalPackages = current.personalPackages + (savedId to translation))
        }
        return TranslationSaveResult(savedId, snapshot)
    }

    override suspend fun deletePackage(
        userId: User.Id,
        selectionId: String,
        expectedRevision: Long,
        clientInstanceId: String?,
    ): TranslationSnapshot = mutate(userId, clientInstanceId, expectedRevision) { current ->
        current.requirePersonalPackage(selectionId)
        current.copy(
            personalPackages = current.personalPackages - selectionId,
            commonSelectionId = current.commonSelectionId.replacingDeleted(selectionId),
            clientSelections = current.clientSelections.mapValues { (_, selectedId) ->
                selectedId.replacingDeleted(selectionId)
            },
        )
    }

    override suspend fun select(
        userId: User.Id,
        selectionId: String,
        expectedRevision: Long,
        clientInstanceId: String?,
    ): TranslationSnapshot = mutate(userId, clientInstanceId, expectedRevision) { current ->
        validateSelectionId(selectionId)
        current.resolvePackage(selectionId)
        if (current.synchronizeClients) {
            current.copy(commonSelectionId = selectionId)
        } else {
            requireNotNull(clientInstanceId) { "Specify one of your client IDs" }
            current.copy(clientSelections = current.clientSelections + (clientInstanceId to selectionId))
        }
    }

    override suspend fun setSynchronizeClients(
        userId: User.Id,
        synchronizeClients: Boolean,
        expectedRevision: Long,
        clientInstanceId: String?,
    ): TranslationSnapshot = mutate(userId, clientInstanceId, expectedRevision) { current ->
        if (current.synchronizeClients == synchronizeClients) {
            current
        } else {
            require(!synchronizeClients || clientInstanceId != null || current.clientSelections.isEmpty()) {
                "Specify one of your client IDs before synchronizing per-client translation choices"
            }
            current.copy(
                commonSelectionId = current.effectiveSelectionId(clientInstanceId),
                synchronizeClients = synchronizeClients,
                clientSelections = emptyMap(),
            )
        }
    }

    private suspend fun mutate(
        userId: User.Id,
        clientInstanceId: String?,
        expectedRevision: Long,
        transform: (TranslationState) -> TranslationState,
    ): TranslationSnapshot {
        validateClientId(clientInstanceId)
        require(expectedRevision >= 0) { "Expected translation revision must not be negative" }
        return mutex.withLock {
            val current = repository.load(userId)
            if (current.revision != expectedRevision) {
                throw TranslationRevisionConflictException(expectedRevision, current.revision)
            }
            check(current.revision < Long.MAX_VALUE) { "Translation state revision is exhausted" }
            val updated = transform(current).copy(revision = current.revision + 1)
            val saved = repository.save(userId, updated, expectedRevision)
            stateInvalidator.invalidate(DeclarativeStateKey.translations(userId))
            saved.snapshot(clientInstanceId)
        }
    }

    private fun TranslationState.snapshot(clientInstanceId: String?): TranslationSnapshot {
        val effectiveId = effectiveSelectionId(clientInstanceId)
        return TranslationSnapshot(
            revision = revision,
            available = builtinMetadata + personalPackages.map { (id, translation) ->
                translation.metadata(id, false)
            }.sortedWith(compareBy({ it.name.lowercase() }, { it.id })),
            synchronizeClients = synchronizeClients,
            commonSelectionId = commonSelectionId,
            effectiveSelectionId = effectiveId,
            selectedPackage = resolvePackage(effectiveId),
            clientSelections = clientSelections.toSortedMap(),
        )
    }

    private fun TranslationState.resolvePackage(selectionId: String): TranslationPackage =
        builtinPackages[selectionId] ?: personalPackages[selectionId]
        ?: throw IllegalArgumentException("Translation package not found: $selectionId")

    private fun TranslationState.requirePersonalPackage(selectionId: String) {
        validateSelectionId(selectionId)
        require(!selectionId.startsWith("builtin:")) { "Bundled translations cannot be modified or deleted" }
        require(selectionId in personalPackages) { "Personal translation package not found: $selectionId" }
    }

    private fun TranslationPackage.metadata(id: String, builtin: Boolean) = TranslationMetadata(
        id = id,
        locale = locale,
        name = name,
        direction = direction,
        builtin = builtin,
    )

    private fun String.replacingDeleted(deletedId: String): String =
        if (this == deletedId) TranslationState.DEFAULT_SELECTION_ID else this

    private fun validateClientId(clientInstanceId: String?) {
        require(clientInstanceId == null || clientInstanceId.isNotBlank()) { "Client ID must not be blank" }
    }

    private fun validateSelectionId(selectionId: String) {
        require(selectionId.isNotBlank()) { "Translation selection ID must not be blank" }
    }
}
