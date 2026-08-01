package com.gromozeka.infrastructure.ai.claude

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class ClaudeCodeCliConfiguration {
    @Bean(destroyMethod = "close")
    fun claudeCodeCliExecutor(): ProcessClaudeCodeCliExecutor =
        ProcessClaudeCodeCliExecutor()

    @Bean
    fun claudeCodeVoiceTranscriptionService(): ClaudeCodeVoiceTranscriptionService =
        ClaudeCodeVoiceTranscriptionService()
}
