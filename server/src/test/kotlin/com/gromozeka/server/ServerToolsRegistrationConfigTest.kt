package com.gromozeka.server

import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.CommandRuntimeStateService
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.infrastructure.ai.config.ServerToolsRegistrationConfig
import com.gromozeka.infrastructure.ai.config.TypedToolCallbackAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.mockito.Mockito
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ServerToolsRegistrationConfigTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(ServerToolsRegistrationConfig::class.java)
        .withBean(SettingsProvider::class.java, { Mockito.mock(SettingsProvider::class.java) })
        .withBean(AiConfigurationProvider::class.java, { Mockito.mock(AiConfigurationProvider::class.java) })
        .withBean(CommandRuntimeStateService::class.java, { Mockito.mock(CommandRuntimeStateService::class.java) })
        .withBean(TypedToolCallbackAdapter::class.java, { TypedToolCallbackAdapter() })

    @Test
    fun `server registers command and monitor listing tool`() {
        runner.run { context ->
            val toolNames = context.getBean(AiToolCallbackContributor::class.java)
                .callbacks
                .map { it.definition.name }

            assertEquals(1, toolNames.count { it == "grz_list_commands_and_monitors" })
        }
    }

    @Test
    fun `worker runtime does not create server tool contributor`() {
        runner
            .withPropertyValues("gromozeka.runtime.worker.enabled=true")
            .run { context ->
                assertFalse(context.containsBean("serverToolCallbacks"))
            }
    }
}
