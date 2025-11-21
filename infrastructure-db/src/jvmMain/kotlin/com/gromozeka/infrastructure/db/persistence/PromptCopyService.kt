package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Prompt
import klog.KLoggers
import org.springframework.stereotype.Service
import java.io.File

@Service
class PromptCopyService(
    private val builtinPromptLoader: BuiltinPromptLoader
) {
    private val log = KLoggers.logger("PromptCopyService")

    fun copyBuiltinToUser(builtinPrompt: Prompt): Result<Unit> {
        val gromozekaHome = System.getProperty("GROMOZEKA_HOME") 
            ?: return Result.failure(IllegalStateException("GROMOZEKA_HOME system property not set"))
        
        val promptsDir = File(gromozekaHome, "prompts")
        
        if (!promptsDir.exists()) {
            promptsDir.mkdirs()
            log.info { "Created user prompts directory: ${promptsDir.absolutePath}" }
        }

        val source = builtinPrompt.source
        if (source !is Prompt.Source.Builtin) {
            return Result.failure(IllegalArgumentException("Prompt is not a builtin prompt"))
        }

        val fileName = source.resourcePath.value.substringAfterLast("/")
        val targetFile = File(promptsDir, fileName)

        return try {
            targetFile.writeText(builtinPrompt.content)
            log.info { "Copied builtin prompt to user: $fileName" }
            Result.success(Unit)
        } catch (e: Exception) {
            log.error(e) { "Failed to copy builtin prompt: $fileName" }
            Result.failure(e)
        }
    }

    fun resetAllBuiltinPrompts(): Result<Int> {
        val gromozekaHome = System.getProperty("GROMOZEKA_HOME") 
            ?: return Result.failure(IllegalStateException("GROMOZEKA_HOME system property not set"))
        
        val promptsDir = File(gromozekaHome, "prompts")
        
        if (!promptsDir.exists()) {
            promptsDir.mkdirs()
            log.info { "Created user prompts directory: ${promptsDir.absolutePath}" }
        }

        val builtinPrompts = builtinPromptLoader.loadBuiltinPrompts()
        var copiedCount = 0

        builtinPrompts.forEach { prompt ->
            val result = copyBuiltinToUser(prompt)
            if (result.isSuccess) {
                copiedCount++
            }
        }

        log.info { "Reset all builtin prompts: $copiedCount/${builtinPrompts.size} copied" }
        return Result.success(copiedCount)
    }
}
