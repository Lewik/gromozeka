package com.gromozeka.presentation.services.translation.data

import com.gromozeka.shared.localization.BundledTranslations

class EnglishTranslation : Translation(BundledTranslations.get(LANGUAGE_CODE)) {
    companion object {
        const val LANGUAGE_CODE = "en"
    }
}
