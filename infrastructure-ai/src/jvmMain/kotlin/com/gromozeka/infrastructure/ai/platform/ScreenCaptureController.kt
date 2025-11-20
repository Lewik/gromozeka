package com.gromozeka.infrastructure.ai.platform

interface ScreenCaptureController {

    suspend fun captureWindow(): String?

    suspend fun captureFullScreen(): String?

    suspend fun captureArea(): String?
}