package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.TranslationSaveResult
import com.gromozeka.domain.model.TranslationSnapshot
import com.gromozeka.shared.localization.TranslationPackage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("get_translations")
data object GetTranslationsRequest : ClientRequest

@Serializable
@SerialName("get_translation_package")
data class GetTranslationPackageRequest(val selectionId: String) : ClientRequest

@Serializable
@SerialName("save_translation_package")
data class SaveTranslationPackageRequest(
    val json: String,
    val expectedRevision: Long,
    val packageId: String? = null,
) : ClientRequest

@Serializable
@SerialName("delete_translation_package")
data class DeleteTranslationPackageRequest(val selectionId: String, val expectedRevision: Long) : ClientRequest

@Serializable
@SerialName("select_translation")
data class SelectTranslationRequest(val selectionId: String, val expectedRevision: Long) : ClientRequest

@Serializable
@SerialName("synchronize_translations")
data class SynchronizeTranslationsRequest(val synchronizeClients: Boolean, val expectedRevision: Long) : ClientRequest

@Serializable
@SerialName("translations")
data class TranslationsResponse(val snapshot: TranslationSnapshot) : ServerResponse

@Serializable
@SerialName("translation_package")
data class TranslationPackageResponse(val translation: TranslationPackage) : ServerResponse

@Serializable
@SerialName("translation_saved")
data class TranslationSavedResponse(val result: TranslationSaveResult) : ServerResponse
