package com.gromozeka.shared.domain.session

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

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
}

fun String.toSessionUuid(): SessionUuid = SessionUuid(this)
fun String.toClaudeSessionUuid(): ClaudeSessionUuid = ClaudeSessionUuid(this)