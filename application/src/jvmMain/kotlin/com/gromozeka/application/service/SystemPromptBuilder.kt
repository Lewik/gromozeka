package com.gromozeka.application.service

import klog.KLoggers
import org.springframework.stereotype.Service
import java.io.File
import java.time.LocalDate

/**
 * Builds complete system prompt matching Claude Code prompt structure.
 *
 * Structure:
 * 1. claudeMd section (global + project CLAUDE.md files) - contains agent identity and instructions
 * 2. Environment info (working directory, platform, git status)
 */
@Service
class SystemPromptBuilder {
    private val log = KLoggers.logger(this)

    /**
     * Builds complete system prompt for the AI model.
     *
     * @param projectPath Optional project directory path for loading project-specific CLAUDE.md
     * @return Complete system prompt with instructions and environment context
     */
    fun build(projectPath: String?): String {
        val sections = mutableListOf<String>()

        // 1. claudeMd section - wrapped in <system-reminder> to match Claude Code format
        // This contains agent identity, personality, and instructions from CLAUDE.md files
        buildClaudeMdSection(projectPath)?.let { sections.add(it) }

        // 2. Environment info
        sections.add(buildEnvironmentInfo(projectPath))

        return sections.joinToString("\n\n")
    }

    private fun buildClaudeMdSection(projectPath: String?): String? {
        val parts = mutableListOf<String>()

        // Global CLAUDE.md
        readGlobalClaudeMd()?.let { parts.add(it) }

        // Project CLAUDE.md
        if (projectPath != null) {
            readProjectClaudeMd(projectPath)?.let { parts.add(it) }
        }

        if (parts.isEmpty()) return null

        // Wrap in system-reminder to match Claude Code format
        return """
            <system-reminder>
            ${parts.joinToString("\n\n")}

                  IMPORTANT: this context may or may not be relevant to your tasks. You should not respond to this context unless it is highly relevant to your task.
            </system-reminder>
        """.trimIndent()
    }

    private fun readGlobalClaudeMd(): String? {
        val globalPath = File(System.getProperty("user.home"), ".claude/CLAUDE.md")
        if (!globalPath.exists()) {
            log.debug { "Global CLAUDE.md not found at ${globalPath.absolutePath}" }
            return null
        }

        log.info { "Loading global CLAUDE.md from ${globalPath.absolutePath}" }

        return """
            # claudeMd
            Codebase and user instructions are shown below. Be sure to adhere to these instructions. IMPORTANT: These instructions OVERRIDE any default behavior and you MUST follow them exactly as written.

            Contents of ${globalPath.absolutePath} (user's private global instructions for all projects):

            ${globalPath.readText()}
        """.trimIndent()
    }

    private fun readProjectClaudeMd(projectPath: String): String? {
        val projectFile = File(projectPath, "CLAUDE.md")
        if (!projectFile.exists()) {
            log.debug { "Project CLAUDE.md not found at ${projectFile.absolutePath}" }
            return null
        }

        log.info { "Loading project CLAUDE.md from ${projectFile.absolutePath}" }

        return """
            Contents of ${projectFile.absolutePath} (project instructions, checked into the codebase):

            ${projectFile.readText()}
        """.trimIndent()
    }

    private fun buildEnvironmentInfo(projectPath: String?): String {
        val workingDir = projectPath ?: System.getProperty("user.dir")
        val isGitRepo = File(workingDir, ".git").exists()
        val platform = System.getProperty("os.name")
        val osVersion = System.getProperty("os.version")

        // TODO: Current date - will be moved to last message for long conversations
        val todayDate = LocalDate.now()

        // TODO: Current time - will be added dynamically to last message, not here
        // This allows historical messages to keep their original timestamp
        // while the last message always has "current" time

        return """
            <env>
            Working directory: $workingDir
            Is directory a git repo: ${if (isGitRepo) "Yes" else "No"}
            Platform: $platform
            OS Version: $osVersion
            Today's date: $todayDate
            </env>
        """.trimIndent()
    }

    // TODO: Add git status (optional, expensive operation)
    // private fun buildGitStatus(projectPath: String): String? {
    //     if (!settings.includeGitStatus) return null
    //
    //     val gitStatus = ProcessBuilder("git", "status", "--short")
    //         .directory(File(projectPath))
    //         .redirectErrorStream(true)
    //         .start()
    //         .inputStream.bufferedReader().readText()
    //
    //     return """
    //         gitStatus: This is the git status at the start of the conversation.
    //
    //         $gitStatus
    //     """.trimIndent()
    // }
}
