package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Prompt
import com.gromozeka.shared.path.KPath
import klog.KLoggers
import kotlinx.datetime.Clock
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component

@Component
class BuiltinPromptLoader {
    private val log = KLoggers.logger("BuiltinPromptLoader")
    private val resourceResolver = PathMatchingResourcePatternResolver()

    fun loadBuiltinPrompts(): List<Prompt> {
        val prompts = mutableListOf<Prompt>()
        
        // 1. Dynamic Environment prompt (always available)
        prompts.add(
            Prompt(
                id = Prompt.Id("system-environment"),
                name = "Environment Info",
                content = "",
                source = Prompt.Source.Dynamic.Environment,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now()
            )
        )
        
        // 2. Load all .md files from /agents directory automatically
        try {
            val resources = resourceResolver.getResources("classpath:/agents/*.md")
            
            resources.forEach { resource ->
                try {
                    val fileName = resource.filename ?: return@forEach
                    val content = resource.inputStream.bufferedReader().use { it.readText() }
                    val name = fileName.removeSuffix(".md")
                        .replace("-", " ")
                        .replaceFirstChar { it.uppercase() }
                    
                    prompts.add(
                        Prompt(
                            id = Prompt.Id("builtin:$fileName"),
                            name = name,
                            content = content,
                            source = Prompt.Source.Builtin(KPath("agents/$fileName")),
                            createdAt = Clock.System.now(),
                            updatedAt = Clock.System.now()
                        )
                    )
                } catch (e: Exception) {
                    log.error(e) { "Failed to load builtin prompt: ${resource.filename}" }
                }
            }
            
            log.info { "Loaded ${prompts.size} builtin prompts from /agents directory" }
        } catch (e: Exception) {
            log.error(e) { "Failed to scan /agents directory for prompts" }
        }

        return prompts
    }
}
