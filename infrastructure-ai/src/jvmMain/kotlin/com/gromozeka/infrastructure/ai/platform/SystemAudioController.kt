package com.gromozeka.infrastructure.ai.platform

interface SystemAudioController {

    suspend fun mute(): Boolean

    suspend fun unmute(): Boolean

    suspend fun isSystemMuted(): Boolean

    suspend fun setVolume(level: Float): Boolean

    suspend fun getVolume(): Float?
}