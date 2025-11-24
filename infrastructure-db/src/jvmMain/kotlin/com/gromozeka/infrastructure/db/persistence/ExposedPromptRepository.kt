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
    private var cachedProjectPrompts: List<Prompt> = emptyList()
    private var cachedGlobalPrompts: List<Prompt> = emptyList()
    
    // In-memory cache for environment prompts (created dynamically via MCP)
    private val environmentPromptsCache = ConcurrentHashMap<Prompt.Id, Prompt>()

    override suspend fun findById(id: Prompt.Id): Prompt? {
        // Check environment cache first
        environmentPromptsCache[id]?.let { return it }
        
        // Then check file-based prompts
        return allPrompts().find { it.id == id }
    }

    override suspend fun findAll(): List<Prompt> {
        return allPrompts().sortedBy { it.name }
    }

    override suspend fun findByType(type: Prompt.Type): List<Prompt> {
        return allPrompts().filter { it.type == type }.sortedBy { it.name }
    }

    override suspend fun refresh() {
        cachedBuiltinPrompts = builtinPromptLoader.loadBuiltinPrompts()
        cachedGlobalPrompts = fileSystemPromptScanner.scanGlobalPrompts()
        cachedProjectPrompts = fileSystemPromptScanner.scanProjectPrompts()
    }

    override suspend fun count(): Int {
        return allPrompts().size
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
        cachedGlobalPrompts = fileSystemPromptScanner.scanGlobalPrompts()
        cachedProjectPrompts = fileSystemPromptScanner.scanProjectPrompts()
    }

    private fun allPrompts(): List<Prompt> {
        return cachedBuiltinPrompts + cachedGlobalPrompts + cachedProjectPrompts + environmentPromptsCache.values
    }
}
