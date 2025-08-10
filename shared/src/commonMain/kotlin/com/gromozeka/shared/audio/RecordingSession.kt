package com.gromozeka.shared.audio

import kotlinx.coroutines.flow.Flow

interface RecordingSession {
    val audioChunks: Flow<ByteArray>
    suspend fun stop(): ByteArray
    fun cancel()
}