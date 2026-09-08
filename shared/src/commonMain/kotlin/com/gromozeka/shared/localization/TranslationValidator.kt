package com.gromozeka.shared.localization

import kotlinx.serialization.Serializable

@Serializable
data class TranslationValidationIssue(val key: String?, val code: String, val detail: String)

object TranslationValidator {
    private val localePattern = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
    private val bidiControls = Regex("[\u202a-\u202e\u2066-\u2069]")

    fun validate(candidate: TranslationPackage, source: TranslationPackage = BundledTranslations.english): List<TranslationValidationIssue> = buildList {
        fun issue(key: String?, code: String, detail: String) {
            add(TranslationValidationIssue(key, code, detail))
        }
        if (candidate.schemaVersion != 1) issue(null, "schema_version", "Expected schemaVersion 1")
        if (!localePattern.matches(candidate.locale)) issue(null, "locale", "Expected a BCP 47 language tag")
        if (candidate.name.isBlank()) issue(null, "name", "Expected a non-empty language name")
        if (bidiControls.containsMatchIn(candidate.name)) issue(null, "name", "Language names must not contain directional controls")
        (source.messages.keys - candidate.messages.keys).forEach { issue(it, "missing_message", "Required message is missing") }
        (candidate.messages.keys - source.messages.keys).forEach { issue(it, "unknown_message", "Message does not exist in the source catalog") }
        candidate.messages.forEach { (key, translated) ->
            val original = source.messages[key] ?: return@forEach
            val originalTemplate = when (original) {
                is TranslationMessage.Text -> original.value
                is TranslationMessage.Plural -> original.forms.getValue(PluralCategory.other)
            }
            if ((original is TranslationMessage.Plural) != (translated is TranslationMessage.Plural)) {
                issue(key, "message_shape", "Text and plural message shapes must match the source")
                return@forEach
            }
            val variants = when (translated) {
                is TranslationMessage.Text -> mapOf<PluralCategory?, String>(null to translated.value)
                is TranslationMessage.Plural -> {
                    val expected = CardinalPluralRules.categories(candidate.locale)
                    if (translated.forms.keys != expected) issue(key, "plural_categories", "Expected categories: ${expected.joinToString()}")
                    translated.forms
                }
            }
            val signature = TranslationFormatter.signature(originalTemplate)
            variants.forEach { (category, text) ->
                if (text.isBlank()) issue(key, "empty_message", "Translation must not be empty")
                if (bidiControls.containsMatchIn(text)) issue(key, "directional_controls", "Directional controls are inserted by the renderer")
                val actual = runCatching { TranslationFormatter.signature(text) }.getOrElse {
                    issue(key, "arguments", it.message.orEmpty())
                    return@forEach
                }
                val implicitCount = category != null && CardinalPluralRules.allowsImplicitCount(candidate.locale, category)
                val allowed = signature == actual || implicitCount && signature.copy(named = signature.named - "count") == actual
                if (!allowed) issue(key, "arguments", "Argument names, occurrences, types and precision must match the source${category?.let { " ($it)" }.orEmpty()}")
            }
        }
    }

    fun requireValid(candidate: TranslationPackage): TranslationPackage = candidate.also {
        val issues = validate(it)
        require(issues.isEmpty()) { issues.joinToString("\n") { issue -> "${issue.key ?: "package"}: ${issue.detail}" } }
    }
}
