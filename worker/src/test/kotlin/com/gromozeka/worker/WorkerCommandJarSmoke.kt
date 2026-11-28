package com.gromozeka.worker

import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.PythonProgram
import com.gromozeka.domain.service.RunningCommandProcess
import com.gromozeka.infrastructure.ai.tool.BundledPythonRuntime
import com.gromozeka.infrastructure.ai.tool.LocalCommandProcessRunner
import java.io.File
import java.nio.file.Files

/** Test-only entrypoint loaded by PropertiesLauncher with production classes from the fat JAR. */
object WorkerCommandJarSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        val source = checkNotNull(LocalCommandProcessRunner::class.java.getResource("LocalCommandProcessRunner.class")).toExternalForm()
        check(source.startsWith("jar:") && source.contains("gromozeka-worker.jar") && source.contains("BOOT-INF/lib/")) {
            "Smoke must load the production runner from the standalone Worker JAR: $source"
        }
        println("production_runner_source=$source")
        val home = Files.createTempDirectory("gromozeka-jar-command-smoke-").toFile()
        System.setProperty("GROMOZEKA_HOME", home.absolutePath)
        System.clearProperty("gromozeka.project.root")
        val runner = LocalCommandProcessRunner()
        var failure: Throwable? = null
        try {
            expectUnavailable { BundledPythonRuntime.executable() }
            val native = start(runner, home, "native", "echo OK")
            check(native.first == 0 && native.second.trim() == "OK") { "Native shell failed: $native" }
            println("native_shell_without_python=OK")
            if (!System.getProperty("os.name").startsWith("Windows")) {
                val sdk = start(runner, home, "sdk", ". \"\${GRZ_SDK_HOME}/grz.sh\"; grz_list_tools")
                check(sdk.first != 0 && sdk.second.contains("Managed Python is unavailable")) {
                    "Shell SDK must explicitly reject missing Python instead of choosing a system interpreter: $sdk"
                }
                println("shell_sdk_without_python=explicit_failure")
            }
            val program = PythonProgram(code = "raise AssertionError('Python must not run')")
            var python: RunningCommandProcess? = null
            try {
                expectUnavailable {
                    python = runner.start(CommandProcessSpec(executionId = "python", command = program.commandIdentity,
                        workingDirectory = home.absolutePath, pythonProgram = program))
                }
            } finally {
                python?.let { if (it.isAlive()) it.terminateTree(); check(it.waitFor(20_000)) { "Unexpected Python process did not stop" } }
            }
            println("python_without_runtime=explicit_failure")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!home.deleteRecursively()) {
                val cleanup = IllegalStateException("Smoke output was not cleaned up: $home")
                failure?.addSuppressed(cleanup) ?: throw cleanup
            }
        }
    }

    private fun start(runner: LocalCommandProcessRunner, home: File, id: String, command: String): Pair<Int, String> {
        val process = runner.start(CommandProcessSpec(executionId = id, command = command, workingDirectory = home.absolutePath))
        try {
            check(process.waitFor(20_000)) { "Smoke command did not finish" }
            return checkNotNull(process.exitCode()) to File(process.outputFile).readText()
        } finally {
            if (process.isAlive()) process.terminateTree()
            check(process.waitFor(20_000)) { "Smoke process did not stop" }
        }
    }

    private fun expectUnavailable(block: () -> Unit) {
        val failure = try { block(); null } catch (error: IllegalStateException) { error }
        check(failure?.message?.let {
            it.startsWith("Bundled Python location is unavailable") || it.startsWith("Bundled Python is not prepared at ")
        } == true) {
            "Expected an explicit bundled-Python availability error, got $failure"
        }
    }
}
