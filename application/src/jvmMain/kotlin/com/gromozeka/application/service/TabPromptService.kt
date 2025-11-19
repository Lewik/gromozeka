package com.gromozeka.application.service

import klog.KLoggers
import org.springframework.stereotype.Service
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Paths
import kotlin.io.path.name

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
            val uri = javaClass.getResource("/agents/")?.toURI() 
                ?: run {
                    log.warn { "Resource directory /agents/ not found" }
                    return emptyList()
                }
            
            val dirPath = try {
                Paths.get(uri)
            } catch (e: FileSystemNotFoundException) {
                FileSystems.newFileSystem(uri, emptyMap<String, String>()).getPath("/agents/")
            }
            
            val excludedFiles = setOf("README.md") + DEFAULT_PROMPTS
            
            Files.list(dirPath)
                .filter { Files.isRegularFile(it) }
                .filter { it.name.endsWith(".md") }
                .map { it.name }
                .filter { it !in excludedFiles }
                .sorted()
                .toList()
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
