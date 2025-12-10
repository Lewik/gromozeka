package com.gromozeka.domain.service.treesitter

/**
 * Registry for managing Tree-sitter languages and parsers
 */
interface LanguageRegistry {
    
    /**
     * Detect language from file path
     * 
     * @param filePath File path (can be relative or absolute)
     * @return Language name (e.g., "kotlin", "python") or null if unknown
     */
    fun languageForFile(filePath: String): String?
    
    /**
     * Check if language is available
     * 
     * @param language Language name
     * @return true if language parser is available
     */
    fun isLanguageAvailable(language: String): Boolean
    
    /**
     * List all available languages
     * 
     * @return Set of supported language names
     */
    fun listAvailableLanguages(): Set<String>
    
    /**
     * Get file extensions for language
     * 
     * @param language Language name
     * @return List of file extensions (without dot)
     */
    fun getExtensionsForLanguage(language: String): List<String>
}
