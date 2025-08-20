package com.gromozeka.bot.platform

interface ScreenCaptureController {

    suspend fun captureWindow(): String?

    suspend fun captureFullScreen(): String?

    suspend fun captureArea(): String?
}