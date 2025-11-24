package com.gromozeka.application.service

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.repository.PromptDomainService
import com.gromozeka.domain.repository.PromptRepository
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service

@Service
class PromptApplicationService(
    private val promptRepository: PromptRepository,
    private val promptCopyService: com.gromozeka.infrastructure.db.persistence.PromptCopyService,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val importedPromptsRegistry: com.gromozeka.infrastructure.db.persistence.ImportedPromptsRegistry,
) : PromptDomainService {

    override suspend fun assembleSystemPrompt(
        promptIds: List<Prompt.Id>,
        separator: String,
        projectPath: String,
    ): String {
        val prompts = promptIds.map { id ->
            when {
                id.value == "env" -> {
                    // Generate environment info on the fly
                    val envContent = systemPromptBuilder.buildEnvironmentInfo(projectPath)
                    Prompt(
                        id = id,
                        name = "Environment Info",
                        content = envContent,
                        type = Prompt.Type.Environment,
                        createdAt = Clock.System.now(),
                        updatedAt = Clock.System.now()
                    )
                }

                else -> promptRepository.findById(id) ?: Prompt(
                    id = id,
                    name = "ERROR: ${id.value}",
                    content = """
                        
                        ## ⚠️ CRITICAL ERROR: Required prompt not loaded
                        **Failed to load prompt:** ${id.value}
                        This prompt is required for agent to function correctly.
                        
                        **Action required:** YOU MUST inform the user about missing file.
                        
                    """.trimIndent(),
                    type = Prompt.Type.Environment,
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now()
                )

            }
        }

        return prompts.joinToString(separator) { it.content }
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

        return promptCopyService.copyBuiltinToUser(prompt)
    }

    override suspend fun resetAllBuiltinPrompts(): Result<Int> {
        return promptCopyService.resetAllBuiltinPrompts()
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
