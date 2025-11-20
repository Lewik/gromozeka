package com.gromozeka.presentation.services.theming.data

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@JvmInline
@Serializable(with = HexColorSerializer::class)
value class HexColor(val hex: String) {
    init {
        require(isValidHex(hex)) { "Invalid hex color format: $hex. Expected format: #RRGGBB" }
    }

    fun toComposeColor(): Color {
        // Parse hex color manually for desktop compatibility
        val colorValue = hex.removePrefix("#").toLong(16)
        return Color(0xFF000000 or colorValue)
    }

    companion object {
        fun fromLong(long: Long): HexColor {
            val hex = "#${(long and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()}"
            return HexColor(hex)
        }

        fun fromComposeColor(color: Color): HexColor {
            val argb = color.value.toLong()
            return fromLong(argb)
        }

        private fun isValidHex(hex: String): Boolean {
            return hex.matches(Regex("#[0-9A-Fa-f]{6}"))
        }

        // Predefined colors for convenience
        val BLACK = HexColor("#000000")
        val WHITE = HexColor("#FFFFFF")
        val TRANSPARENT = HexColor("#000000") // Will be handled specially in UI
    }

    override fun toString(): String = hex
}

@Serializer(forClass = HexColor::class)
object HexColorSerializer : KSerializer<HexColor> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("HexColor", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: HexColor) {
        encoder.encodeString(value.hex)
    }

    override fun deserialize(decoder: Decoder): HexColor {
        val hexString = decoder.decodeString()
        return HexColor(hexString)
    }
}