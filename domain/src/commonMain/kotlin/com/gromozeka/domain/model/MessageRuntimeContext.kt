package com.gromozeka.domain.model

import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class MessageInputContext(
    val modality: Modality,
    val source: Source,
    val clientPlatform: ClientPlatform,
    val delivery: Delivery = Delivery(QueuedMessagePlacement.END_OF_TURN),
    val reliability: Reliability = Reliability.NORMAL,
) {
    @Serializable
    enum class Modality {
        TEXT,
        SPEECH_TO_TEXT,
    }

    @Serializable
    enum class Source {
        CHAT_INPUT,
        PUSH_TO_TALK,
        ACTION_BUTTON,
    }

    @Serializable
    enum class ClientPlatform {
        DESKTOP,
        ANDROID,
        IOS,
        WEB_DESKTOP,
        WEB_TOUCH,
        UNKNOWN,
    }

    @Serializable
    enum class Reliability {
        NORMAL,
        MAY_CONTAIN_RECOGNITION_ERRORS,
    }

    @Serializable
    data class Delivery(
        val placement: QueuedMessagePlacement,
    ) {
        val explanation: String
            get() = when (placement) {
                QueuedMessagePlacement.AFTER_TOOL_RESULT ->
                    "deliver at the next safe boundary after a tool result; never between a tool call and its tool result"
                QueuedMessagePlacement.END_OF_TURN ->
                    "deliver after the current assistant turn finishes"
            }
    }

    fun toXml(): String =
        """
        <message_input_context>
          <modality>${modality.xmlValue()}</modality>
          <source>${source.xmlValue()}</source>
          <client_platform>${clientPlatform.xmlValue()}</client_platform>
          <input_reliability>${reliability.xmlValue()}</input_reliability>
          <delivery placement="${delivery.placement.xmlValue()}">${delivery.explanation.escapeXml()}</delivery>
        </message_input_context>
        """.trimIndent()
}

@Serializable
data class UserSituationContext(
    val observedAt: Instant? = null,
    val timezone: String? = null,
    val deviceScreenVisibility: DeviceScreenVisibility = DeviceScreenVisibility.UNKNOWN,
    val appScreenVisibility: AppScreenVisibility = AppScreenVisibility.UNKNOWN,
    val audioOutput: AudioOutput = AudioOutput.UNKNOWN,
) {
    @Serializable
    enum class DeviceScreenVisibility {
        VISIBLE,
        LOCKED,
        UNKNOWN,
    }

    @Serializable
    enum class AppScreenVisibility {
        VISIBLE,
        BACKGROUND,
        NOT_VISIBLE,
        UNKNOWN,
    }

    @Serializable
    enum class AudioOutput {
        AVAILABLE,
        UNAVAILABLE,
        UNKNOWN,
    }

    fun toXml(): String =
        buildString {
            appendLine("<user_situation_context>")
            observedAt?.let { appendLine("  <observed_at>${it.toString().escapeXml()}</observed_at>") }
            timezone?.takeIf { it.isNotBlank() }?.let { appendLine("  <timezone>${it.escapeXml()}</timezone>") }
            appendLine("  <device_screen_visibility>${deviceScreenVisibility.xmlValue()}</device_screen_visibility>")
            appendLine("  <app_screen_visibility>${appScreenVisibility.xmlValue()}</app_screen_visibility>")
            appendLine("  <audio_output>${audioOutput.xmlValue()}</audio_output>")
            append("</user_situation_context>")
        }
}

internal fun String.escapeXml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

private fun Enum<*>.xmlValue(): String = name.lowercase()
