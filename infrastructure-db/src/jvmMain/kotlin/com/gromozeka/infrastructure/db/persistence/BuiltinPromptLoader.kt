package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Prompt
import com.gromozeka.shared.path.KPath
import klog.KLoggers
import kotlinx.datetime.Clock
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class BuiltinPromptLoader {
    private val log = KLoggers.logger("BuiltinPromptLoader")

    fun loadBuiltinPrompts(): List<Prompt> {
        val resourcePath = "/agents"
        val promptFiles = listOf(
            "README.md",
            "agent-builder-v2.md",
            "agent-builder.md",
            "architect-agent.md",
            "architecture.md",
            "business-logic-agent.md",
            "default-agent.md",
            "domain-model.md",
            "repository-agent.md",
            "shared-base.md",
            "spring-ai-agent.md",
            "ui-agent.md"
        )

        val prompts = promptFiles.mapNotNull { fileName ->
            val resourceStream = javaClass.getResourceAsStream("$resourcePath/$fileName")
            
            if (resourceStream != null) {
                try {
                    val content = resourceStream.bufferedReader().use { it.readText() }
                    val name = fileName.removeSuffix(".md")
                        .replace("-", " ")
                        .replaceFirstChar { it.uppercase() }
                    
                    Prompt(
                        id = Prompt.Id("builtin:$fileName"),
                        name = name,
                        content = content,
                        source = Prompt.Source.Builtin(KPath("agents/$fileName")),
                        createdAt = Clock.System.now(),
                        updatedAt = Clock.System.now()
                    )
                } catch (e: Exception) {
                    log.error(e) { "Failed to load builtin prompt: $fileName" }
                    null
                }
            } else {
                log.warn { "Builtin prompt resource not found: $resourcePath/$fileName" }
                null
            }
        }

        log.info { "Loaded ${prompts.size} builtin prompts from resources" }
        return prompts
    }
}
