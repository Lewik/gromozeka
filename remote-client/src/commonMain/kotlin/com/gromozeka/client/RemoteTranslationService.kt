package com.gromozeka.client

import com.gromozeka.domain.model.TranslationSaveResult
import com.gromozeka.domain.model.TranslationSnapshot
import com.gromozeka.domain.model.User
import com.gromozeka.remote.protocol.DeleteTranslationPackageRequest
import com.gromozeka.remote.protocol.GetTranslationPackageRequest
import com.gromozeka.remote.protocol.GetTranslationsRequest
import com.gromozeka.remote.protocol.RemoteDeclarativeStateResource
import com.gromozeka.remote.protocol.SaveTranslationPackageRequest
import com.gromozeka.remote.protocol.SelectTranslationRequest
import com.gromozeka.remote.protocol.SynchronizeTranslationsRequest
import com.gromozeka.remote.protocol.TranslationPackageResponse
import com.gromozeka.remote.protocol.TranslationSavedResponse
import com.gromozeka.remote.protocol.TranslationsResponse
import com.gromozeka.shared.localization.TranslationPackage
import kotlinx.coroutines.flow.Flow

class RemoteTranslationService internal constructor(
    private val client: GromozekaWsClient,
    private val currentUserId: User.Id,
) {
    suspend fun snapshot(): TranslationSnapshot =
        client.requestTyped<GetTranslationsRequest, TranslationsResponse>(GetTranslationsRequest).snapshot

    suspend fun getPackage(selectionId: String): TranslationPackage =
        client.requestTyped<GetTranslationPackageRequest, TranslationPackageResponse>(GetTranslationPackageRequest(selectionId)).translation

    suspend fun savePackage(json: String, expectedRevision: Long, packageId: String? = null): TranslationSaveResult =
        client.requestTyped<SaveTranslationPackageRequest, TranslationSavedResponse>(
            SaveTranslationPackageRequest(json, expectedRevision, packageId)
        ).result

    suspend fun deletePackage(selectionId: String, expectedRevision: Long): TranslationSnapshot =
        client.requestTyped<DeleteTranslationPackageRequest, TranslationsResponse>(
            DeleteTranslationPackageRequest(selectionId, expectedRevision)
        ).snapshot

    suspend fun select(selectionId: String, expectedRevision: Long): TranslationSnapshot =
        client.requestTyped<SelectTranslationRequest, TranslationsResponse>(
            SelectTranslationRequest(selectionId, expectedRevision)
        ).snapshot

    suspend fun setSynchronizeClients(synchronizeClients: Boolean, expectedRevision: Long): TranslationSnapshot =
        client.requestTyped<SynchronizeTranslationsRequest, TranslationsResponse>(
            SynchronizeTranslationsRequest(synchronizeClients, expectedRevision)
        ).snapshot

    fun observe(): Flow<TranslationSnapshot> = client.observeDeclarativeState(
        RemoteDeclarativeStateResource.TRANSLATIONS, currentUserId.value, ::snapshot,
    )
}
