package com.gromozeka.presentation.services.translation

import com.gromozeka.domain.model.SpeechAvailabilityException
import com.gromozeka.presentation.services.translation.data.Translation
import kotlinx.serialization.Serializable

@Serializable
sealed interface LocalizedText {
    @Serializable
    data class Resource(
        val key: String,
        val arguments: Map<String, LocalizedText> = emptyMap(),
        val count: Long? = null,
    ) : LocalizedText

    @Serializable
    data class Literal(val value: String) : LocalizedText

    @Serializable
    data class Joined(val items: List<LocalizedText>, val separator: String = ", ") : LocalizedText

    fun resolve(translation: Translation): String = when (this) {
        is Literal -> value
        is Joined -> items.joinToString(separator) { it.resolve(translation) }
        is Resource -> {
            val resolved = arguments.map { (name, value) -> name to value.resolve(translation) }.toTypedArray()
            if (count == null) translation.text(key, *resolved) else translation.plural(key, count, *resolved)
        }
    }
}

fun localizedText(key: String, vararg arguments: Pair<String, Any?>): LocalizedText.Resource =
    LocalizedText.Resource(key, arguments.associate { (name, value) -> name to value.asLocalizedText() })

fun localizedPlural(key: String, count: Long, vararg arguments: Pair<String, Any?>): LocalizedText.Resource =
    localizedText(key, *arguments).copy(count = count)

private fun Any?.asLocalizedText(): LocalizedText =
    this as? LocalizedText ?: LocalizedText.Literal(toString())

class LocalizedTextException(val text: LocalizedText, cause: Throwable? = null) : IllegalStateException(null, cause)

fun Throwable.localizedText(fallbackKey: String = "common.unknownError"): LocalizedText =
    when (this) {
        is LocalizedTextException -> text
        is SpeechAvailabilityException -> failure.localizedText()
        else -> message?.let(LocalizedText::Literal) ?: localizedText(fallbackKey)
    }
