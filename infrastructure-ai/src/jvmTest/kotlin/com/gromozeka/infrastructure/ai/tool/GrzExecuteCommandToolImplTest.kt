package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.tool.ToolCancellationSignal
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GrzExecuteCommandToolImplTest {
    private val tool = GrzExecuteCommandToolImpl()

    @Test
    fun `execute command drains large output without pipe deadlock`() {
        withTemporaryGromozekaHome {
            val result = tool.execute(
                ExecuteCommandRequest(
                    command = "i=0; while [ ${'$'}i -lt 20000 ]; do echo line-${'$'}i; i=${'$'}((i+1)); done",
                    timeout_seconds = 10,
                ),
                ToolExecutionContext(mapOf("projectPath" to ".")),
            )

            assertEquals(true, result["success"])
            assertEquals(true, result["output_truncated"])

            val output = result["output"] as String
            assertTrue(output.contains("line-0"))
            assertTrue(output.contains("line-19999"))
            assertTrue(output.contains("output truncated"))

            val outputFile = File(assertNotNull(result["output_file"] as? String))
            assertTrue(outputFile.exists())
            assertTrue(outputFile.readText().contains("line-19999"))
        }
    }

    @Test
    fun `execute command returns full preview for small output and saves output file`() {
        withTemporaryGromozekaHome {
            val result = tool.execute(
                ExecuteCommandRequest(
                    command = "printf 'hello from command'",
                    timeout_seconds = 10,
                ),
                ToolExecutionContext(mapOf("projectPath" to ".")),
            )

            assertEquals(true, result["success"])
            assertEquals(false, result["output_truncated"])
            assertEquals("hello from command", result["output"])

            val outputFile = File(assertNotNull(result["output_file"] as? String))
            assertTrue(outputFile.exists())
            assertEquals("hello from command", outputFile.readText())
        }
    }

    @Test
    fun `execute command returns partial output artifact on timeout`() {
        withTemporaryGromozekaHome {
            val result = tool.execute(
                ExecuteCommandRequest(
                    command = "echo before-timeout; sleep 30",
                    timeout_seconds = 1,
                ),
                ToolExecutionContext(mapOf("projectPath" to ".")),
            )

            assertEquals(false, result["success"])
            assertTrue((result["error"] as String).contains("timed out"))

            val outputFile = File(assertNotNull(result["output_file"] as? String))
            assertTrue(outputFile.exists())
            assertTrue(outputFile.readText().contains("before-timeout"))
        }
    }

    @Test
    fun `execute command kills process when tool context is cancelled`() {
        val startedAt = System.nanoTime()
        val durationMillis = measureTimeMillis {
            assertFailsWith<CancellationException> {
                tool.execute(
                    ExecuteCommandRequest(
                        command = "sleep 30",
                        timeout_seconds = 60,
                    ),
                    ToolExecutionContext(
                        values = mapOf("projectPath" to "."),
                        cancellationSignal = ToolCancellationSignal {
                            if (System.nanoTime() - startedAt > 250_000_000L) {
                                throw CancellationException("test cancellation")
                            }
                        },
                    ),
                )
            }
        }

        assertTrue(durationMillis < 5_000, "Command cancellation took ${durationMillis}ms")
    }

    private fun withTemporaryGromozekaHome(block: (File) -> Unit) {
        val previousHome = System.getProperty("GROMOZEKA_HOME")
        val home = Files.createTempDirectory("gromozeka-tool-output-test-").toFile()
        try {
            System.setProperty("GROMOZEKA_HOME", home.absolutePath)
            block(home)
        } finally {
            if (previousHome == null) {
                System.clearProperty("GROMOZEKA_HOME")
            } else {
                System.setProperty("GROMOZEKA_HOME", previousHome)
            }
            assertFalse(home.exists() && !home.deleteRecursively())
        }
    }
}
