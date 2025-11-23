package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Prompt
import klog.KLoggers
import kotlinx.datetime.Clock
import org.springframework.stereotype.Component
import java.io.File
import java.util.UUID

@Component
class FileSystemPromptScanner {
    private val log = KLoggers.logger("FileSystemPromptScanner")

    fun scanGlobalPrompts(): List<Prompt> {
        val gromozekaHome = System.getProperty("GROMOZEKA_HOME") 
            ?: throw IllegalStateException("GROMOZEKA_HOME system property not set")
        
        val promptsDir = File(gromozekaHome, "prompts")
        
        if (!promptsDir.exists() || !promptsDir.isDirectory) {
            log.debug { "Global prompts directory does not exist: ${promptsDir.absolutePath}" }
            return emptyList()
        }

        return scanPromptsFromDirectory(promptsDir, isGlobal = true)
    }
    
    fun scanProjectPrompts(projectPath: String): List<Prompt> {
        val promptsDir = File(projectPath, ".gromozeka/prompts")
        
        if (!promptsDir.exists() || !promptsDir.isDirectory) {
            log.debug { "Project prompts directory does not exist: ${promptsDir.absolutePath}" }
            return emptyList()
        }

        return scanPromptsFromDirectory(promptsDir, isGlobal = false)
    }
    
    fun scanProjectPrompts(): List<Prompt> {
        val projectRoot = findProjectRoot()
        
        if (projectRoot == null) {
            log.warn { "Could not find project root with .gromozeka directory" }
            return emptyList()
        }
        
        log.info { "Found project root: ${projectRoot.absolutePath}" }
        return scanProjectPrompts(projectRoot.absolutePath)
    }
    
    private fun findProjectRoot(): File? {
        var current = File(System.getProperty("user.dir"))
        
        log.debug { "Searching for project root starting from: ${current.absolutePath}" }
        
        while (current != null) {
            val gromozekaDir = File(current, ".gromozeka")
            
            // Skip runtime data directories (dev-data, beta-data, etc.)
            if (current.name.contains("data") || current.parentFile?.name?.contains("data") == true) {
                log.debug { "Skipping data directory: ${current.absolutePath}" }
                current = current.parentFile
                continue
            }
            
            log.debug { "Checking: ${current.absolutePath} -> .gromozeka exists: ${gromozekaDir.exists()}" }
            
            if (gromozekaDir.exists() && gromozekaDir.isDirectory) {
                // Check if it's a project directory (has prompts/ or agents/ subdirs)
                val hasPrompts = File(gromozekaDir, "prompts").exists()
                val hasAgents = File(gromozekaDir, "agents").exists()
                
                if (hasPrompts || hasAgents) {
                    log.info { "Found .gromozeka directory with prompts/agents at: ${current.absolutePath}" }
                    return current
                }
            }
            
            current = current.parentFile
        }
        
        log.warn { "Could not find .gromozeka directory in any parent directories" }
        return null
    }
    
    private fun scanPromptsFromDirectory(
        directory: File,
        isGlobal: Boolean
    ): List<Prompt> {
        val promptFiles = directory.listFiles { file -> 
            file.isFile && file.extension == "md"
        } ?: emptyArray()

        val prompts = promptFiles.mapNotNull { file ->
            try {
                val content = file.readText()
                val name = file.nameWithoutExtension
                    .replace("-", " ")
                    .replaceFirstChar { it.uppercase() }
                
                val relativePath = if (isGlobal) {
                    "prompts/${file.name}"
                } else {
                    ".gromozeka/prompts/${file.name}"
                }
                
                val id = Prompt.Id(relativePath)  // ID = relative path
                val type = if (isGlobal) Prompt.Type.Global else Prompt.Type.Project
                
                Prompt(
                    id = id,
                    name = name,
                    content = content,
                    type = type,
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now()
                )
            } catch (e: Exception) {
                log.error(e) { "Failed to read prompt file: ${file.absolutePath}" }
                null
            }
        }

        val typeName = if (isGlobal) "global" else "project"
        log.info { "Scanned ${prompts.size} $typeName prompts from ${directory.absolutePath}" }
        return prompts
    }

}
