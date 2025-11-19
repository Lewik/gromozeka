package com.gromozeka.domain.service

import java.io.File

interface AudioController {
    suspend fun playAudioFile(file: File)
    suspend fun stopPlayback()
}
