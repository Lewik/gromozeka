package com.gromozeka.domain.model

import com.gromozeka.shared.localization.BundledTranslations
import com.gromozeka.shared.localization.TranslationDirection
import com.gromozeka.shared.localization.TranslationPackage
import kotlinx.serialization.Serializable

@Serializable
data class TranslationState(
    val revision: Long = 0,
    val personalPackages: Map<String, TranslationPackage> = emptyMap(),
    val commonSelectionId: String = DEFAULT_SELECTION_ID,
    val synchronizeClients: Boolean = true,
    val clientSelections: Map<String, String> = emptyMap(),
) {
    init {
        require(revision >= 0) { "Translation state revision must not be negative" }
        require(personalPackages.keys.all { PERSONAL_ID_PATTERN.matches(it) }) {
            "Personal translation IDs must use personal:<UUID>"
        }
        require(clientSelections.keys.none { it.isBlank() }) { "Client IDs must not be blank" }
        val availableIds = personalPackages.keys + BundledTranslations.locales.map { "builtin:$it" }
        require(commonSelectionId in availableIds) {
            "Common translation selection does not identify an available package: $commonSelectionId"
        }
        require(clientSelections.values.all { it in availableIds }) {
            "Client translation selections must identify available packages"
        }
        require(!synchronizeClients || clientSelections.isEmpty()) {
            "Synchronized translation state must not contain per-client selections"
        }
    }

    fun effectiveSelectionId(clientInstanceId: String?): String =
        if (synchronizeClients || clientInstanceId == null) commonSelectionId
        else clientSelections[clientInstanceId] ?: commonSelectionId

    companion object {
        const val DEFAULT_SELECTION_ID = "builtin:en"
        private val PERSONAL_ID_PATTERN = Regex(
            "personal:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
    }
}

@Serializable
data class TranslationMetadata(
    val id: String,
    val locale: String,
    val name: String,
    val direction: TranslationDirection,
    val builtin: Boolean,
)

@Serializable
data class TranslationSnapshot(
    val revision: Long,
    val available: List<TranslationMetadata>,
    val synchronizeClients: Boolean,
    val commonSelectionId: String,
    val effectiveSelectionId: String,
    val selectedPackage: TranslationPackage,
    val clientSelections: Map<String, String>,
)

@Serializable
data class TranslationSaveResult(
    val packageId: String,
    val snapshot: TranslationSnapshot,
)
