package com.gromozeka.domain.repository

import com.gromozeka.domain.model.TelegramBotState

interface TelegramChannelRepository {
    suspend fun find(connectionId: String): TelegramBotState?
    suspend fun openExclusiveSession(connectionId: String): TelegramBotSession?
}

interface TelegramBotSession {
    suspend fun verifyLease()
    suspend fun load(): TelegramBotState
    suspend fun save(state: TelegramBotState)
    suspend fun close()
}
