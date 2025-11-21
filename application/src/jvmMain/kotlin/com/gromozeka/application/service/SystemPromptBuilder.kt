package com.gromozeka.application.service

import klog.KLoggers
import org.springframework.stereotype.Service
import java.io.File
import java.time.LocalDate

/**
 * Builds environment information for Dynamic prompts.
 * 
 * Used exclusively by PromptApplicationService for Dynamic.Environment prompts.
 * All other system prompt assembly happens through agent.prompts.
 */
@Service
class SystemPromptBuilder {
    private val log = KLoggers.logger(this)

    /**
     * Generates environment information block.
     * 
     * @param projectPath Optional project directory path for working directory
     * @return Environment info block wrapped in <env> tag
     */
    fun buildEnvironmentInfo(projectPath: String): String {
        val isGitRepo = File(projectPath, ".git").exists()
        val platform = System.getProperty("os.name")
        val osVersion = System.getProperty("os.version")
        val todayDate = LocalDate.now()

        return """
            <env>
            Project path: $projectPath
            Is directory a git repo: ${if (isGitRepo) "Yes" else "No"}
            Platform: $platform
            OS Version: $osVersion
            Today's date: $todayDate
            </env>
        """.trimIndent()
    }
}
