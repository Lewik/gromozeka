package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Project
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

    override suspend fun findBuiltinById(id: Prompt.Id): Prompt? {
        val idValue = id.value
        
        // Check environment cache first
        environmentPromptsCache[id]?.let { return it }
        
        // Only works for builtin
        if (idValue.startsWith("builtin:")) {
            return cachedBuiltinPrompts.find { it.id == id }
        }
        
        return null
    }

    override suspend fun findById(id: Prompt.Id, project: Project): Prompt? {
        val idValue = id.value

        // Check environment cache first
        environmentPromptsCache[id]?.let { return it }

        // Handle builtin with project override
        if (idValue.startsWith("builtin:")) {
            val fileName = idValue.removePrefix("builtin:")

            // Check for project override first
            val projectOverride = Prompt.Id("project:$fileName")
            fileSystemPromptScanner.loadPromptById(projectOverride, project.path)?.let {
                return it
            }

            // Fall back to builtin
            return cachedBuiltinPrompts.find { it.id == id }
        }

        // For global and project prompts, load from disk
        if (idValue.startsWith("global:") || idValue.startsWith("project:")) {
            return fileSystemPromptScanner.loadPromptById(id, project.path)
        }

        return null
    }

    override suspend fun findAll(): List<Prompt> {
        val globalPrompts = fileSystemPromptScanner.scanGlobalPrompts()
        
        // Note: Project prompts are NOT included in findAll() 
        // because we don't have project context here.
        // Project prompts are loaded explicitly via findById(id, projectPath)
        // when assembling system prompts for specific project.

        return (cachedBuiltinPrompts + globalPrompts + environmentPromptsCache.values)
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
