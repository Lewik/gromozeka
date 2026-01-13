package com.gromozeka.infrastructure.ai.service.lsp

import klog.KLoggers
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.PreDestroy

/**
 * Service managing LSP clients for different languages and projects.
 *
 * Features:
 * - One LSP client per language per project
 * - Lazy initialization (start server on first use)
 * - Automatic cleanup on shutdown
 * - Support for npx-based and binary-based servers
 */
@Service
class LspClientService {

    private val log = KLoggers.logger(this)

    private val clients = ConcurrentHashMap<String, LspClient>()

    /**
     * Get or create LSP client for language and project.
     */
    fun getClient(language: String, projectPath: String): LspClient {
        val key = "$language:$projectPath"

        return clients.computeIfAbsent(key) {
            log.info { "Creating LSP client for $language in $projectPath" }
            createClient(language, projectPath)
        }
    }

    /**
     * Create LSP client with appropriate server command for language.
     */
    private fun createClient(language: String, projectPath: String): LspClient {
        val command = getServerCommand(language)
        val rootUri = Path.of(projectPath)

        return LspClient(command, rootUri, language)
    }

    /**
     * Get LSP server command for language.
     *
     * Priority:
     * 1. Check if binary exists in PATH
     * 2. Fall back to npx for npm-based servers
     * 3. Error if not supported
     */
    private fun getServerCommand(language: String): List<String> {
        return when (language.lowercase()) {
            "kotlin" -> {
                // kotlin-language-server from Homebrew or system PATH
                if (isCommandAvailable("kotlin-language-server")) {
                    listOf("kotlin-language-server")
                } else {
                    error("kotlin-language-server not found. Install with: brew install kotlin-language-server")
                }
            }

            "typescript", "javascript" -> {
                // typescript-language-server via npx (auto-downloads if needed)
                listOf("npx", "typescript-language-server", "--stdio")
            }

            "python" -> {
                // pyright via npx
                listOf("npx", "pyright-langserver", "--stdio")
            }

            "bash", "sh" -> {
                // bash-language-server via npx
                listOf("npx", "bash-language-server", "start")
            }

            "json" -> {
                // vscode-json-languageserver via npx
                listOf("npx", "vscode-json-languageserver", "--stdio")
            }

            "yaml", "yml" -> {
                // yaml-language-server via npx
                listOf("npx", "yaml-language-server", "--stdio")
            }

            else -> error("Language '$language' not supported")
        }
    }

    /**
     * Check if command is available in PATH.
     */
    private fun isCommandAvailable(command: String): Boolean {
        return try {
            val process = ProcessBuilder("which", command)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Shutdown all LSP clients.
     */
    @PreDestroy
    fun shutdown() {
        log.info { "Shutting down ${clients.size} LSP clients" }

        clients.values.forEach { client ->
            try {
                client.close()
            } catch (e: Exception) {
                log.warn(e) { "Error closing LSP client" }
            }
        }

        clients.clear()
    }
}
