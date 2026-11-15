package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.PythonProgram
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagedPythonProcessTest {
    @Test
    fun `inline Python stays behind native admission gate and its artifacts are managed`() = withHome { home ->
        val workspace = File(home, "workspace").apply { mkdirs() }
        val marker = File(workspace, "effect.txt")
        val program = PythonProgram(code = "from pathlib import Path\nPath('effect.txt').write_text('done')\nprint('PYTHON_OK')")
        val runner = LocalCommandProcessRunner()
        val process = runner.start(spec(program, workspace).copy(deferStartUntilRegistration = true))
        try {
            assertFalse(marker.exists(), "Python must not execute before admission")
            assertTrue(File(process.outputFile + ".python.py").isFile)
            assertTrue(File(process.outputFile + ".python.json").isFile)
            process.releaseStart()
            assertTrue(process.waitFor(15_000), "Python process did not finish")
            assertEquals(0, process.exitCode(), File(process.outputFile).readText())
            assertEquals("done", marker.readText())
            assertTrue(File(process.outputFile).readText().contains("PYTHON_OK"))
        } finally {
            if (process.isAlive()) process.terminateTree()
        }
        runner.deleteOutputArtifacts(process.outputFile)
        assertFalse(File(process.outputFile + ".python.py").exists())
        assertFalse(File(process.outputFile + ".python.json").exists())
        assertTrue(marker.exists(), "Artifact cleanup must not roll back script effects")
    }

    @Test
    fun `file source and literal arguments do not become shell syntax`() = withHome { home ->
        val workspace = File(home, "workspace with spaces").apply { mkdirs() }
        val source = File(workspace, "program %PATH% & שלום.py").apply {
            writeText("import json, sys\nprint(json.dumps(sys.argv[1:], ensure_ascii=False))\n")
        }
        val arguments = listOf("", "two words", "${'$'}(touch injected)", "%PATH%!&", "שלום 😀", "line\nbreak")
        val program = PythonProgram(filePath = source.name, arguments = arguments)
        val runner = LocalCommandProcessRunner()
        val process = runner.start(spec(program, workspace))
        try {
            assertTrue(process.waitFor(15_000))
            val output = File(process.outputFile).readText()
            assertEquals(0, process.exitCode(), output)
            assertEquals(arguments, Json.parseToJsonElement(output.trim()).jsonArray.map { it.jsonPrimitive.content })
            assertFalse(File(workspace, "injected").exists())
        } finally {
            if (process.isAlive()) process.terminateTree()
        }
        runner.deleteOutputArtifacts(process.outputFile)
        assertTrue(source.isFile, "An existing user script must never be deleted as a launch artifact")
    }

    @Test
    fun `Python execution uses existing process tree cancellation`() = withHome { home ->
        val workspace = File(home, "workspace").apply { mkdirs() }
        val marker = File(workspace, "started")
        val program = PythonProgram(code = "import time\nfrom pathlib import Path\nPath('started').write_text('yes')\nwhile True: time.sleep(0.05)\n")
        val process = LocalCommandProcessRunner().start(spec(program, workspace))
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!marker.isFile && System.nanoTime() < deadline && process.isAlive()) Thread.sleep(20)
            assertTrue(marker.isFile, File(process.outputFile).readText())
            process.terminateTree()
            assertTrue(process.waitFor(10_000))
            assertFalse(process.isAlive())
        } finally {
            if (process.isAlive()) process.terminateTree()
        }
    }

    @Test
    fun `missing source fails before launch and leaves no output companions`() = withHome { home ->
        val workspace = File(home, "workspace").apply { mkdirs() }
        val program = PythonProgram(filePath = "missing.py")
        assertFailsWith<IllegalArgumentException> { LocalCommandProcessRunner().start(spec(program, workspace)) }
        assertTrue(File(home, "tool-outputs").listFiles().orEmpty().isEmpty())
    }

    private fun spec(program: PythonProgram, workspace: File) = CommandProcessSpec(
        executionId = UUID.randomUUID().toString(),
        command = program.commandIdentity,
        workingDirectory = workspace.absolutePath,
        pythonProgram = program,
    )

    private fun withHome(block: (File) -> Unit) {
        val home = Files.createTempDirectory("grz-python-process-").toFile()
        val previous = System.getProperty("GROMOZEKA_HOME")
        try {
            System.setProperty("GROMOZEKA_HOME", home.absolutePath)
            block(home)
        } finally {
            if (previous == null) System.clearProperty("GROMOZEKA_HOME") else System.setProperty("GROMOZEKA_HOME", previous)
            assertTrue(home.deleteRecursively())
        }
    }
}
