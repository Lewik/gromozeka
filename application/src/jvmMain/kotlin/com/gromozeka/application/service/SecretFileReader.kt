package com.gromozeka.application.service

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal fun readSecretFile(filePath: String): String {
    val path = try { Path.of(filePath) } catch (_: InvalidPathException) {
        throw IllegalArgumentException("Invalid secret file path on the execution target")
    }
    require(path.isAbsolute) { "Secret file path must be absolute on the execution target" }
    val bytes = try {
        Files.newInputStream(path).use { it.readNBytes(MAX_SECRET_FILE_BYTES + 1) }
    } catch (_: IOException) {
        throw IllegalStateException("Cannot read the configured secret file on the execution target")
    }
    require(bytes.size <= MAX_SECRET_FILE_BYTES) { "Secret file exceeds the 64 KiB limit" }
    val value = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().trim()
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("Secret file must contain UTF-8 text")
    } finally {
        bytes.fill(0)
    }
    require(value.isNotEmpty()) { "Configured secret file is empty" }
    return value
}

private const val MAX_SECRET_FILE_BYTES = 64 * 1024
