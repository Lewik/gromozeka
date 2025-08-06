package com.gromozeka.bot.services

import com.gromozeka.bot.model.Session
import org.springframework.stereotype.Service

@Service
class SessionService(
    private val claudeCodeStreamingWrapper: ClaudeCodeStreamingWrapper,
    private val sessionJsonlService: SessionJsonlService,
    private val soundNotificationService: SoundNotificationService,
    private val settingsService: SettingsService,
) {

    fun createSession(
        projectPath: String,
        claudeModel: String = settingsService.settings.claudeModel,
    ): Session {
        return Session(
            projectPath = projectPath,
            claudeWrapper = claudeCodeStreamingWrapper,
            sessionJsonlService = sessionJsonlService,
            soundNotificationService = soundNotificationService,
            claudeModel = claudeModel,
        )
    }
}