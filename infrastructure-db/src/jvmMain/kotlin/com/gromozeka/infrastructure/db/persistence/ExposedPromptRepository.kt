package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.repository.PromptRepository
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ExposedPromptRepository(
    private val fileSystemPromptScanner: FileSystemPromptScanner,
    private val builtinPromptLoader: BuiltinPromptLoader
) : PromptRepository {

    private var cachedBuiltinPrompts: List<Prompt> = emptyList()

    // In-memory cache for environment prompts (created dynamically via MCP)
    private val environmentPromptsCache = ConcurrentHashMap<Prompt.Id, Prompt>()

    private fun getProjectPath(): String? {
        return fileSystemPromptScanner.findProjectRoot()?.absolutePath
    }

    override suspend fun findById(id: Prompt.Id): Prompt? {
        val idValue = id.value

        // Check environment cache first
        environmentPromptsCache[id]?.let { return it }

        // Handle builtin with project override
        if (idValue.startsWith("builtin:")) {
            val fileName = idValue.removePrefix("builtin:")
            val projectPath = getProjectPath()

            // Check for project override first
            if (projectPath != null) {
                val projectOverride = Prompt.Id("project:$fileName")
                fileSystemPromptScanner.loadPromptById(projectOverride, projectPath)?.let {
                    return it
                }
            }

            // Fall back to builtin
            return cachedBuiltinPrompts.find { it.id == id }
        }

        // For global and project prompts, load from disk
        if (idValue.startsWith("global:") || idValue.startsWith("project:")) {
            val projectPath = getProjectPath()
            return fileSystemPromptScanner.loadPromptById(id, projectPath)
        }

        return null
    }

    override suspend fun findAll(): List<Prompt> {
        val projectPath = getProjectPath()

        val globalPrompts = fileSystemPromptScanner.scanGlobalPrompts()
        val projectPrompts = if (projectPath != null) {
            fileSystemPromptScanner.scanProjectPrompts(projectPath)
        } else {
            emptyList()
        }

        return (cachedBuiltinPrompts + globalPrompts + projectPrompts + environmentPromptsCache.values)
            .sortedBy { it.name }
    }

    override suspend fun findByType(type: Prompt.Type): List<Prompt> {
        return findAll().filter { it.type == type }
    }

    override suspend fun refresh() {
        cachedBuiltinPrompts = builtinPromptLoader.loadBuiltinPrompts()
    }

    override suspend fun count(): Int {
        return findAll().size
    }
    
    override suspend fun save(prompt: Prompt): Prompt {
        return when (prompt.type) {
            is Prompt.Type.Environment -> {
                // Store environment prompts in memory cache
                environmentPromptsCache[prompt.id] = prompt
                prompt
            }
            else -> {
                throw UnsupportedOperationException(
                    "File-based prompts cannot be saved via repository. " +
                    "Use file system to create/edit prompts."
                )
            }
        }
    }

    @jakarta.annotation.PostConstruct
    fun initialize() {
        cachedBuiltinPrompts = builtinPromptLoader.loadBuiltinPrompts()
    }
}
