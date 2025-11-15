package com.gromozeka.bot.services

import klog.KLoggers
import org.springframework.stereotype.Service

@Service
class TabPromptService {
    private val log = KLoggers.logger {}

    companion object {
        private val DEFAULT_PROMPTS = listOf(
            "default-agent.md",
            "domain-model.md",
        )
    }

    fun listAvailablePrompts(): List<String> {
        return try {
            val knownPrompts = listOf(
                "architect-agent.md",
                "architecture.md",
                "business-logic-agent.md",
                "data-layer-agent.md",
                "data-services-agent.md",
                "infrastructure-agent.md",
                "shared-base.md",
                "spring-ai-agent.md",
                "ui-agent.md",
                "agent-builder.md"
            )

            knownPrompts.filter { fileName ->
                javaClass.getResourceAsStream("/agents/$fileName") != null
            }.sorted()
        } catch (e: Exception) {
            log.error(e) { "Failed to list prompts from resources" }
            emptyList()
        }
    }

    fun buildDefaultPrompts(): String {
        log.debug { "Loading default prompts: $DEFAULT_PROMPTS" }
        return DEFAULT_PROMPTS
            .mapNotNull {
                loadPromptContent(it).ifEmpty {
                    log.warn { "Default prompt not found or empty: $it" }
                    null
                }
            }
            .joinToString("\n\n---\n\n")
    }
    
    fun loadPromptContent(fileName: String): String {
        val resourcePath = "/agents/$fileName"
        
        return try {
            javaClass.getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: run {
                    log.warn { "Prompt file not found in resources: $resourcePath" }
                    ""
                }
        } catch (e: Exception) {
            log.error(e) { "Failed to load prompt file: $resourcePath" }
            ""
        }
    }
    
    fun buildAdditionalPrompt(customPrompts: List<String>): String {
        if (customPrompts.isEmpty()) {
            return ""
        }
        
        return customPrompts
            .mapNotNull { fileName ->
                val content = loadPromptContent(fileName)
                if (content.isNotEmpty()) {
                    "# Included: $fileName\n\n$content"
                } else {
                    null
                }
            }
            .joinToString("\n\n---\n\n")
    }
}
