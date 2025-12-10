package com.gromozeka.infrastructure.ai.treesitter

import com.gromozeka.domain.service.treesitter.LanguageRegistry
import io.github.treesitter.ktreesitter.Language
import io.github.treesitter.ktreesitter.Parser
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of LanguageRegistry for Tree-sitter
 * 
 * Currently supports only Kotlin language.
 * Other languages can be added when their grammars are compiled.
 */
@Service
class LanguageRegistryImpl : LanguageRegistry {
    
    // Map of file extensions to language names
    private val extensionToLanguage = mapOf(
        "kt" to "kotlin",
        "kts" to "kotlin"
        // TODO: Add other languages when grammars are available
        // "py" to "python",
        // "js" to "javascript",
        // "ts" to "typescript",
        // "java" to "java",
    )
    
    // Cache for parsers
    private val parsers = ConcurrentHashMap<String, Parser>()
    
    // Cache for languages
    private val languages = ConcurrentHashMap<String, Language>()
    
    override fun languageForFile(filePath: String): String? {
        val path = Path.of(filePath)
        val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()
        
        // Handle special cases
        return when {
            path.fileName.toString().lowercase() == "dockerfile" -> "dockerfile"
            path.fileName.toString().lowercase() == "makefile" -> "make"
            extension.isEmpty() -> null
            else -> extensionToLanguage[extension]
        }
    }
    
    override fun isLanguageAvailable(language: String): Boolean {
        return when (language) {
            "kotlin" -> true
            else -> false
        }
    }
    
    override fun listAvailableLanguages(): Set<String> {
        return setOf("kotlin")
    }
    
    override fun getExtensionsForLanguage(language: String): List<String> {
        return extensionToLanguage.filterValues { it == language }.keys.toList()
    }
    
    /**
     * Get parser for language (internal use)
     */
    internal fun getParser(language: String): Parser {
        return parsers.computeIfAbsent(language) { lang ->
            val tsLanguage = getLanguage(lang)
            Parser(tsLanguage)
        }
    }
    
    /**
     * Get Tree-sitter Language for language name (internal use)
     */
    private fun getLanguage(language: String): Language {
        return languages.computeIfAbsent(language) { lang ->
            when (lang) {
                "kotlin" -> {
                    // Load Kotlin grammar from compiled JNI library
                    // Use reflection to avoid direct dependency on generated class
                    val kotlinClass = Class.forName("io.github.treesitter.ktreesitter.kotlin.TreeSitterKotlin")
                    val languageMethod = kotlinClass.getMethod("language")
                    val languagePtr = languageMethod.invoke(null) as Long
                    Language(languagePtr)
                }
                else -> throw IllegalArgumentException("Language not supported: $lang")
            }
        }
    }
    
    /**
     * Scan project directory for languages
     */
    fun scanProjectLanguages(projectPath: Path): Set<String> {
        val languages = mutableSetOf<String>()
        
        try {
            projectPath.toFile().walkTopDown()
                .filter { it.isFile }
                .mapNotNull { languageForFile(it.path) }
                .forEach { languages.add(it) }
        } catch (e: Exception) {
            println("[LanguageRegistry] Error scanning project languages: ${e.message}")
        }
        
        return languages
    }
}
