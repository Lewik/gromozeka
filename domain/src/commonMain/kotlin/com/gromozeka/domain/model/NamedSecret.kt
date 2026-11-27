package com.gromozeka.domain.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

@Serializable
data class NamedSecret(
    val id: Id,
    val userId: User.Id,
    val name: String,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(NAME_PATTERN.matches(name)) {
            "Secret name must start with a lowercase letter or digit and contain only lowercase letters, digits, '.', '_', or '-'"
        }
        require(description.length <= MAX_DESCRIPTION_LENGTH) {
            "Secret description must not exceed $MAX_DESCRIPTION_LENGTH characters"
        }
    }

    val reference: String
        get() = "$REFERENCE_PREFIX$name"

    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "Named secret id must not be blank" }
        }
    }

    companion object {
        const val REFERENCE_PREFIX = "secret://"
        const val MAX_DESCRIPTION_LENGTH = 512
        val NAME_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        val REFERENCE_PATTERN = Regex("secret://([a-z0-9][a-z0-9._-]{0,63})")

        fun normalizeName(value: String): String = value.trim().lowercase()

        fun nameFromReference(value: String): String? = value
            .takeIf { it.startsWith(REFERENCE_PREFIX) }
            ?.removePrefix(REFERENCE_PREFIX)
            ?.takeIf(NAME_PATTERN::matches)

    }
}

data class StoredNamedSecret(
    val metadata: NamedSecret,
    val value: String,
)
