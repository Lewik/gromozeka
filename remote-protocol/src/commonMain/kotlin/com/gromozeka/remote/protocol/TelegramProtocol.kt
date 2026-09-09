package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable @SerialName("telegram_settings_get")
data object GetTelegramSettingsRequest : ClientRequest

@Serializable @SerialName("telegram_bot_probe")
data class ProbeTelegramBotRequest(val tokenSecretName: String) : ClientRequest

@Serializable @SerialName("telegram_connection_save")
data class SaveTelegramConnectionRequest(val connection: TelegramConnection, val expectedRevision: Long) : ClientRequest

@Serializable @SerialName("telegram_profile_get")
data class GetTelegramProfileRequest(val connectionId: String) : ClientRequest

@Serializable @SerialName("telegram_profile_update")
data class UpdateTelegramProfileRequest(val connectionId: String, val update: TelegramProfileUpdate) : ClientRequest

@Serializable @SerialName("telegram_settings")
data class TelegramSettingsResponse(val snapshot: TelegramManagementSnapshot) : ServerResponse

@Serializable @SerialName("telegram_connection")
data class TelegramConnectionResponse(val connection: TelegramConnection) : ServerResponse

@Serializable @SerialName("telegram_profile")
data class TelegramProfileResponse(val profile: TelegramBotProfile) : ServerResponse
