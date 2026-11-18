package com.gromozeka.infrastructure.ai.tool

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalScriptSdkTest {
    @Test
    fun `shared content addressed SDK is reused and concurrent materialization is complete`() = withHome { home ->
        val pool = Executors.newFixedThreadPool(4)
        try {
            val directories = (1..16).map { pool.submit<File> { LocalScriptSdk.materialize(home) } }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, directories.toSet().size)
            val directory = directories.first()
            assertEquals(64, directory.name.length)
            listOf("grz.py", "grz.sh", "grz_runner.py").forEach { name ->
                val expected = checkNotNull(javaClass.getResourceAsStream("/script-sdk/$name")).use { it.readBytes() }
                assertTrue(File(directory, name).readBytes().contentEquals(expected))
                val before = File(directory, name).lastModified()
                LocalScriptSdk.materialize(home)
                assertEquals(before, File(directory, name).lastModified())
            }
            assertEquals(setOf("grz.py", "grz.sh", "grz_runner.py"), directory.list()!!.toSet())
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `modified shared SDK is not executed or silently overwritten`() = withHome { home ->
        val script = File(LocalScriptSdk.materialize(home), "grz.py")
        script.writeText("unexpected")
        assertFailsWith<IllegalStateException> { LocalScriptSdk.materialize(home) }
        assertEquals("unexpected", script.readText())
    }

    @Test
    fun `per command paths bind bundled interpreter and preserve Python path without injecting credentials`() = withHome { home ->
        val supplied = mapOf("EXISTING" to "value", "GRZ_MCP_CONTEXT" to "{\"conversation_id\":\"one\"}")
        val inherited = mapOf("PYTHONPATH" to "previous${File.pathSeparator}second", "GRZ_MCP_CONTEXT" to "stale", "PATH" to "untouched")
        val environment = LocalScriptSdk.environment(home, supplied, inherited)
        assertEquals(supplied.getValue("GRZ_MCP_CONTEXT"), environment["GRZ_MCP_CONTEXT"])
        assertEquals(environment["GRZ_SDK_HOME"] + File.pathSeparator + inherited["PYTHONPATH"], environment["PYTHONPATH"])
        assertEquals("value", environment["EXISTING"])
        assertFalse(environment.containsKey("PATH"))
        assertEquals(BundledPythonRuntime.executable().absolutePath, environment["GRZ_PYTHON"])
        assertEquals(environment["GRZ_PYTHON"], LocalScriptSdk.environment(home, mapOf("GRZ_PYTHON" to "unmanaged"))["GRZ_PYTHON"])
        assertFalse(environment.containsKey("GRZ_MCP_TOKEN"))
        assertFalse(environment.containsKey("GRZ_MCP_URL"))
        assertEquals("{}", LocalScriptSdk.environment(home, emptyMap(), inherited)["GRZ_MCP_CONTEXT"])
        val explicit = LocalScriptSdk.environment(home, mapOf("PYTHONPATH" to "explicit"), inherited)
        assertEquals(explicit["GRZ_SDK_HOME"] + File.pathSeparator + "explicit", explicit["PYTHONPATH"])
    }

    @Test
    fun `Python SDK protocol and shell wrapper regressions`() = runPythonSuite("test_grz.py", 28)

    @Test
    fun `Python runner source modes and isolation regressions`() = runPythonSuite("test_grz_runner.py", 8)

    private fun runPythonSuite(name: String, expectedTests: Int) = withHome { home ->
        val script = File(home, name)
        script.writeBytes(checkNotNull(javaClass.getResourceAsStream("/script-sdk/$name")).use { it.readBytes() })
        val python = BundledPythonRuntime.executable().absolutePath
        val output = File(home, "python-test.log")
        val process = ProcessBuilder(python, "-B", "-X", "utf8", script.absolutePath).directory(home)
            .redirectErrorStream(true).redirectOutput(output)
            .apply {
                environment()["GRZ_SDK_HOME"] = LocalScriptSdk.materialize(home).absolutePath
                environment()["PYTHONIOENCODING"] = "utf-8"
            }.start()
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Python SDK tests timed out")
            val log = output.readText()
            println(log)
            assertEquals(0, process.exitValue(), log)
            assertTrue(log.contains("Ran $expectedTests tests"), "All Python test cases must actually execute")
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun withHome(block: (File) -> Unit) {
        val home = Files.createTempDirectory("grz-sdk-test-").toFile()
        try { block(home) } finally { assertTrue(home.deleteRecursively()) }
    }
}
