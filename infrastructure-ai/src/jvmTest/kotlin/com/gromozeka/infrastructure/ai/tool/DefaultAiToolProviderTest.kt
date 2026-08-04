package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.infrastructure.ai.config.TypedToolCallbackAdapter
import com.gromozeka.infrastructure.ai.config.ToolsRegistrationConfig
import com.gromozeka.infrastructure.ai.config.ToolCallbacksRegistrar
import com.gromozeka.infrastructure.ai.config.mcp.McpConfigurationService
import org.springframework.context.support.StaticApplicationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DefaultAiToolProviderTest {
    @Test
    fun `exposes adapted local tools deterministically`() {
        val provider = provider(
            declaredCallbacks = listOf(testCallback("declared")),
            localTools = listOf(TestTool()),
        )

        assertEquals(
            setOf("declared", "test_tool"),
            provider.getTools().map { it.definition.name }.toSet(),
        )
    }

    @Test
    fun `fails fast when tool names collide`() {
        val provider = provider(
            declaredCallbacks = listOf(testCallback("test_tool")),
            localTools = listOf(TestTool()),
        )

        val error = assertFailsWith<IllegalStateException> {
            provider.getTools()
        }
        assertEquals("AI tool names must be unique: test_tool", error.message)
    }

    @Test
    fun `omits callbacks that are not currently available`() {
        val provider = provider(
            declaredCallbacks = listOf(
                testCallback("enabled"),
                testCallback("disabled", available = false),
            ),
            localTools = emptyList(),
        )

        assertEquals(listOf("enabled"), provider.getTools().map { it.definition.name })
    }

    @Test
    fun `typed tool preserves mixed text and binary results`() {
        val bytes = byteArrayOf(1, 2, 3)
        val callback = TypedToolCallbackAdapter().adapt(MixedResultTool(bytes))

        val result = callback.callResult("{}", ToolExecutionContext())

        assertEquals(AiToolResult.Text("observation"), result[0])
        val binary = result[1] as AiToolResult.Binary
        assertEquals("screen.png", binary.fileName)
        assertEquals("image/png", binary.mediaType)
        assertEquals(bytes.toList(), binary.content.toList())
    }

    private fun provider(
        declaredCallbacks: List<AiToolCallback>,
        localTools: List<Tool<*, *>>,
    ): AiToolProvider {
        val context = StaticApplicationContext()
        declaredCallbacks.forEachIndexed { index, callback ->
            context.beanFactory.registerSingleton("declaredCallback$index", callback)
        }
        val registrar = ToolsRegistrationConfig(TypedToolCallbackAdapter()).toolCallbacksRegistrar(localTools)
        context.beanFactory.registerSingleton("toolCallbacksRegistrar", registrar)

        return DefaultAiToolProvider(
            applicationContext = context,
            callbackContributors = context.getBeanProvider(AiToolCallbackContributor::class.java),
            mcpConfigurationService = context.getBeanProvider(McpConfigurationService::class.java),
        )
    }

    private fun testCallback(
        name: String,
        available: Boolean = true,
    ): AiToolCallback =
        object : AiToolCallback {
            override val available = available
            override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.WORKER)
            override val definition = AiToolDefinition(
                name = name,
                description = "Test callback",
                inputSchema = """{"type":"object","properties":{}}""",
            )

            override fun call(toolInput: String, context: ToolExecutionContext?): String = "ok"
        }

    data class TestRequest(val value: String = "")

    class TestTool : Tool<TestRequest, String> {
        override val name = "test_tool"
        override val description = "Test tool"
        override val requestType = TestRequest::class.java
        override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.WORKER)

        override fun execute(request: TestRequest, context: ToolExecutionContext?): String = request.value
    }

    class MixedResultTool(
        private val bytes: ByteArray,
    ) : Tool<TestRequest, List<AiToolResult>> {
        override val name = "mixed_result_tool"
        override val description = "Mixed result tool"
        override val requestType = TestRequest::class.java
        override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.WORKER)

        override fun execute(
            request: TestRequest,
            context: ToolExecutionContext?,
        ): List<AiToolResult> = listOf(
            AiToolResult.Text("observation"),
            AiToolResult.Binary(bytes, "screen.png", "image/png"),
        )
    }
}
