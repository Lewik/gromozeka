package com.gromozeka.bot.services.translation.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("chinese")
data class ChineseTranslation(
    override val languageCode: String = LANGUAGE_CODE,
    override val languageName: String = "中文",
    override val textDirection: TextDirection = TextDirection.LTR,

    override val appName: String = "格罗莫泽卡",
    override val helloWorld: String = "你好世界！",
    override val switchLanguage: String = "切换语言",

    override val newSessionButton: String = "新建会话",
    override val newButton: String = "新建",
    override val continueButton: String = "继续",
    override val newSessionShort: String = "新建",
    override val cancelButton: String = "取消",
    override val saveButton: String = "保存",
    override val builtinStringsMode: String = "内置",
    override val externalStringsMode: String = "外部",

    override val viewOriginalJson: String = "原始JSON",

    override val renameTabTitle: String = "重命名标签页",
    override val tabNameLabel: String = "标签页名称",
    override val projectsTabTooltip: String = "项目",

    override val refreshSessionsTooltip: String = "刷新会话列表",
    override val settingsTooltip: String = "设置",
    override val searchSessionsTooltip: String = "搜索会话",
    override val messageCountTooltip: String = "总消息数: %d\n（设置已过滤）",
    override val closeTabTooltip: String = "关闭标签页",
    override val screenshotTooltip: String = "窗口截图",
    override val sendingMessageTooltip: String = "发送消息中...",
    override val sendMessageTooltip: String = "发送消息 (Shift+Enter)",
    override val recordingTooltip: String = "录音中... (松开停止)",
    override val pttButtonTooltip: String = "按住录音 (PTT)",
    override val builtinStringsTooltip: String = "使用内置字符串",
    override val externalStringsTooltip: String = "使用外部JSON字符串",

    override val searchSessionsPlaceholder: String = "在会话中搜索...",

    override val showJsonMenuItem: String = "显示JSON",
    override val copyMarkdownMenuItem: String = "复制为Markdown",
    override val speakMenuItem: String = "朗读",

    override val executingStatus: String = "执行中...",
    override val errorClickToViewStatus: String = "错误 - 点击查看",
    override val successClickToViewStatus: String = "成功 - 点击查看结果",

    override val alwaysOnTopSuffix: String = " [置顶]",
    override val devModeSuffix: String = " [开发]",

    override val quickActionTongueTwister: String = "🗣 绕口令",
    override val quickActionTable: String = "📊 表格",
    override val quickActionGoogleSearch: String = "🔍 搜索谷歌",
    override val quickActionFileList: String = "📁 执行ls",

    override val searchingForText: String = "搜索\"%s\"中...",
    override val enterSearchQuery: String = "输入搜索查询",
    override val nothingFoundForText: String = "未找到\"%s\"的结果",
    override val foundSessionsText: String = "找到%d个会话：",
    override val noSavedProjectsText: String = "没有保存的项目\n点击\"新建会话\"开始工作",
    override val expandCollapseText: String = "展开/折叠",
    override val sessionsCountText: String = "%d个会话",
    override val messagesCountText: String = "%d条消息",
    override val noSessionsText: String = "无会话",
    override val contextMenuHint: String = "\n右键 - 上下文菜单",
    override val contentUnavailable: String = "内容不可用",
    override val imageDisplayText: String = "🖼️ [图片 %s - %d字符Base64]",
    override val parseErrorText: String = "⚠️ 解析结构失败",
    override val clearSearchText: String = "清除搜索",
    override val recordingText: String = "录音中",
    override val pushToTalkText: String = "按下通话",
) : Translation() {

    @Serializable
    data class ChineseSettingsTranslation(
        override val voiceSynthesisTitle: String = "语音合成",
        override val speechRecognitionTitle: String = "语音识别",
        override val aiSettingsTitle: String = "AI",
        override val apiKeysTitle: String = "API密钥",
        override val interfaceSettingsTitle: String = "界面",
        override val localizationTitle: String = "本地化",
        override val notificationsTitle: String = "通知",
        override val developerSettingsTitle: String = "开发者",

        override val enableTtsLabel: String = "启用文本转语音",
        override val voiceModelLabel: String = "语音模型",
        override val voiceTypeLabel: String = "语音类型",
        override val speechSpeedLabel: String = "语音速度",
        override val enableSttLabel: String = "启用语音转文本",
        override val recognitionLanguageLabel: String = "识别语言",
        override val autoSendMessagesLabel: String = "自动发送消息",
        override val globalPttHotkeyLabel: String = "全局PTT热键",
        override val muteAudioDuringPttLabel: String = "PTT期间静音系统音频",
        override val claudeModelLabel: String = "Claude模型",
        override val responseFormatLabel: String = "响应格式",
        override val includeCurrentTimeLabel: String = "包含当前时间",
        override val openaiApiKeyLabel: String = "OpenAI API密钥",
        override val showSystemMessagesLabel: String = "显示系统消息",
        override val alwaysOnTopLabel: String = "始终置顶",
        override val showTabsAtBottomLabel: String = "在底部显示标签页",
        override val errorSoundsLabel: String = "错误提示音",
        override val messageSoundsLabel: String = "消息提示音",
        override val readySoundsLabel: String = "就绪提示音",
        override val soundVolumeLabel: String = "音量",
        override val showOriginalJsonLabel: String = "显示原始JSON",
        override val localizationModeLabel: String = "字符串源",
        override val exportStringsButton: String = "导出当前翻译到文件",
        override val exportStringsTooltip: String = "导出当前活动翻译到自定义JSON文件进行编辑",
        override val localizationModeBuiltin: String = "内置语言",
        override val localizationModeCustom: String = "自定义JSON文件",
        override val builtinLanguageLabel: String = "内置语言",

        override val ttsDescription: String = "将AI响应转换为语音",
        override val ttsModelDescription: String = "文本转语音模型",
        override val ttsVoiceDescription: String = "语音合成使用的声音",
        override val ttsSpeedDescription: String = "语音速率: 0.25x（最慢）到4.0x（最快）",
        override val sttDescription: String = "将语音输入转换为文本",
        override val sttLanguageDescription: String = "语音识别语言",
        override val autoSendDescription: String = "语音输入后立即发送消息",
        override val globalPttDescription: String = "从任何地方启用按下通话（Cmd+Shift+Space）",
        override val muteAudioDescription: String = "录音时防止音频反馈",
        override val claudeModelDescription: String = "用于响应的AI模型",
        override val responseFormatDescription: String = "AI构建语音响应的方式（推荐XML_INLINE）",
        override val includeTimeDescription: String = "在对话开始时添加一次当前日期/时间",
        override val openaiKeyDescription: String = "TTS和STT服务必需",
        override val showSystemDescription: String = "在聊天中显示系统通知（错误始终显示）",
        override val alwaysOnTopDescription: String = "保持窗口在所有其他应用程序之上",
        override val showTabsAtBottomDescription: String = "将标签页放在窗口底部而不是顶部",
        override val errorSoundsDescription: String = "为错误消息播放声音通知",
        override val messageSoundsDescription: String = "为新消息播放声音通知",
        override val readySoundsDescription: String = "Claude完成处理时播放声音",
        override val soundVolumeDescription: String = "所有通知声音的音量级别",
        override val showJsonDescription: String = "在聊天中显示原始API响应",

        override val customTranslationInfoLabel: String = "自定义翻译信息",
        override val customTranslationInfoMessage: String = "💡 如果存在override.json文件，自定义翻译将自动加载。使用导出→编辑文件→检查进行自定义。",
        override val translationOverrideStatusLabel: String = "翻译覆盖状态",
        override val overrideSuccessMessage: String = "✅ 自定义翻译已加载。%d个字段已自定义。",
        override val overrideFailureMessage: String = "❌ 覆盖失败: %s",
        override val refreshTranslationsLabel: String = "刷新翻译",
        override val refreshTranslationsDescription: String = "应用当前语言设置并检查覆盖文件",
        override val refreshTranslationsButton: String = "刷新",
        override val exportTranslationLabel: String = "导出当前翻译",
        override val exportTranslationDescription: String = "导出当前翻译到override.json文件进行自定义",
        override val exportTranslationButton: String = "导出",

        override val languageSelectionDescription: String = "选择界面语言",

        override val themingTitle: String = "主题设置",
        override val themeSelectionLabel: String = "主题",
        override val themeSelectionDescription: String = "选择应用程序的视觉主题",
        override val customThemeInfoLabel: String = "自定义主题覆盖",
        override val customThemeInfoMessage: String = "您可以在~/.gromozeka/themes/override.json创建自定义JSON文件来覆盖主题颜色。使用下面的导出按钮开始。",
        override val themeOverrideStatusLabel: String = "主题覆盖状态",
        override val themeOverrideSuccessMessage: String = "成功应用主题覆盖，包含%d个自定义字段",
        override val themeOverrideFailureMessage: String = "应用主题覆盖失败: %s",
        override val refreshThemesLabel: String = "刷新主题",
        override val refreshThemesDescription: String = "重新加载主题设置并应用覆盖文件的任何更改",
        override val refreshThemesButton: String = "刷新",
        override val exportThemeLabel: String = "导出当前主题",
        override val exportThemeDescription: String = "导出当前主题为可编辑的JSON文件并用作覆盖",
        override val exportThemeButton: String = "导出主题",

        override val themeNameDark: String = "深色",
        override val themeNameLight: String = "浅色",
        override val themeNameGromozeka: String = "格罗莫泽卡",

        override val themeDeserializationError: String = "反序列化失败",
        override val themeFileError: String = "文件错误",
        override val themeInvalidFormat: String = "无效格式",

        override val settingsTitle: String = "设置",
        override val closeSettingsText: String = "关闭设置",
    ) : SettingsTranslation()

    override val settings: SettingsTranslation = ChineseSettingsTranslation()

    companion object {
        const val LANGUAGE_CODE = "zh"
    }
}