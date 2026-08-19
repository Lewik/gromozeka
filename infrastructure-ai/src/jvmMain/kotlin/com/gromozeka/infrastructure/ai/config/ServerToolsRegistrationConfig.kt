package com.gromozeka.infrastructure.ai.config

import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.infrastructure.ai.tool.GrzListCommandsAndMonitorsToolImpl
import com.gromozeka.infrastructure.ai.tool.web.BraveLocalSearchTool
import com.gromozeka.infrastructure.ai.tool.web.BraveWebSearchTool
import com.gromozeka.infrastructure.ai.tool.web.JinaReadUrlTool
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
@Import(
    GrzListCommandsAndMonitorsToolImpl::class,
    BraveLocalSearchTool::class,
    BraveWebSearchTool::class,
    JinaReadUrlTool::class,
)
class ServerToolsRegistrationConfig {
    @Bean
    fun serverToolCallbacks(
        braveLocalSearchTool: BraveLocalSearchTool,
        braveWebSearchTool: BraveWebSearchTool,
        jinaReadUrlTool: JinaReadUrlTool,
        grzListCommandsAndMonitorsTool: GrzListCommandsAndMonitorsToolImpl,
        adapter: TypedToolCallbackAdapter,
    ): AiToolCallbackContributor = ToolCallbacksRegistrar(
        listOf(
            adapter.adapt(braveLocalSearchTool),
            adapter.adapt(braveWebSearchTool),
            adapter.adapt(jinaReadUrlTool),
            adapter.adapt(grzListCommandsAndMonitorsTool),
        )
    )
}
