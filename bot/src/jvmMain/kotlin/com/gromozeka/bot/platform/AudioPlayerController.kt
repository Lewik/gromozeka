package com.gromozeka.bot.platform

import java.io.File

import com.gromozeka.domain.service.AudioController

interface AudioPlayerController : AudioController {

    override suspend fun playAudioFile(audioFile: File)

    override suspend fun stopPlayback()

    fun isPlaying(): Boolean
}