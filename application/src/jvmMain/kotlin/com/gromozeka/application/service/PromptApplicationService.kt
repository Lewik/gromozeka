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
) : PromptDomainService {

    override suspend fun assembleSystemPrompt(
        promptIds: List<Prompt.Id>,
        separator: String
    ): String {
        val prompts = promptIds.mapNotNull { id ->
            promptRepository.findById(id) ?: throw Prompt.NotFoundException(id)
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

    override suspend fun createInlinePrompt(name: String, content: String): Prompt {
        val now = Clock.System.now()
        
        val prompt = Prompt(
            id = Prompt.Id(uuid7()),
            name = name,
            content = content,
            source = Prompt.Source.Text(content),
            createdAt = now,
            updatedAt = now
        )
        
        // Register inline prompt in repository for lookup
        // Note: InMemoryPromptRepository needs a register method for this
        // For now, inline prompts are ephemeral
        return prompt
    }
    
    override suspend fun copyBuiltinPromptToUser(id: Prompt.Id): Result<Unit> {
        val prompt = promptRepository.findById(id) 
            ?: return Result.failure(IllegalArgumentException("Prompt not found: ${id.value}"))
        
        return promptCopyService.copyBuiltinToUser(prompt)
    }
    
    override suspend fun resetAllBuiltinPrompts(): Result<Int> {
        return promptCopyService.resetAllBuiltinPrompts()
    }
}
