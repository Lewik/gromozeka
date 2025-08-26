package com.gromozeka.bot.services

import com.gromozeka.bot.settings.ResponseFormat
import java.io.InputStream

/**
 * Loads system prompts from resources based on response format
 */
object SystemPromptLoader {

    private const val PROMPTS_PATH = "/prompts/"

    /**
     * Get system prompt for specified format
     */
    fun loadPrompt(
        format: ResponseFormat,
        agentPrompt: String,
    ): String {
        val formatFilename = when (format) {
            ResponseFormat.JSON -> "json-format.md"
            ResponseFormat.XML_STRUCTURED -> "xml-structured.md"
            ResponseFormat.XML_INLINE -> "xml-inline.md"
            ResponseFormat.PLAIN_TEXT -> "plain-text.md"
        }

        return listOfNotNull(
            loadResourceFile("${PROMPTS_PATH}domain-model.md"),
            agentPrompt,
            loadResourceFile("${PROMPTS_PATH}tech-prompt.md"),
            loadResourceFile("$PROMPTS_PATH$formatFilename")
        )
            .joinToString("\n\n")
    }


    /**
     * Load prompt file from resources
     */
    private fun loadResourceFile(path: String): String? {
        return try {
            val inputStream: InputStream? = this::class.java.getResourceAsStream(path)
            inputStream?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            println("[SystemPromptLoader] Failed to load prompt from $path: ${e.message}")
            null
        }
    }
}