package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.presentation.services.translation.data.Translation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun ConversationRuntimeSnapshot.runningToolActivities(
    translation: Translation.RuntimeTranslation,
): List<String> = toolExecutions
    .asSequence()
    .filter { it.status == ConversationRuntimeToolExecution.Status.RUNNING }
    .map { execution ->
        toolActivityCaption(
            toolName = execution.toolName,
            input = toolInput(execution),
            translation = translation,
        )
    }
    .toList()

internal fun toolActivityCaption(
    toolName: String,
    input: JsonElement?,
    translation: Translation.RuntimeTranslation,
): String {
    val actionName = normalizedToolActionName(toolName)
    return when {
        actionName.isCommandTool() && input.isTestCommand() -> translation.runningTestsActivity
        actionName.isCommandTool() -> translation.runningCommandActivity
        actionName.containsAnyFragment("read_file", "read_text", "get_file", "load_file") ->
            translation.readingFileActivity
        actionName.containsAnyFragment("write_file", "edit_file", "apply_patch", "replace_file", "file_change") ->
            translation.editingFileActivity
        actionName.containsAnyFragment("web_search", "websearch", "search_query", "brave_search") ->
            translation.searchingWebActivity
        actionName.containsAnyFragment("read_url", "web_fetch", "webfetch", "fetch_url", "open_page") ->
            translation.readingWebActivity
        actionName.containsAnyFragment("grep", "glob", "search_files", "find_files", "search_code") ->
            translation.searchingFilesActivity
        actionName.containsAnyFragment("take_screenshot", "capture_screenshot") -> translation.capturingScreenActivity
        actionName.containsAnyFragment("computer_observe", "observe_screen") -> translation.observingScreenActivity
        actionName.containsAnyFragment("computer_act", "computer_use") -> translation.usingComputerActivity
        actionName.contains("browser") -> translation.usingBrowserActivity
        actionName.containsAnyFragment("create_agent", "tell_agent", "send_input", "spawn_agent", "wait_agent") ->
            translation.coordinatingAgentsActivity
        actionName.contains("skill") -> translation.usingSkillActivity
        actionName.contains("memory") -> translation.accessingMemoryActivity
        else -> "${translation.usingToolActivity}: ${humanizedToolActionName(actionName)}"
    }
}

private fun ConversationRuntimeSnapshot.toolInput(execution: ConversationRuntimeToolExecution): JsonElement? {
    val runtimeTaskId = execution.runtimeTaskId ?: return null
    val payload = sequenceOf(activeTask, continuationTask)
        .plus(activeInsertions.asSequence())
        .filterNotNull()
        .firstOrNull { it.id == runtimeTaskId }
        ?.payload as? ConversationRuntimeTask.Payload.ToolExecution
        ?: return null
    return payload.toolCalls.firstOrNull { it.id == execution.toolCallId }?.call?.input
}

private fun String.isCommandTool(): Boolean = containsAnyFragment(
    "execute_command",
    "run_command",
    "exec_command",
    "shell",
    "bash",
)

private fun JsonElement?.isTestCommand(): Boolean {
    val command = runCatching {
        this?.jsonObject?.get("command")?.jsonPrimitive?.contentOrNull
            ?: this?.jsonObject?.get("cmd")?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.lowercase() ?: return false
    return listOf(
        "gradlew test",
        "gradlew check",
        ":test",
        "pytest",
        "npm test",
        "npm run test",
        "pnpm test",
        "yarn test",
        "cargo test",
        "go test",
        "mvn test",
        "mvn verify",
        "vitest",
        "jest",
        "xcodebuild test",
        "dotnet test",
    ).any(command::contains)
}
