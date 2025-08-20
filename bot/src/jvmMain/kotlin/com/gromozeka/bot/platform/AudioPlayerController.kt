package com.gromozeka.bot.platform

import java.io.File

interface AudioPlayerController {

    suspend fun playAudioFile(audioFile: File)

    suspend fun stopPlayback()

    fun isPlaying(): Boolean
}