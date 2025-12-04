package com.gromozeka.application.service

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.repository.PromptDomainService
import com.gromozeka.domain.repository.PromptRepository
import com.gromozeka.domain.service.PromptPersistenceService
import com.gromozeka.domain.service.ImportedPromptsRegistry
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service

@Service
class PromptApplicationService(
    private val promptRepository: PromptRepository,
    private val promptPersistenceService: PromptPersistenceService,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val importedPromptsRegistry: ImportedPromptsRegistry,
) : PromptDomainService {

    override suspend fun assembleSystemPrompt(
        promptIds: List<Prompt.Id>,
        projectPath: String,
    ): List<String> {
        return promptIds.map { id ->
            when {
                id.value == "env" -> {
                    systemPromptBuilder.buildEnvironmentInfo(projectPath)
                }

                else -> {
                    val prompt = promptRepository.findById(id)
                    prompt?.content ?: """
                        ## ⚠️ CRITICAL ERROR: Required prompt not loaded
                        **Failed to load prompt:** ${id.value}
                        This prompt is required for agent to function correctly.

                        **Action required:** YOU MUST inform the user about missing file.
                    """.trimIndent()
                }
            }
        }
    }

    override suspend fun findById(id: Prompt.Id): Prompt? {
        return promptRepository.findById(id)
    }

    override suspend fun findAll(): List<Prompt> {
        return promptRepository.findAll()
    }

    override suspend fun refresh() {
        promptRepository.refresh()
    }

    override suspend fun createEnvironmentPrompt(name: String, content: String): Prompt {
        val now = Clock.System.now()

        val prompt = Prompt(
            id = Prompt.Id("env:${uuid7()}"),
            name = name,
            content = content,
            type = Prompt.Type.Environment,
            createdAt = now,
            updatedAt = now
        )

        // Save to repository (will be stored in memory cache for environment prompts)
        return promptRepository.save(prompt)
    }

    override suspend fun copyBuiltinPromptToUser(id: Prompt.Id): Result<Unit> {
        val prompt = promptRepository.findById(id)
            ?: return Result.failure(IllegalArgumentException("Prompt not found: ${id.value}"))

        return promptPersistenceService.copyBuiltinToUser(prompt)
    }

    override suspend fun resetAllBuiltinPrompts(): Result<Int> {
        return promptPersistenceService.resetAllBuiltinPrompts()
    }

    override suspend fun importAllClaudeMd(): Result<Int> {
        return try {
            val claudeMdFiles = findAllClaudeMdFiles()
            val existingPaths = importedPromptsRegistry.load().toSet()

            val newPaths = claudeMdFiles
                .map { it.absolutePath }
                .filter { it !in existingPaths }

            if (newPaths.isNotEmpty()) {
                importedPromptsRegistry.add(newPaths)
                promptRepository.refresh()
            }

            Result.success(newPaths.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun findAllClaudeMdFiles(): List<java.io.File> {
        if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
            try {
                val process = ProcessBuilder("mdfind", "-name", "CLAUDE.md")
                    .redirectErrorStream(true)
                    .start()

                val files = process.inputStream.bufferedReader()
                    .lineSequence()
                    .filter { !it.startsWith("find:") }
                    .map { java.io.File(it) }
                    .filter { it.exists() && it.canRead() && it.isFile }
                    .toList()

                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)

                if (files.isNotEmpty()) return files
            } catch (e: Exception) {
                // Fallback
            }
        }

        try {
            val home = System.getProperty("user.home")
            val process = ProcessBuilder("find", home, "-name", "CLAUDE.md", "-type", "f")
                .redirectErrorStream(true)
                .start()

            val files = process.inputStream.bufferedReader()
                .lineSequence()
                .filter { !it.startsWith("find:") }
                .map { java.io.File(it) }
                .filter { it.exists() && it.canRead() && it.isFile }
                .toList()

            process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)

            return files
        } catch (e: Exception) {
            return emptyList()
        }
    }
}
