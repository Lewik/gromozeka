package com.gromozeka.presentation.ui.session

internal fun normalizedToolActionName(toolName: String): String = toolName
    .trim()
    .lowercase()
    .replace(Regex("__v\\d+$"), "")
    .substringAfterLast("__")
    .removePrefix("grz_")
    .removePrefix("claude_code_")

internal fun humanizedToolActionName(actionName: String): String = actionName
    .replace(Regex("[^a-z0-9_-]"), " ")
    .replace('_', ' ')
    .replace('-', ' ')
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(48)
    .ifBlank { "unknown" }

internal fun String.containsAnyFragment(vararg values: String): Boolean = values.any(::contains)
