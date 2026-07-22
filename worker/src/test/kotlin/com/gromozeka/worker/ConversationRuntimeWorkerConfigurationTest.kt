package com.gromozeka.worker

import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolMetadata
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationRuntimeWorkerConfigurationTest {
    private fun contextRunner(tools: List<AiToolCallback> = emptyList()) =
        ApplicationContextRunner()
            .withUserConfiguration(ConversationRuntimeWorkerConfiguration::class.java)
            .withBean(AiToolProvider::class.java, {
                object : AiToolProvider {
                    override fun getTools(): List<AiToolCallback> = tools
                }
            })

    @Test
    fun `binds worker identity and capabilities without filesystem configuration`() {
        contextRunner()
            .withPropertyValues(
                "gromozeka.runtime.worker.id=macbook-primary",
                "gromozeka.runtime.worker.capabilities[0]=TOOL_EXECUTION",
                "gromozeka.runtime.worker.capabilities[1]=LOCAL_AGENT_TOOL",
            )
            .run { context ->
                val descriptor = context.getBean(ConversationRuntimeWorkerDescriptor::class.java)

                assertEquals("macbook-primary", descriptor.id.value)
                assertEquals(
                    setOf(
                        ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                        ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
                    ),
                    descriptor.capabilities,
                )
                assertEquals(emptyList(), descriptor.tools)
            }
    }

    @Test
    fun `fails fast without capabilities`() {
        contextRunner()
            .withPropertyValues("gromozeka.runtime.worker.id=worker-1")
            .run { context ->
                assertNotNull(context.startupFailure)
                assertTrue(
                    context.startupFailure
                        ?.causeChain()
                        ?.any { it.message?.contains("must declare at least one capability") == true } == true
                )
            }
    }

    @Test
    fun `llm worker does not advertise executable tools`() {
        contextRunner(
            listOf(
                TestTool("generic"),
                TestTool(
                    "local",
                    setOf(ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL),
                ),
            )
        )
            .withPropertyValues(
                "gromozeka.runtime.worker.id=llm-worker",
                "gromozeka.runtime.worker.capabilities[0]=LLM_RUNTIME",
                "gromozeka.runtime.worker.capabilities[1]=MEMORY_PIPELINE",
            )
            .run { context ->
                val descriptor = context.getBean(ConversationRuntimeWorkerDescriptor::class.java)
                assertTrue(descriptor.tools.isEmpty())
            }
    }

    @Test
    fun `tool worker advertises only tools supported by its capabilities`() {
        contextRunner(
            listOf(
                TestTool("generic"),
                TestTool(
                    "local",
                    setOf(ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL),
                ),
            )
        )
            .withPropertyValues(
                "gromozeka.runtime.worker.id=tool-worker",
                "gromozeka.runtime.worker.capabilities[0]=TOOL_EXECUTION",
            )
            .run { context ->
                val descriptor = context.getBean(ConversationRuntimeWorkerDescriptor::class.java)
                assertEquals(listOf("generic"), descriptor.tools.map { it.definition.name })
            }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause }
}

private class TestTool(
    name: String,
    requiredCapabilities: Set<ConversationRuntimeWorkerCapability> = emptySet(),
) : AiToolCallback {
    override val definition = AiToolDefinition(
        name = name,
        description = name,
        inputSchema = """{"type":"object"}""",
    )
    override val metadata = AiToolMetadata(requiredRuntimeCapabilities = requiredCapabilities)

    override fun call(toolInput: String, context: com.gromozeka.domain.tool.ToolExecutionContext?): String =
        error("Not used")
}
