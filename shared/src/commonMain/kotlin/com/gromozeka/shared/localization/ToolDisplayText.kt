package com.gromozeka.shared.localization

object ToolDisplayText {
    fun name(toolName: String, text: (String) -> String): String {
        val action = toolName.trim().lowercase().replace(Regex("__v\\d+$"), "").substringAfterLast("__")
            .removePrefix("grz_").removePrefix("claude_code_")
        val key = labels.firstOrNull { (fragments, _) -> fragments.any(action::contains) }?.second
        if (key != null) return text("runtime.$key")
        return action.replace(Regex("[^a-z0-9_-]"), " ").replace('_', ' ').replace('-', ' ')
            .replace(Regex("\\s+"), " ").trim().take(48).ifBlank { "unknown" }.split(' ').joinToString(" ") {
                if (it in acronyms) it.uppercase() else it.replaceFirstChar(Char::titlecase)
            }
    }

    private val acronyms = setOf("ai", "api", "http", "https", "id", "json", "lsp", "mcp", "sql", "url")
    private val labels = listOf(
        listOf("computer_targets", "list_displays", "screen_targets") to "listDisplaysToolLabel",
        listOf("computer_observe", "observe_screen") to "observeScreenToolLabel",
        listOf("computer_act", "computer_use") to "useComputerToolLabel",
        listOf("take_screenshot", "capture_screenshot") to "captureScreenToolLabel",
        listOf("read_file", "read_text", "get_file", "load_file") to "readFileToolLabel",
        listOf("write_file", "create_file") to "writeFileToolLabel",
        listOf("edit_file", "apply_patch", "replace_file", "file_change") to "editFileToolLabel",
        listOf("execute_command", "run_command", "exec_command", "shell", "bash") to "executeCommandToolLabel",
        listOf("local_search", "brave_local") to "localSearchToolLabel",
        listOf("web_search", "websearch", "search_query", "brave_search") to "webSearchToolLabel",
        listOf("read_url", "web_fetch", "webfetch", "fetch_url", "open_page") to "readUrlToolLabel",
        listOf("create_agent", "spawn_agent") to "createAgentToolLabel",
        listOf("tell_agent", "send_input") to "tellAgentToolLabel",
        listOf("switch_tab") to "switchTabToolLabel", listOf("list_tabs") to "listTabsToolLabel",
        listOf("hello_world") to "testToolLabel",
    )
}
