package com.gromozeka.shared.localization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

@Serializable
enum class TranslationDirection { LTR, RTL }

@Serializable
enum class PluralCategory {
    zero, one, two, few, many, other,
}

@Serializable
data class TranslationPackage(
    val schemaVersion: Int = 1,
    val locale: String,
    val name: String,
    val direction: TranslationDirection,
    val messages: Map<String, TranslationMessage>,
)

@Serializable(with = TranslationMessageSerializer::class)
sealed interface TranslationMessage {
    data class Text(val value: String) : TranslationMessage
    data class Plural(val forms: Map<PluralCategory, String>) : TranslationMessage
}

object TranslationMessageSerializer : KSerializer<TranslationMessage> {
    override val descriptor: SerialDescriptor = SerializedTranslationMessage.serializer().descriptor

    override fun deserialize(decoder: Decoder): TranslationMessage {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeSerializableValue(
            SerializedTranslationMessage.serializer()
        ).let { value ->
            if ((value.text == null) == (value.forms == null)) {
                throw SerializationException("Expected exactly one translation message representation")
            }
            value.text?.let(TranslationMessage::Text) ?: TranslationMessage.Plural(requireNotNull(value.forms))
        }
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> TranslationMessage.Text(element.translationString())
            is JsonObject -> TranslationMessage.Plural(element.map { (category, value) ->
                val pluralCategory = PluralCategory.entries.firstOrNull { it.name == category }
                    ?: throw SerializationException("Unknown plural category: $category")
                pluralCategory to value.translationString()
            }.toMap())
            else -> throw SerializationException("A translation message must be text or plural forms")
        }
    }

    override fun serialize(encoder: Encoder, value: TranslationMessage) {
        val jsonEncoder = encoder as? JsonEncoder ?: return encoder.encodeSerializableValue(
            SerializedTranslationMessage.serializer(),
            when (value) {
                is TranslationMessage.Text -> SerializedTranslationMessage(text = value.value)
                is TranslationMessage.Plural -> SerializedTranslationMessage(forms = value.forms)
            },
        )
        jsonEncoder.encodeJsonElement(when (value) {
            is TranslationMessage.Text -> JsonPrimitive(value.value)
            is TranslationMessage.Plural -> JsonObject(value.forms.mapKeys { it.key.name }.mapValues { JsonPrimitive(it.value) })
        })
    }

    private fun JsonElement.translationString(): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SerializationException("Translation values must be strings")
}

@Serializable
private data class SerializedTranslationMessage(
    val text: String? = null,
    val forms: Map<PluralCategory, String>? = null,
)

object TranslationJson {
    val format = Json {
        encodeDefaults = true
        prettyPrint = true
        ignoreUnknownKeys = false
    }

    fun decode(value: String): TranslationPackage {
        val element = format.parseToJsonElement(value) as? JsonObject
            ?: throw SerializationException("A translation package must be an object")
        rejectDuplicateKeys(value)
        if (element.keys != setOf("schemaVersion", "locale", "name", "direction", "messages")) {
            throw SerializationException("Expected schemaVersion, locale, name, direction and messages")
        }
        val version = element["schemaVersion"] as? JsonPrimitive
        if (version == null || version.isString || version.intOrNull == null ||
            !Regex("-?(0|[1-9][0-9]*)").matches(version.content)
        ) {
            throw SerializationException("schemaVersion must be a JSON integer")
        }
        for (field in listOf("locale", "name", "direction")) {
            if ((element[field] as? JsonPrimitive)?.isString != true) {
                throw SerializationException("$field must be a JSON string")
            }
        }
        if (element["messages"] !is JsonObject) {
            throw SerializationException("messages must be a JSON object")
        }
        return format.decodeFromJsonElement(element)
    }

    fun encode(value: TranslationPackage): String = format.encodeToString(value)

    private fun rejectDuplicateKeys(value: String) {
        val containers = mutableListOf<MutableSet<String>?>()
        var index = 0
        while (index < value.length) {
            when (value[index]) {
                '{' -> containers.add(mutableSetOf())
                '[' -> containers.add(null)
                '}', ']' -> containers.removeAt(containers.lastIndex)
                '"' -> {
                    val start = index++
                    while (value[index] != '"') {
                        if (value[index] == '\\') index++
                        index++
                    }
                    var next = index + 1
                    while (next < value.length && value[next].isWhitespace()) next++
                    if (next < value.length && value[next] == ':') {
                        val key = format.decodeFromString<String>(value.substring(start, index + 1))
                        if (containers.last()?.add(key) == false) {
                            throw SerializationException("Duplicate JSON key: $key")
                        }
                    }
                }
            }
            index++
        }
    }
}

object BundledTranslations {
    val locales: List<String> = BundledLocalizationData.locales.sortedWith(
        compareBy<String> { when (it) { "en" -> 0; "ru" -> 1; "he" -> 2; else -> 3 } }.thenBy { it }
    )
    private val packages by lazy {
        locales.associateWith { TranslationJson.decode(BundledLocalizationData.catalog(it)) }
    }

    val english: TranslationPackage get() = get("en")

    fun get(locale: String): TranslationPackage = packages.getValue(locale)

    fun matchLocale(locale: String): String {
        val normalized = locale.replace('_', '-').lowercase()
        locales.firstOrNull { it.lowercase() == normalized }?.let { return it }
        val language = normalized.substringBefore('-')
        if (language == "zh") {
            return if (normalized.split('-').any { it in setOf("hant", "tw", "hk", "mo") }) "zh-Hant" else "zh-Hans"
        }
        if (language == "iw") return "he"
        return locales.firstOrNull { it.substringBefore('-').lowercase() == language } ?: "en"
    }
}
