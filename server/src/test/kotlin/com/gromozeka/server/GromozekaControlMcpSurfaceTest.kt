package com.gromozeka.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito

class GromozekaControlMcpSurfaceTest {
    @Test
    fun `control MCP exposes the complete server-managed configuration surface`() {
        val server = GromozekaControlMcpServerFactory(
            listOf(
                ControlMcpProjectWorkspaceTools(
                    projectService = mock(),
                    workspaceService = mock(),
                    workspaceManagementService = mock(),
                    workerRegistry = mock(),
                ),
                ControlMcpAgentCatalogTools(
                    agentService = mock(),
                    promptService = mock(),
                    skillService = mock(),
                    templateService = mock(),
                ),
                ControlMcpAiSettingsTools(
                    aiConfigurationService = mock(),
                    aiCatalogManagementService = mock(),
                    settingsService = mock(),
                ),
            )
        ).create()

        assertEquals(
            setOf(
                "grz_control_help",
                "grz_project_list",
                "grz_project_get",
                "grz_project_create",
                "grz_project_update",
                "grz_project_delete",
                "grz_workspace_list",
                "grz_workspace_get",
                "grz_workspace_create",
                "grz_workspace_update",
                "grz_workspace_delete",
                "grz_workspace_mount_create",
                "grz_workspace_mount_delete",
                "grz_worker_list",
                "grz_runtime_template_get",
                "grz_agent_list",
                "grz_agent_get",
                "grz_agent_create",
                "grz_agent_update",
                "grz_agent_duplicate",
                "grz_agent_delete",
                "grz_prompt_list",
                "grz_prompt_get",
                "grz_prompt_create",
                "grz_prompt_update",
                "grz_prompt_delete",
                "grz_skill_list",
                "grz_skill_get",
                "grz_skill_import_inline",
                "grz_skill_export",
                "grz_skill_delete",
                "grz_ai_catalog_get",
                "grz_ai_connection_upsert",
                "grz_ai_connection_delete",
                "grz_ai_model_spec_upsert",
                "grz_ai_model_spec_delete",
                "grz_ai_model_configuration_upsert",
                "grz_ai_model_configuration_delete",
                "grz_ai_runtime_assignment_set",
                "grz_default_agent_set",
                "grz_user_profile_get",
                "grz_user_profile_update",
            ),
            server.tools.keys,
        )

        assertTrue(server.toolProperties("grz_agent_create").containsKey("projectId"))
        assertFalse(server.toolProperties("grz_agent_update").containsKey("projectId"))
        assertTrue(server.toolProperties("grz_prompt_create").containsKey("projectId"))
        assertFalse(server.toolProperties("grz_prompt_update").containsKey("projectId"))
        assertTrue(server.tools.getValue("grz_project_delete").tool.annotations?.destructiveHint == true)
        assertTrue(server.tools.getValue("grz_ai_catalog_get").tool.annotations?.readOnlyHint == true)
    }

    private fun io.modelcontextprotocol.kotlin.sdk.server.Server.toolProperties(name: String) =
        requireNotNull(tools.getValue(name).tool.inputSchema?.properties)

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
