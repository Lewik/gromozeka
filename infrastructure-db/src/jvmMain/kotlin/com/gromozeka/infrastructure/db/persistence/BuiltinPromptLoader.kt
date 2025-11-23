package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Prompt
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
        
        // Load all .md files from /prompts directory
        try {
            val resources = resourceResolver.getResources("classpath:/prompts/*.md")
            
            resources.forEach { resource ->
                try {
                    val fileName = resource.filename ?: return@forEach
                    val content = resource.inputStream.bufferedReader().use { it.readText() }
                    val name = fileName.removeSuffix(".md")
                        .replace("-", " ")
                        .replaceFirstChar { it.uppercase() }
                    
                    val resourcePath = "prompts/$fileName"
                    val id = Prompt.Id(resourcePath)  // ID = relative path
                    
                    prompts.add(
                        Prompt(
                            id = id,
                            name = name,
                            content = content,
                            type = Prompt.Type.Builtin,
                            createdAt = Clock.System.now(),
                            updatedAt = Clock.System.now()
                        )
                    )
                    
                    log.debug { "Loaded builtin prompt: $name ($id)" }
                } catch (e: Exception) {
                    log.error(e) { "Failed to load builtin prompt: ${resource.filename}" }
                }
            }
            
            log.info { "Loaded ${prompts.size} builtin prompts from /prompts directory" }
        } catch (e: Exception) {
            log.error(e) { "Failed to scan /prompts directory for prompts" }
        }

        return prompts
    }
}
