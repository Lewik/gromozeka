package com.gromozeka.bot.services

import klog.KLoggers
import java.io.File

object ClaudePathDetector {
    private val log = KLoggers.logger(this)

    fun detectClaudePath(): String {
        log.info { "Attempting to auto-detect Claude CLI installation path..." }

        // Strategy 1: Try to find via login shell (works for most installations)
        try {
            val process = ProcessBuilder("/bin/zsh", "-l", "-c", "command -v claude")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()

            val result = process.inputStream.bufferedReader().readText().trim()
            if (result.isNotEmpty() && File(result).exists()) {
                log.info { "Found Claude CLI via zsh login shell: $result" }
                return result
            }
        } catch (e: Exception) {
            log.debug { "Failed to find Claude via zsh: ${e.message}" }
        }

        // Strategy 2: Check common installation paths
        val commonPaths = listOf(
            // NVM installations (various node versions)
            "/Users/${System.getProperty("user.name")}/.nvm/versions/node/v20.10.0/bin/claude",
            "/Users/${System.getProperty("user.name")}/.nvm/versions/node/v22.0.0/bin/claude",
            "/Users/${System.getProperty("user.name")}/.nvm/versions/node/v21.0.0/bin/claude",
            "/Users/${System.getProperty("user.name")}/.nvm/versions/node/v19.0.0/bin/claude",
            "/Users/${System.getProperty("user.name")}/.nvm/versions/node/v18.0.0/bin/claude",

            // Homebrew installations
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",

            // Direct npm global installation
            "/Users/${System.getProperty("user.name")}/.npm-global/bin/claude",
            "/Users/${System.getProperty("user.name")}/node_modules/.bin/claude",

            // System-wide installations
            "/usr/bin/claude",
            "/bin/claude"
        )

        for (path in commonPaths) {
            if (File(path).exists() && File(path).canExecute()) {
                log.info { "Found Claude CLI at common path: $path" }
                return path
            }
        }

        // Strategy 3: Try to find any claude executable in NVM directory
        val nvmDir = File("/Users/${System.getProperty("user.name")}/.nvm/versions/node")
        if (nvmDir.exists() && nvmDir.isDirectory) {
            nvmDir.walkTopDown()
                .filter { it.name == "claude" && it.canExecute() }
                .firstOrNull()?.let { claudeFile ->
                    log.info { "Found Claude CLI in NVM directory: ${claudeFile.absolutePath}" }
                    return claudeFile.absolutePath
                }
        }

        log.warn { "Could not auto-detect Claude CLI installation path, falling back to 'claude'" }
        return "claude"
    }
}
