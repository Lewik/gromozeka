package com.gromozeka.infrastructure.ai.copilot

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.SettingsProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class GitHubCopilotContextQueryTest {
    @Test fun `strict query is rejected before credentials SDK or filesystem initialization`() = runBlocking {
        val connection = AiConnection.GitHubCopilot(AiConnection.Id("copilot"), "Copilot", enabled = true,
            authMode = AiConnection.GitHubCopilotAuthMode.PER_USER_TOKEN)
        val untouchedSettings = object : SettingsProvider {
            override val userProfile: UserProfile get() = error("Must not read settings")
            override val userDeviceSettings: UserDeviceSettings get() = error("Must not read settings")
            override val mode: AppMode get() = error("Must not read settings")
            override val homeDirectory: String get() = error("Must not create SDK home")
        }
        val pool = GitHubCopilotClientPool(untouchedSettings)
        try {
            val runtime = GitHubCopilotRuntime(connection,
                AiModelConfiguration(AiModelConfiguration.Id("model"), connection.id, "model", "Model"), pool, null)
            val error = assertFailsWith<UnsupportedOperationException> {
                runtime.call(AiRuntimeRequest(emptyList(), emptyList(), options = AiRuntimeOptions(
                    usagePurpose = AI_CONTEXT_QUERY_PURPOSE, toolChoice = AiToolChoice.None)))
            }
            assertContains(error.message.orEmpty(), "strictly tool-free")
        } finally { pool.close() }
    }
}
