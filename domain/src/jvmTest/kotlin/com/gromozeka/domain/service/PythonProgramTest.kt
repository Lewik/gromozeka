package com.gromozeka.domain.service

import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolLoadingPolicy
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.ExecutePythonRequest
import com.gromozeka.domain.tool.filesystem.GrzExecutePythonTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PythonProgramTest {
    @Test
    fun `exactly one valid source is required`() {
        assertFailsWith<IllegalArgumentException> { ExecutePythonRequest() }
        assertFailsWith<IllegalArgumentException> { ExecutePythonRequest(code = "print(1)", file_path = "one.py") }
        assertFailsWith<IllegalArgumentException> { ExecutePythonRequest(code = " ") }
        assertFailsWith<IllegalArgumentException> { ExecutePythonRequest(file_path = "") }
        assertFailsWith<IllegalArgumentException> { ExecutePythonRequest(code = "a\u0000b") }
        assertFailsWith<IllegalArgumentException> { ExecutePythonRequest(file_path = "one.py", arguments = listOf("a\u0000b")) }
    }

    @Test
    fun `launch identity covers source mode full source and ordered arguments`() {
        val first = PythonProgram(code = "print(1)", arguments = listOf("one", "two"))
        assertEquals(first.commandIdentity, first.copy().commandIdentity)
        assertNotEquals(first.commandIdentity, first.copy(code = "print(2)").commandIdentity)
        assertNotEquals(first.commandIdentity, first.copy(arguments = listOf("two", "one")).commandIdentity)
        assertNotEquals(PythonProgram(code = "script.py").commandIdentity, PythonProgram(filePath = "script.py").commandIdentity)
        assertNotEquals(PythonProgram(filePath = "one.py").commandIdentity, PythonProgram(filePath = "two.py").commandIdentity)
    }

    @Test
    fun `Python tool declares preload policy and workspace routing`() {
        val tool = object : GrzExecutePythonTool {
            override fun execute(request: ExecutePythonRequest, context: ToolExecutionContext?): List<AiToolResult> = error("Not executed")
        }
        assertEquals(AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE, tool.metadata.loadingPolicy)
        assertEquals(AiToolExecutionScope.WORKSPACE, tool.metadata.executionScope)
    }
}
