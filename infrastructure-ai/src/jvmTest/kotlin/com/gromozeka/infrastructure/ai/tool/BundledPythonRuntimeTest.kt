package com.gromozeka.infrastructure.ai.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundledPythonRuntimeTest {
    @Test
    fun `only distributed desktop targets are accepted`() {
        assertEquals("macos-arm64", BundledPythonRuntime.target("Mac OS X", "aarch64"))
        assertEquals("linux-x64", BundledPythonRuntime.target("Linux", "amd64"))
        assertEquals("windows-x64", BundledPythonRuntime.target("Windows 11", "amd64"))
        assertFailsWith<IllegalStateException> { BundledPythonRuntime.target("Linux", "aarch64") }
        assertFailsWith<IllegalStateException> { BundledPythonRuntime.target("Mac OS X", "x86_64") }
    }

    @Test
    fun `missing mismatched and incomplete installations fail without a system fallback`() = withDirectory { home ->
        assertFailsWith<IllegalStateException> { BundledPythonRuntime.executable(null, null) }
        assertFailsWith<IllegalStateException> { BundledPythonRuntime.executable(home.path, null, "windows-x64") }
        val runtime = File(home, "runtime/python").apply { mkdirs() }
        val markerFile = File(runtime, "gromozeka-runtime.json")
        markerFile.writeText(marker("different-lock"))
        assertFailsWith<IllegalStateException> { BundledPythonRuntime.executable(home.path, null, "windows-x64") }
        markerFile.writeText(marker(lockHash()))
        assertFailsWith<IllegalStateException> { BundledPythonRuntime.executable(home.path, null, "windows-x64") }
        val executable = File(runtime, "python.exe").apply { writeText("fixture"); setExecutable(true) }
        assertEquals(executable.canonicalFile, BundledPythonRuntime.executable(home.path, null, "windows-x64"))
    }

    @Test
    fun `malformed runtime metadata is an explicit availability error`() = withDirectory { home ->
        val runtime = File(home, "runtime/python").apply { mkdirs() }
        val marker = File(runtime, "gromozeka-runtime.json")
        for (broken in listOf("not JSON", "[]", "{\"format\":{}}")) {
            marker.writeText(broken)
            assertFailsWith<BundledPythonUnavailableException> { BundledPythonRuntime.executable(home.path, null, "windows-x64") }
        }
    }

    @Test
    fun `invalid internal target is not classified as an unavailable installation`() = withDirectory { home ->
        val error = assertFailsWith<IllegalStateException> { BundledPythonRuntime.executable(home.path, null, "not-in-lock") }
        assertFalse(error is BundledPythonUnavailableException)
    }

    @Test
    fun `development uses its lock generation rather than inherited installed Worker`() = withDirectory { home ->
        val runtime = File(home, "build/python-runtime/windows-x64/${lockHash()}").apply { mkdirs() }
        File(runtime, "gromozeka-runtime.json").writeText(marker(lockHash()))
        val executable = File(runtime, "python.exe").apply { writeText("fixture"); setExecutable(true) }
        assertEquals(executable.canonicalFile, BundledPythonRuntime.executable("absent-installation", home.path, "windows-x64"))
    }

    private fun lockBytes() = checkNotNull(javaClass.getResourceAsStream("/script-sdk/python-runtime.json")).use { it.readBytes() }
    private fun lockHash() = MessageDigest.getInstance("SHA-256").digest(lockBytes()).joinToString("") { "%02x".format(it) }
    private fun marker(hash: String) = buildJsonObject {
        put("format", 1)
        put("lock_sha256", hash)
        put("target", "windows-x64")
        put("python_version", Json.parseToJsonElement(lockBytes().decodeToString()).jsonObject.getValue("python_version"))
    }.toString()

    private fun withDirectory(block: (File) -> Unit) {
        val home = Files.createTempDirectory("grz-python-runtime-").toFile()
        try { block(home) } finally { assertTrue(home.deleteRecursively()) }
    }
}
