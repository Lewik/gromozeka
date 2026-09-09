package com.gromozeka.shared.localization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object TranslationSource {
    val context: JsonObject by lazy { Json.parseToJsonElement(BundledLocalizationData.context()).jsonObject }
    val glossary: JsonElement by lazy { Json.parseToJsonElement(BundledLocalizationData.glossary()) }
    val translatorPrompt: String by lazy { BundledLocalizationData.translatorPrompt() }
}
