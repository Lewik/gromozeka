package com.gromozeka.presentation.ui.session

import com.gromozeka.presentation.services.translation.data.RussianTranslation
import com.gromozeka.presentation.services.translation.data.Translation
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolDisplayNameTest {
    private val english = Translation.RuntimeTranslation()
    private val russian = RussianTranslation().runtime

    @Test
    fun `resolves computer use labels`() {
        assertEquals("List Displays", toolDisplayName("grz_computer_targets", english))
        assertEquals("Observe Screen", toolDisplayName("grz_computer_observe", english))
        assertEquals("Use Computer", toolDisplayName("grz_computer_act", english))
    }

    @Test
    fun `resolves localized labels`() {
        assertEquals("Просмотреть экран", toolDisplayName("grz_computer_observe", russian))
        assertEquals("Выполнить команду", toolDisplayName("grz_execute_command", russian))
    }

    @Test
    fun `normalizes external names and versions`() {
        assertEquals("Read URL", toolDisplayName("mcp__browser__web_fetch__v2", english))
        assertEquals("Deploy Release", toolDisplayName("mcp__private-host__deploy_release__v3", english))
    }
}
