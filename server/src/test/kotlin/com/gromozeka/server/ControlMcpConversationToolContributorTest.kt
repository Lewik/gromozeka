package com.gromozeka.server

import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ControlMcpConversationToolContributorTest {
    private val user = testControlMcpCaller().user
    private val catalog = ControlMcpToolCatalog(
        listOf(
            object : ControlMcpToolProvider {
                override val tools = listOf(
                    controlMcpTool(
                        name = "grz_test_control",
                        description = "Test control operation.",
                        readOnly = true,
                    ) {
                        buildJsonObject {
                            put("userId", user.id.value)
                        }
                    }
                )
            }
        )
    )

    @Test
    fun `exposes control tools inside conversation runtime with authenticated actor`() {
        val callback = contributor().callbacks.single()

        assertEquals("grz_test_control", callback.definition.name)
        assertEquals("gromozeka:control", callback.definition.source)
        assertEquals(AiToolExecutionScope.CONVERSATION_RUNTIME, callback.metadata.executionScope)
        assertEquals(false, callback.metadata.visibleToMemoryPipeline)

        val result = controlMcpJson.parseToJsonElement(
            callback.call(
                "{}",
                ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to user.id.value)),
            )
        ).jsonObject

        assertEquals(true, result.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals(
            user.id.value,
            result.getValue("result").jsonObject.getValue("userId").jsonPrimitive.content,
        )
    }

    @Test
    fun `rejects conversation control calls without authenticated actor`() {
        val error = assertFailsWith<IllegalStateException> {
            contributor().callbacks.single().call("{}", ToolExecutionContext())
        }

        assertEquals("User id is required in tool execution context", error.message)
    }

    private fun contributor() =
        ControlMcpConversationToolContributor(
            catalog = catalog,
            userDirectoryService = object : UserDirectoryService {
                override suspend fun findActiveById(id: User.Id): User? =
                    user.takeIf { it.id == id }

                override suspend fun listActive(): List<User> = listOf(user)
            },
        )
}
