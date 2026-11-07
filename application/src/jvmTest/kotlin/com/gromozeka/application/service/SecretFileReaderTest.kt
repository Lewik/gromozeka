package com.gromozeka.application.service

import java.nio.file.Files
import kotlin.test.*

class SecretFileReaderTest {
    @Test
    fun `token file is read again on every resolution and surrounding whitespace is removed`() {
        val directory = Files.createTempDirectory("grz-secret-source-test-")
        try {
            val path = directory.resolve("token")
            Files.writeString(path, "\n synthetic-first-token \n")
            assertEquals("synthetic-first-token", readSecretFile(path.toString()))
            Files.writeString(path, "synthetic-rotated-token\n")
            assertEquals("synthetic-rotated-token", readSecretFile(path.toString()))
            Files.delete(path)
            val failure = assertFailsWith<IllegalStateException> { readSecretFile(path.toString()) }
            assertFalse(failure.toString().contains(path.toString()))
            assertFalse(failure.toString().contains("synthetic-first-token"))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `empty malformed UTF-8 oversized and relative sources fail without exposing contents`() {
        val directory = Files.createTempDirectory("grz-secret-source-test-")
        try {
            val path = directory.resolve("token")
            for (bytes in listOf(" \n\t".toByteArray(), "private-marker".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28),
                ("private-marker" + "x".repeat(65536)).toByteArray())) {
                Files.write(path, bytes)
                val failure = assertFailsWith<IllegalArgumentException> { readSecretFile(path.toString()) }
                assertFalse(failure.toString().contains("private-marker"))
                assertNull(failure.cause)
            }
            assertFailsWith<IllegalArgumentException> { readSecretFile("relative-token-file") }
            assertFailsWith<IllegalStateException> { readSecretFile(directory.toString()) }
        } finally { directory.toFile().deleteRecursively() }
    }
}
