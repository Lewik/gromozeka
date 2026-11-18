package com.gromozeka.domain.service

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest

/** The source is a literal program, never a shell command or a process argument. */
data class PythonProgram(
    val code: String? = null,
    val filePath: String? = null,
    val arguments: List<String> = emptyList(),
) {
    init {
        require((code != null) != (filePath != null)) { "Supply exactly one of code or file_path" }
        require(code == null || code.isNotBlank()) { "code must not be blank" }
        require(filePath == null || filePath.isNotBlank()) { "file_path must not be blank" }
        require(code?.contains('\u0000') != true && filePath?.contains('\u0000') != true) {
            "Python source and file_path must not contain NUL"
        }
        require(arguments.none { '\u0000' in it }) { "Python arguments must not contain NUL" }
    }

    /** Binds admission to the complete source selection and argv without duplicating source in task inventory. */
    val commandIdentity: String by lazy {
        val input = buildJsonObject {
            code?.let { put("code", it) }
            filePath?.let { put("file_path", it) }
            putJsonArray("arguments") { arguments.forEach { add(JsonPrimitive(it)) } }
        }.toString().encodeToByteArray(throwOnInvalidSequence = true)
        val hash = MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }
        "Python ${filePath ?: "inline"} [sha256:$hash]"
    }
}
