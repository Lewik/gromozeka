package com.gromozeka.bot.services

import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.XML
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

            println("[ContextFileService] Saved context: $filename")
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
}