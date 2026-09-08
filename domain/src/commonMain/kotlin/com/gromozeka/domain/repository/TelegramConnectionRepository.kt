package com.gromozeka.domain.repository

import com.gromozeka.domain.model.TelegramConnection

interface TelegramConnectionRepository {
    suspend fun list(): List<TelegramConnection>
    suspend fun find(id: String): TelegramConnection?
    suspend fun save(connection: TelegramConnection, expectedRevision: Long): TelegramConnection
}
