package com.gromozeka.client

import com.gromozeka.domain.model.*
import com.gromozeka.remote.protocol.*

class RemoteTelegramService internal constructor(private val client: GromozekaWsClient, val userId: User.Id) {
    suspend fun snapshot(): TelegramManagementSnapshot =
        client.requestTyped<GetTelegramSettingsRequest, TelegramSettingsResponse>(GetTelegramSettingsRequest).snapshot

    suspend fun probe(tokenSecretName: String): TelegramBotProfile =
        client.requestTyped<ProbeTelegramBotRequest, TelegramProfileResponse>(ProbeTelegramBotRequest(tokenSecretName)).profile

    suspend fun save(connection: TelegramConnection): TelegramConnection =
        client.requestTyped<SaveTelegramConnectionRequest, TelegramConnectionResponse>(SaveTelegramConnectionRequest(connection, connection.revision)).connection

    suspend fun profile(connectionId: String): TelegramBotProfile =
        client.requestTyped<GetTelegramProfileRequest, TelegramProfileResponse>(GetTelegramProfileRequest(connectionId)).profile

    suspend fun updateProfile(connectionId: String, update: TelegramProfileUpdate): TelegramBotProfile =
        client.requestTyped<UpdateTelegramProfileRequest, TelegramProfileResponse>(UpdateTelegramProfileRequest(connectionId, update)).profile
}
