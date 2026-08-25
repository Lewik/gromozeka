package com.gromozeka.presentation.ui.session

import com.gromozeka.presentation.services.translation.data.Translation

internal fun toolDisplayName(
    toolName: String,
    translation: Translation.RuntimeTranslation,
): String {
    val actionName = normalizedToolActionName(toolName)
    return when {
        actionName.containsAnyFragment("computer_targets", "list_displays", "screen_targets") ->
            translation.listDisplaysToolLabel
        actionName.containsAnyFragment("computer_observe", "observe_screen") ->
            translation.observeScreenToolLabel
        actionName.containsAnyFragment("computer_act", "computer_use") ->
            translation.useComputerToolLabel
        actionName.containsAnyFragment("take_screenshot", "capture_screenshot") ->
            translation.captureScreenToolLabel
        actionName.containsAnyFragment("read_file", "read_text", "get_file", "load_file") ->
            translation.readFileToolLabel
        actionName.containsAnyFragment("write_file", "create_file") -> translation.writeFileToolLabel
        actionName.containsAnyFragment("edit_file", "apply_patch", "replace_file", "file_change") ->
            translation.editFileToolLabel
        actionName.containsAnyFragment("execute_command", "run_command", "exec_command", "shell", "bash") ->
            translation.executeCommandToolLabel
        actionName.containsAnyFragment("local_search", "brave_local") -> translation.localSearchToolLabel
        actionName.containsAnyFragment("web_search", "websearch", "search_query", "brave_search") ->
            translation.webSearchToolLabel
        actionName.containsAnyFragment("read_url", "web_fetch", "webfetch", "fetch_url", "open_page") ->
            translation.readUrlToolLabel
        actionName.containsAnyFragment("create_agent", "spawn_agent") -> translation.createAgentToolLabel
        actionName.containsAnyFragment("tell_agent", "send_input") -> translation.tellAgentToolLabel
        actionName.contains("switch_tab") -> translation.switchTabToolLabel
        actionName.contains("list_tabs") -> translation.listTabsToolLabel
        actionName.contains("hello_world") -> translation.testToolLabel
        else -> humanizedToolActionName(actionName).toDisplayTitle()
    }
}

private val uppercaseToolNameWords = setOf(
    "ai",
    "api",
    "http",
    "https",
    "id",
    "json",
    "lsp",
    "mcp",
    "sql",
    "url",
)

private fun String.toDisplayTitle(): String = split(' ')
    .joinToString(" ") { word ->
        if (word in uppercaseToolNameWords) {
            word.uppercase()
        } else {
            word.replaceFirstChar { character -> character.titlecase() }
        }
    }
