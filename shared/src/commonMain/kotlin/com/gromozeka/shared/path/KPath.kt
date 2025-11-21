package com.gromozeka.shared.path

import kotlinx.serialization.Serializable

/**
 * Type-safe file path wrapper.
 *
 * Value class with zero runtime overhead that prevents mixing paths with other strings.
 * Wraps path string for type safety without performance penalty.
 *
 * @property value path string (can be absolute or relative)
 */
@Serializable
@JvmInline
value class KPath(val value: String) {
    init {
        require(value.isNotBlank()) { "Path cannot be blank" }
    }
}
