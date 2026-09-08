package com.gromozeka.domain.service

import com.gromozeka.domain.model.*

interface TelegramManagementService {
    suspend fun snapshot(actor: User): TelegramManagementSnapshot
    suspend fun probe(actor: User, tokenSecretName: String): TelegramBotProfile
    suspend fun save(actor: User, connection: TelegramConnection, expectedRevision: Long): TelegramConnection
    suspend fun profile(actor: User, connectionId: String): TelegramBotProfile
    suspend fun updateProfile(actor: User, connectionId: String, update: TelegramProfileUpdate): TelegramBotProfile
}
