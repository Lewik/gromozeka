package com.gromozeka.application.service

import com.gromozeka.domain.model.RuntimeEnvironmentContext
import org.springframework.stereotype.Service
import java.io.File
import java.time.LocalDate

/**
 * Builds environment information for Dynamic prompts.
 * 
 * Used exclusively by PromptApplicationService for Dynamic.Environment prompts.
 * All other system prompt assembly happens through agent.prompts.
 */
@Service
class SystemPromptBuilder {
    /**
     * Generates environment information block.
     * 
     * @param runtimeContext environment visible to the executing worker
     * @return Environment info block wrapped in <env> tag
     */
    fun buildEnvironmentInfo(runtimeContext: RuntimeEnvironmentContext): String {
        val platform = System.getProperty("os.name")
        val osVersion = System.getProperty("os.version")
        val todayDate = LocalDate.now()

        return buildString {
            appendLine("<env>")
            appendLine("Runtime worker: ${runtimeContext.workerId}")
            when (runtimeContext) {
                is RuntimeEnvironmentContext.Standalone ->
                    appendLine("Runtime scope: standalone; no Project or Filesystem Workspace is selected")

                is RuntimeEnvironmentContext.WorkspaceBound -> {
                    appendLine(
                        "Project: ${runtimeContext.project.name} " +
                            "(${runtimeContext.project.id.value})"
                    )
                    appendLine(
                        "Filesystem workspace: ${runtimeContext.workspace.name} " +
                            "(${runtimeContext.workspace.id.value})"
                    )
                    runtimeContext.workspaceRootPath?.let { rootPath ->
                        appendLine("Workspace mounted on runtime worker: Yes")
                        appendLine("Workspace root path on runtime worker: $rootPath")
                        appendLine(
                            "Is directory a git repo: " +
                                if (File(rootPath, ".git").exists()) "Yes" else "No"
                        )
                    } ?: appendLine("Workspace mounted on runtime worker: No")
                }
            }
            appendLine("Platform: $platform")
            appendLine("OS Version: $osVersion")
            appendLine("Today's date: $todayDate")
            append("</env>")
        }
    }
}
