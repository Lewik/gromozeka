package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Prompt
import klog.KLoggers
import kotlinx.datetime.Clock
import org.springframework.stereotype.Component
import java.io.File

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

        return scanGlobalPromptsFromDirectory(promptsDir)
    }
    
    private fun scanGlobalPromptsFromDirectory(directory: File): List<Prompt> {
        val promptFiles = directory.listFiles { file -> 
            file.isFile && file.extension == "md"
        } ?: emptyArray()

        val prompts = promptFiles.mapNotNull { file ->
            try {
                val content = file.readText()
                val name = file.nameWithoutExtension
                    .replace("-", " ")
                    .replaceFirstChar { it.uppercase() }
                
                Prompt(
                    id = Prompt.Id("global:${file.absolutePath}"),
                    name = name,
                    content = content,
                    type = Prompt.Type.Global,
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now()
                )
            } catch (e: Exception) {
                log.error(e) { "Failed to read prompt file: ${file.absolutePath}" }
                null
            }
        }

        log.info { "Scanned ${prompts.size} global prompts from ${directory.absolutePath}" }
        return prompts
    }

    fun loadGlobalPromptById(id: Prompt.Id): Prompt? {
        require(id.value.startsWith("global:")) { "Not a global prompt id: ${id.value}" }
        val path = id.value.removePrefix("global:")
        val resolvedPath = resolveGlobalPath(path)
        return loadPromptFile(id, File(resolvedPath), Prompt.Type.Global)
    }

    fun loadWorkspacePromptById(id: Prompt.Id, workspaceRootPath: String): Prompt? {
        require(id.value.startsWith("workspace:")) { "Not a workspace prompt id: ${id.value}" }
        val fileName = id.value.removePrefix("workspace:")
        val file = File(workspaceRootPath, ".gromozeka/prompts/$fileName")
        return loadPromptFile(id, file, Prompt.Type.Workspace)
    }

    private fun loadPromptFile(id: Prompt.Id, file: File, type: Prompt.Type): Prompt? {
        if (!file.exists() || !file.isFile) {
            log.warn { "Prompt file not found: ${file.absolutePath}" }
            return null
        }

        return try {
            val content = file.readText()
            val name = file.nameWithoutExtension
                .replace("-", " ")
                .replaceFirstChar { it.uppercase() }

            Prompt(
                id = id,
                name = name,
                content = content,
                type = type,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now()
            )
        } catch (e: Exception) {
            log.error(e) { "Failed to load prompt: ${file.absolutePath}" }
            null
        }
    }

    private fun resolveGlobalPath(path: String): String {
        return when {
            path.startsWith("~/") -> {
                System.getProperty("user.home") + path.substring(1)
            }
            path.startsWith("/") -> path
            else -> {
                // Relative to GROMOZEKA_HOME
                val gromozekaHome = System.getProperty("GROMOZEKA_HOME")
                    ?: throw IllegalStateException("GROMOZEKA_HOME system property not set")
                "$gromozekaHome/$path"
            }
        }
    }

}
