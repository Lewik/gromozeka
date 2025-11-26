package com.gromozeka.infrastructure.ai.platform

import com.gromozeka.domain.service.AudioController

interface AudioPlayerController : AudioController {

    override suspend fun playAudioFile(filePath: String)

    override suspend fun stopPlayback()

    fun isPlaying(): Boolean
}