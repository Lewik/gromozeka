package com.gromozeka.bot.config

import com.gromozeka.bot.services.SettingsService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import kotlin.io.path.Path

@Configuration
class AppConfig {

    @Bean
    fun appDataPath(settingsService: SettingsService): Path {
        return settingsService.gromozekaHome.toPath()
    }
}
