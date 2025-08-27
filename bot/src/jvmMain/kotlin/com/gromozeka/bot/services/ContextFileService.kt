package com.gromozeka.bot.services

import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.XML
import klog.KLoggers

import org.springframework.stereotype.Service
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

@Service
class ContextFileService(
    private val gitService: GitService,
    private val settingsService: SettingsService,
) {
    private val log = KLoggers.logger(this)

    private val contextsDir = File(settingsService.gromozekaHome, "contexts")

    private val xml = XML {
        defaultPolicy {
            ignoreUnknownChildren()
        }
    }

    init {
        contextsDir.mkdirs()
        // temporary disabled
//        gitService.initializeRepository(contextsDir)
    }

    fun parseXmlAndSaveContexts(xmlContent: String, projectPath: String) {

        val extractedContexts = parseXmlToContexts(xmlContent, projectPath)

        if (extractedContexts.contexts.isEmpty()) {
            throw IllegalArgumentException("No contexts found in XML")
        }

        val savedFiles = mutableListOf<String>()

        extractedContexts.contexts.forEach { context ->
            val filename = generateContextFilename(context.name)
            val mdContent = convertContextToMarkdown(context)

            val file = File(contextsDir, filename)
            file.writeText(mdContent)
            savedFiles.add(filename)

            log.info("Saved context: $filename")
        }

        val commitMessage = "Add contexts: ${savedFiles.joinToString(", ")}"
        gitService.addAndCommit(contextsDir, commitMessage)

    }

    private fun parseXmlToContexts(xmlContent: String, projectPath: String): ExtractedContexts {
        try {
            val contextsXml = xml.decodeFromString<ContextsXml>(xmlContent)

            val contexts = contextsXml.context.map { contextXml ->

                val files = contextXml.files?.file?.associate { fileXml ->
                    val spec = when {
                        fileXml.isReadFull -> ContextFileSpec.ReadFull
                        fileXml.specificItems.isNotEmpty() -> ContextFileSpec.Specific(fileXml.specificItems)
                        else -> ContextFileSpec.ReadFull
                    }
                    fileXml.path to spec
                } ?: emptyMap()

                val links = contextXml.links?.link ?: emptyList()

                ContextItem(
                    name = contextXml.name,
                    projectPath = projectPath,
                    files = files,
                    links = links,
                    content = contextXml.content,
                    extractedAt = LocalDateTime.now().toString()
                )
            }

            return ExtractedContexts(contexts)

        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse XML: ${e.message}", e)
        }
    }

    private fun convertContextToMarkdown(context: ContextItem): String {
        val md = StringBuilder()

        md.appendLine("# ${context.name}")
        md.appendLine("> Context extracted from conversation")
        md.appendLine()

        md.appendLine("## Project Path")
        md.appendLine(context.projectPath)
        md.appendLine()

        if (context.files.isNotEmpty()) {
            md.appendLine("## Files to Read")
            context.files.forEach { (path, spec) ->
                when (spec) {
                    is ContextFileSpec.ReadFull -> {
                        md.appendLine("- `$path` - readfull")
                    }

                    is ContextFileSpec.Specific -> {
                        val items = spec.items.joinToString(", ")
                        md.appendLine("- `$path` - $items")
                    }
                }
            }
            md.appendLine()
        }

        if (context.links.isNotEmpty()) {
            md.appendLine("## Links")
            context.links.forEach { link ->
                md.appendLine("- $link")
            }
            md.appendLine()
        }

        md.appendLine("## Context")
        md.appendLine(context.content)
        md.appendLine()

        md.appendLine("---")
        md.appendLine("*Extracted: ${context.extractedAt}*")

        return md.toString()
    }

    private fun generateContextFilename(contextName: String): String {
        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val safeName = contextName
            .replace(Regex("[^a-zA-Z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .lowercase()
            .take(30)
        val guid = UUID.randomUUID().toString().take(8)

        return "${date}_${safeName}_${guid}.md"
    }

    // UI-specific methods for Context Management
    
    suspend fun listAllContexts(): List<ContextItem> {
        if (!contextsDir.exists()) return emptyList()
        
        return contextsDir.listFiles { file -> file.extension == "md" }
            ?.mapNotNull { file ->
                try {
                    parseContextFromMarkdown(file.readText())
                        .copy(filePath = file.absolutePath)
                } catch (e: Exception) {
                    log.warn(e, "Failed to parse context file: ${file.name}, error: ${e.message}")
                    null
                }
            } ?: emptyList()
    }
    
    suspend fun listContextsForProject(projectPath: String): List<ContextItem> = 
        listAllContexts().filter { it.projectPath == projectPath }
    
    suspend fun loadContextContent(filePath: String): String {
        return File(filePath).readText()
    }
    
    suspend fun deleteContext(filePath: String): Result<Unit> {
        return runCatching {
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("Context file not found: $filePath")
            }
            
            val contextName = file.nameWithoutExtension
            file.delete()
            
            try {
                gitService.addAndCommit(contextsDir, "Delete context: $contextName")
            } catch (e: Exception) {
                log.warn(e, "Git commit failed for context deletion: ${e.message}")
            }
        }
    }
    
    suspend fun getContextsStatistics(): ContextsStatistics {
        val contexts = listAllContexts()
        return ContextsStatistics(
            totalContexts = contexts.size,
            totalProjects = contexts.map { it.projectPath }.distinct().size,
            totalFiles = contexts.sumOf { it.files.size },
            recentContexts = contexts.filter { 
                try {
                    val weekAgo = java.time.Instant.now().minus(java.time.Duration.ofDays(7))
                    java.time.Instant.parse(it.extractedAt ?: "1970-01-01T00:00:00Z").isAfter(weekAgo)
                } catch (e: Exception) {
                    false
                }
            }.size
        )
    }
    
    private fun parseContextFromMarkdown(content: String): ContextItem {
        val lines = content.lines()
        var name = "Unknown Context"
        var projectPath = ""
        var currentContent = ""
        val files = mutableMapOf<String, ContextFileSpec>()
        val links = mutableListOf<String>()
        var extractedAt: String? = null
        
        var currentSection = ""
        var inFilesSection = false
        var inLinksSection = false
        var inContextSection = false
        
        for (line in lines) {
            when {
                line.startsWith("# ") -> {
                    name = line.removePrefix("# ")
                }
                line.startsWith("## Project Path") -> {
                    currentSection = "project"
                    inFilesSection = false
                    inLinksSection = false
                    inContextSection = false
                }
                line.startsWith("## Files to Read") -> {
                    currentSection = "files"
                    inFilesSection = true
                    inLinksSection = false
                    inContextSection = false
                }
                line.startsWith("## Links") -> {
                    currentSection = "links"
                    inFilesSection = false
                    inLinksSection = true
                    inContextSection = false
                }
                line.startsWith("## Context") -> {
                    currentSection = "context"
                    inFilesSection = false
                    inLinksSection = false
                    inContextSection = true
                }
                line.startsWith("*Extracted: ") -> {
                    extractedAt = line.removePrefix("*Extracted: ").removeSuffix("*")
                }
                currentSection == "project" && line.isNotBlank() && !line.startsWith("##") -> {
                    projectPath = line.trim()
                }
                inFilesSection && line.startsWith("- `") -> {
                    val filePattern = Regex("- `([^`]+)` - (.+)")
                    val match = filePattern.find(line)
                    if (match != null) {
                        val (filePath, spec) = match.destructured
                        files[filePath] = if (spec == "readfull") {
                            ContextFileSpec.ReadFull
                        } else {
                            ContextFileSpec.Specific(spec.split(", "))
                        }
                    }
                }
                inLinksSection && line.startsWith("- ") -> {
                    links.add(line.removePrefix("- "))
                }
                inContextSection && line.isNotBlank() && !line.startsWith("##") && !line.startsWith("---") -> {
                    currentContent += if (currentContent.isEmpty()) line else "\n$line"
                }
            }
        }
        
        return ContextItem(
            name = name,
            projectPath = projectPath,
            files = files,
            links = links,
            content = currentContent,
            extractedAt = extractedAt
        )
    }
    
    data class ContextsStatistics(
        val totalContexts: Int,
        val totalProjects: Int, 
        val totalFiles: Int,
        val recentContexts: Int
    )
}