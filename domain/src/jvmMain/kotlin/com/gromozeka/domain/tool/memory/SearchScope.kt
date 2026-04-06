package com.gromozeka.domain.tool.memory

/**
 * Search scope for unified_search tool.
 *
 * Defines what sources and symbol types to search.
 *
 * ## Source scopes
 * - [MEMORY_OBJECTS] - Facts, concepts, technologies in knowledge graph
 * - [CONVERSATION_MESSAGES] - Past conversation messages
 * - [CODE_SPECS] - All code symbols (shortcut for all CODE_SPECS_* types)
 *
 * ## Code symbol scopes
 * - [CODE_SPECS_CLASS] - Class definitions
 * - [CODE_SPECS_INTERFACE] - Interface definitions
 * - [CODE_SPECS_ENUM] - Enum definitions
 * - [CODE_SPECS_METHOD] - Methods and functions
 * - [CODE_SPECS_PROPERTY] - Properties and fields
 * - [CODE_SPECS_CONSTRUCTOR] - Constructors
 *
 * ## JSON serialization
 * Values use enum names exactly as written:
 * - `MEMORY_OBJECTS`
 * - `CODE_SPECS_CLASS`
 */
enum class SearchScope {
    MEMORY_OBJECTS,
    CONVERSATION_MESSAGES,
    CODE_SPECS,
    CODE_SPECS_CLASS,
    CODE_SPECS_INTERFACE,
    CODE_SPECS_ENUM,
    CODE_SPECS_METHOD,
    CODE_SPECS_PROPERTY,
    CODE_SPECS_CONSTRUCTOR;

    val isCodeSpec: Boolean
        get() = this == CODE_SPECS || name.startsWith("CODE_SPECS_")

    val symbolKind: String?
        get() = when (this) {
            CODE_SPECS_CLASS -> "Class"
            CODE_SPECS_INTERFACE -> "Interface"
            CODE_SPECS_ENUM -> "Enum"
            CODE_SPECS_METHOD -> "Method"
            CODE_SPECS_PROPERTY -> "Property"
            CODE_SPECS_CONSTRUCTOR -> "Constructor"
            else -> null
        }

    companion object {
        /**
         * Extract symbol kinds from a set of scopes.
         * Returns null if CODE_SPECS is present (meaning all symbols).
         */
        fun extractSymbolKinds(scopes: Set<SearchScope>): Set<String>? {
            if (CODE_SPECS in scopes) return null
            val kinds = scopes.mapNotNull { it.symbolKind }.toSet()
            return kinds.takeIf { it.isNotEmpty() }
        }

        /**
         * Check if any code spec scope is present.
         */
        fun hasCodeSpecs(scopes: Set<SearchScope>): Boolean =
            scopes.any { it.isCodeSpec }

        /**
         * Check if memory objects scope is present.
         */
        fun hasMemoryObjects(scopes: Set<SearchScope>): Boolean =
            MEMORY_OBJECTS in scopes

        /**
         * Check if conversation messages scope is present.
         */
        fun hasConversationMessages(scopes: Set<SearchScope>): Boolean =
            CONVERSATION_MESSAGES in scopes
    }
}
