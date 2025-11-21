package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Prompt
import com.gromozeka.shared.path.KPath
import klog.KLoggers
import kotlinx.datetime.Clock
import org.springframework.stereotype.Component
import java.io.File
import java.util.UUID

@Component
class FileSystemPromptScanner(
    private val importedPromptsRegistry: ImportedPromptsRegistry
) {
    private val log = KLoggers.logger("FileSystemPromptScanner")

    fun scanUserPrompts(): List<Prompt> {
        val gromozekaHome = System.getProperty("GROMOZEKA_HOME") 
            ?: throw IllegalStateException("GROMOZEKA_HOME system property not set")
        
        val promptsDir = File(gromozekaHome, "prompts")
        
        if (!promptsDir.exists() || !promptsDir.isDirectory) {
            log.warn { "User prompts directory does not exist: ${promptsDir.absolutePath}" }
            return emptyList()
        }

        val promptFiles = promptsDir.listFiles { file -> 
            file.isFile && file.extension == "md"
        } ?: emptyArray()

        val prompts = promptFiles.mapNotNull { file ->
            try {
                val content = file.readText()
                val name = file.nameWithoutExtension
                    .replace("-", " ")
                    .replaceFirstChar { it.uppercase() }
                
                Prompt(
                    id = Prompt.Id("user:${file.nameWithoutExtension}"),
                    name = name,
                    content = content,
                    source = Prompt.Source.LocalFile.User(KPath(file.name)),
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now()
                )
            } catch (e: Exception) {
                log.error(e) { "Failed to read prompt file: ${file.absolutePath}" }
                null
            }
        }

        log.info { "Scanned ${prompts.size} user prompts from ${promptsDir.absolutePath}" }
        
        // Scan imported prompts from registry
        val importedPrompts = scanImportedPrompts()
        
        return prompts + importedPrompts
    }
    
    private fun scanImportedPrompts(): List<Prompt> {
        val importedPaths = importedPromptsRegistry.load()
        
        if (importedPaths.isEmpty()) {
            return emptyList()
        }
        
        val prompts = importedPaths.mapNotNull { path ->
            try {
                val file = File(path)
                if (!file.exists() || !file.isFile) {
                    log.warn { "Imported prompt file not found: $path" }
                    return@mapNotNull null
                }
                
                val content = file.readText()
                val name = "${file.parentFile.name}/CLAUDE.md"
                
                Prompt(
                    id = Prompt.Id("imported:${file.absolutePath.hashCode()}"),
                    name = name,
                    content = content,
                    source = Prompt.Source.LocalFile.Imported(KPath(file.absolutePath)),
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now()
                )
            } catch (e: Exception) {
                log.error(e) { "Failed to read imported prompt: $path" }
                null
            }
        }
        
        log.info { "Scanned ${prompts.size} imported prompts from registry" }
        return prompts
    }
}
