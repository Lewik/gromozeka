package com.gromozeka.presentation.ui

/**
 * [SPECIFICATION] Stable identifiers for Compose UI test nodes.
 *
 * Static tags derive their value from the qualified tag type name trimmed to the
 * `presentation` module boundary. Dynamic tags use the same base identity and append
 * runtime suffix parts.
 *
 * Examples:
 * - `UiTestTag.AppRoot` -> `ui.UiTestTag.AppRoot`
 * - `UiTestTag.SessionTab(2)` -> `ui.UiTestTag.SessionTab:2`
 */
sealed interface UiTestTag {
    data object AppRoot : UiTestTag
    data object TabRow : UiTestTag
    data object ProjectsTab : UiTestTag
    data object AgentsTab : UiTestTag
    data object SettingsTab : UiTestTag
    data object SettingsPanel : UiTestTag
    data object SettingsButton : UiTestTag
    data object SessionScreen : UiTestTag
    data object MessageList : UiTestTag
    data object MessageInput : UiTestTag
    data object SendButton : UiTestTag
    data object PttButton : UiTestTag
    data object PromptsPanel : UiTestTag
    data object AgentButton : UiTestTag
    data object MemoryTasksButton : UiTestTag
    data object MemoryTasksPanel : UiTestTag

    data class SessionTab(val index: Int) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(index)
    }

    data class MessageItem(val messageId: String) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(messageId)
    }

    val suffixParts: List<Any?>
        get() = emptyList()

    val baseName: String
        get() = this::class.qualifiedName
            ?.substringAfter(MODULE_PREFIX)
            ?: error("UiTestTag qualifiedName is unavailable")

    val value: String
        get() = buildString {
            append(baseName)
            suffixParts.forEach {
                append(':')
                append(it)
            }
        }

    private companion object {
        val MODULE_PREFIX = UiTestTag::class.qualifiedName
            ?.removeSuffix(".ui.UiTestTag")
            ?.plus(".")
            ?: error("UiTestTag qualifiedName is unavailable")
    }
}
