package com.gromozeka.shared.domain.session

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class SessionUuid(val value: String) {
    init {
        require(value.isNotBlank()) { "SessionUuid cannot be blank" }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class ClaudeSessionUuid(val value: String) {
    init {
        require(value.isNotBlank()) { "ClaudeSessionUuid cannot be blank" }
    }

    override fun toString(): String = value

    companion object {
        val DEFAULT = ClaudeSessionUuid("default")
    }
}

fun String.toSessionUuid(): SessionUuid = SessionUuid(this)
fun String.toClaudeSessionUuid(): ClaudeSessionUuid = ClaudeSessionUuid(this)