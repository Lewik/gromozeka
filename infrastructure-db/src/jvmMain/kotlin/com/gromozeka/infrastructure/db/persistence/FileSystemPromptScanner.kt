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
                
                val id = if (isGlobal) {
                    Prompt.Id("global:${file.absolutePath}")
                } else {
                    Prompt.Id("project:${file.name}")
                }
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

    /**
     * Loads a single prompt by ID from filesystem.
     * Supports global:path and project:filename formats.
     */
    fun loadPromptById(id: Prompt.Id, projectPath: String?): Prompt? {
        val idValue = id.value

        return when {
            idValue.startsWith("global:") -> {
                val path = idValue.removePrefix("global:")
                val resolvedPath = resolveGlobalPath(path)
                val file = File(resolvedPath)

                if (!file.exists() || !file.isFile) {
                    log.warn { "Global prompt file not found: $resolvedPath" }
                    return null
                }

                try {
                    val content = file.readText()
                    val name = file.nameWithoutExtension
                        .replace("-", " ")
                        .replaceFirstChar { it.uppercase() }

                    Prompt(
                        id = id,
                        name = name,
                        content = content,
                        type = Prompt.Type.Global,
                        createdAt = Clock.System.now(),
                        updatedAt = Clock.System.now()
                    )
                } catch (e: Exception) {
                    log.error(e) { "Failed to load global prompt: $resolvedPath" }
                    null
                }
            }

            idValue.startsWith("project:") -> {
                if (projectPath == null) {
                    log.warn { "Project path not provided for project prompt: $idValue" }
                    return null
                }

                val fileName = idValue.removePrefix("project:")
                val file = File(projectPath, ".gromozeka/prompts/$fileName")

                if (!file.exists() || !file.isFile) {
                    log.warn { "Project prompt file not found: ${file.absolutePath}" }
                    return null
                }

                try {
                    val content = file.readText()
                    val name = file.nameWithoutExtension
                        .replace("-", " ")
                        .replaceFirstChar { it.uppercase() }

                    Prompt(
                        id = id,
                        name = name,
                        content = content,
                        type = Prompt.Type.Project,
                        createdAt = Clock.System.now(),
                        updatedAt = Clock.System.now()
                    )
                } catch (e: Exception) {
                    log.error(e) { "Failed to load project prompt: ${file.absolutePath}" }
                    null
                }
            }

            else -> null
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
