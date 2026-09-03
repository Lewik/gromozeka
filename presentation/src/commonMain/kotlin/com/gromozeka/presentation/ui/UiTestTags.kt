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
    data object ManageProjectsButton : UiTestTag
    data object ProjectManager : UiTestTag
    data object NewProjectButton : UiTestTag
    data object ProjectEditorDialog : UiTestTag
    data object ProjectNameInput : UiTestTag
    data object ProjectDescriptionInput : UiTestTag
    data object ProjectSaveButton : UiTestTag
    data object AgentsTab : UiTestTag
    data object SettingsTab : UiTestTag
    data object LiveTab : UiTestTag
    data object SettingsPanel : UiTestTag
    data object SettingsButton : UiTestTag
    data object SessionScreen : UiTestTag
    data object MessageList : UiTestTag
    data object EditSelectedMessageButton : UiTestTag
    data object EditMessageInput : UiTestTag
    data object EditMessageSaveButton : UiTestTag
    data object UnreadMessagesButton : UiTestTag
    data object MessageInput : UiTestTag
    data object SendButton : UiTestTag
    data object ConnectionStatus : UiTestTag
    data object ConversationProgressStrip : UiTestTag
    data object MessageSquashStatus : UiTestTag
    data object PendingMessagesPanel : UiTestTag
    data object RuntimePanel : UiTestTag
    data object RuntimeButton : UiTestTag
    data object ParticipantsPanel : UiTestTag
    data object ParticipantsButton : UiTestTag
    data object PttButton : UiTestTag
    data object VoiceCaptureStatus : UiTestTag
    data object LiveVoiceButton : UiTestTag
    data object LiveVoiceStatus : UiTestTag
    data object MemoryActionItemsButton : UiTestTag
    data object MemoryActionItemsPanel : UiTestTag
    data object MemoryMenuButton : UiTestTag
    data object CopyableMarkdownBlock : UiTestTag
    data object CopyableMarkdownButton : UiTestTag
    data object SuggestedReplies : UiTestTag
    data object SuggestedRepliesRefresh : UiTestTag
    data object KeyboardShortcuts : UiTestTag

    data class SettingsSectionTab(val section: String) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(section)
    }

    data class KeyboardShortcutBinding(val action: String) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(action)
    }

    data class KeyboardShortcutCapture(val action: String) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(action)
    }

    data class SessionTab(val index: Int) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(index)
    }

    data class ParticipantToggle(val kind: String, val id: String) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(kind, id)
    }

    data class ProjectItem(val projectId: String) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(projectId)
    }

    data class NewSessionButton(val projectId: String) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(projectId)
    }

    data class MessageItem(val messageId: String) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(messageId)
    }

    data class CommandMonitorItem(val monitorId: String) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(monitorId)
    }

    data class ToolActivityGroup(val firstToolCallId: String) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(firstToolCallId)
    }

    data class ToolActivityGroupContent(val firstToolCallId: String) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(firstToolCallId)
    }

    data class SuggestedReply(val index: Int) : UiTestTag {
        override val suffixParts: List<Any?> = listOf(index)
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
