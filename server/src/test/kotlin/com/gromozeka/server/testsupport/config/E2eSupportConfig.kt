package com.gromozeka.server.testsupport.config

import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.AudioController
import com.gromozeka.infrastructure.ai.platform.GlobalHotkeyController
import com.gromozeka.infrastructure.ai.platform.NoOpGlobalHotkeyController
import com.gromozeka.infrastructure.ai.platform.ScreenCaptureController
import com.gromozeka.infrastructure.ai.platform.SystemAudioController
import com.gromozeka.infrastructure.ai.runtime.AiRuntimeBackend
import com.gromozeka.server.testsupport.llm.AiRuntimeCassetteSettings
import com.gromozeka.server.testsupport.llm.CassetteAiRuntimeProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile

@TestConfiguration(proxyBeanMethods = false)
@Profile("e2e")
class E2eSupportConfig {

    @Bean
    @Primary
    fun aiRuntimeProvider(backends: List<AiRuntimeBackend>): AiRuntimeProvider {
        return CassetteAiRuntimeProvider(
            backends = backends,
            settings = AiRuntimeCassetteSettings.fromSystemProperties(),
        )
    }

    @Bean
    @Primary
    fun screenCaptureController(): ScreenCaptureController = object : ScreenCaptureController {
        override suspend fun captureWindow(): String? = null
        override suspend fun captureFullScreen(): String? = null
        override suspend fun captureArea(): String? = null
    }

    @Bean
    @Primary
    fun systemAudioController(): SystemAudioController = object : SystemAudioController {
        override suspend fun mute(): Boolean = true
        override suspend fun unmute(): Boolean = true
        override suspend fun isSystemMuted(): Boolean = false
        override suspend fun setVolume(level: Float): Boolean = true
        override suspend fun getVolume(): Float = 1.0f
    }

    @Bean
    @Primary
    fun audioController(): AudioController = object : AudioController {
        override suspend fun playAudioFile(filePath: String) = Unit
        override suspend fun stopPlayback() = Unit
    }

    @Bean
    @Primary
    fun globalHotkeyController(): GlobalHotkeyController = NoOpGlobalHotkeyController()
}
