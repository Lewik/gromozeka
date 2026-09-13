package com.gromozeka.domain.model

import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable

@Serializable
data class BinaryContent(val base64: String) {
    fun bytes(): ByteArray = Base64.Default.decode(base64)

    fun utf8TextOrNull(): String? = try {
        bytes().decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }

    fun textPreview(): String = utf8TextOrNull()?.safeToolOutputText()
        ?: "[Cannot decode this output as UTF-8. It may contain binary data, use another encoding, or be a fragment of a multibyte character. Save the original output to inspect it.]"

    companion object {
        fun fromBytes(bytes: ByteArray): BinaryContent = BinaryContent(Base64.Default.encode(bytes))

        fun fromText(text: String): BinaryContent = fromBytes(text.encodeToByteArray())

        val EMPTY = fromBytes(byteArrayOf())
    }
}

fun String.safeToolOutputText(): String = buildString(length) {
    this@safeToolOutputText.forEach { character ->
        if ((character.code < 32 && character !in "\n\r\t") || character.code in 127..159) {
            append("\\u")
            append(character.code.toString(16).padStart(4, '0'))
        } else {
            append(character)
        }
    }
}
