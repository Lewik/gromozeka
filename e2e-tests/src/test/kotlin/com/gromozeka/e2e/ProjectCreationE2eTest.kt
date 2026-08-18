package com.gromozeka.e2e

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class ProjectCreationE2eTest {
    @Test
    fun createsProjectThroughComposeAndPersistsItOnServer() = runGromozekaUiTest("project-creation") { client ->
        val projectName = "Compose project ${UUID.randomUUID().toString().take(8)}"
        val description = "Created by the real Compose E2E client"

        waitForTag(UiTestTag.ManageProjectsButton)
        onNodeWithTag(UiTestTag.ManageProjectsButton.value).performClick()
        waitForTag(UiTestTag.ProjectManager)
        onNodeWithTag(UiTestTag.NewProjectButton.value).performClick()
        waitForTag(UiTestTag.ProjectEditorDialog)
        onNodeWithTag(UiTestTag.ProjectNameInput.value).performTextInput(projectName)
        onNodeWithTag(UiTestTag.ProjectDescriptionInput.value).performTextInput(description)
        onNodeWithTag(UiTestTag.ProjectSaveButton.value).performClick()

        waitUntil(timeoutMillis = 30_000) {
            runBlocking { client.components.projectService.findAll() }.any { it.name == projectName }
        }
        val project = runBlocking { client.components.projectService.findAll() }.single { it.name == projectName }
        waitForTag(UiTestTag.ProjectItem(project.id.value))
        onNodeWithTag(UiTestTag.ProjectItem(project.id.value).value).assertTextContains(projectName)
        assertTrue(project.description == description)
    }
}
