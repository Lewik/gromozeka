package com.gromozeka.infrastructure.ai.claude

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class ClaudeCodeCliConfiguration {
    @Bean(destroyMethod = "close")
    fun claudeCodeCliExecutor(admission: com.gromozeka.domain.service.AiCallAdmission): ProcessClaudeCodeCliExecutor =
        ProcessClaudeCodeCliExecutor(admission = admission)

    @Bean
    fun claudeCodeVoiceTranscriptionService(): ClaudeCodeVoiceTranscriptionService =
        ClaudeCodeVoiceTranscriptionService()
}
