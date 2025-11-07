package com.gromozeka.bot.services.translation.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("japanese")
data class JapaneseTranslation(
    override val languageCode: String = LANGUAGE_CODE,
    override val languageName: String = "日本語",
    override val textDirection: TextDirection = TextDirection.LTR,

    override val appName: String = "グロモゼカ",
    override val helloWorld: String = "こんにちは世界！",
    override val switchLanguage: String = "言語を切り替え",

    override val newSessionButton: String = "新しいセッション",
    override val newButton: String = "新規",
    override val forkButton: String = "フォーク",
    override val restartButton: String = "やり直す",
    override val continueButton: String = "続行",
    override val newSessionShort: String = "新規",
    override val cancelButton: String = "キャンセル",
    override val saveButton: String = "保存",
    override val builtinStringsMode: String = "内蔵",
    override val externalStringsMode: String = "外部",

    override val viewOriginalJson: String = "元のJSON",

    override val renameTabTitle: String = "タブ名変更",
    override val tabNameLabel: String = "タブ名",
    override val projectsTabTooltip: String = "プロジェクト",

    override val refreshSessionsTooltip: String = "セッション一覧を更新",
    override val settingsTooltip: String = "設定",
    override val searchSessionsTooltip: String = "セッション検索",
    override val messageCountTooltip: String = "総メッセージ数: %d\n（設定でフィルタ済み）",
    override val closeTabTooltip: String = "タブを閉じる",
    override val screenshotTooltip: String = "ウィンドウのスクリーンショット",
    override val sendingMessageTooltip: String = "メッセージ送信中...",
    override val sendMessageTooltip: String = "メッセージ送信 (Shift+Enter)",
    override val recordingTooltip: String = "録音中... (離すと停止)",
    override val pttButtonTooltip: String = "押しながら録音 (PTT)",
    override val builtinStringsTooltip: String = "内蔵文字列を使用",
    override val externalStringsTooltip: String = "外部JSON文字列を使用",

    override val searchSessionsPlaceholder: String = "セッションを検索...",

    override val showJsonMenuItem: String = "JSONを表示",
    override val copyMarkdownMenuItem: String = "Markdownとしてコピー",
    override val speakMenuItem: String = "音声読み上げ",

    override val executingStatus: String = "実行中...",
    override val errorClickToViewStatus: String = "エラー - クリックして確認",
    override val successClickToViewStatus: String = "成功 - クリックして結果表示",

    override val alwaysOnTopSuffix: String = " [常に前面]",
    override val devModeSuffix: String = " [開発]",

    override val quickActionTongueTwister: String = "🗣 早口言葉",
    override val quickActionTable: String = "📊 表",
    override val quickActionGoogleSearch: String = "🔍 Googleについて検索",
    override val quickActionFileList: String = "📁 ls実行",

    override val searchingForText: String = "\"%s\"を検索中...",
    override val enterSearchQuery: String = "検索クエリを入力",
    override val nothingFoundForText: String = "\"%s\"の検索結果なし",
    override val foundSessionsText: String = "%d個のセッションが見つかりました：",
    override val noSavedProjectsText: String = "保存されたプロジェクトなし\n「新しいセッション」をクリックして開始",
    override val expandCollapseText: String = "展開/折りたたみ",
    override val sessionsCountText: String = "%d個のセッション",
    override val messagesCountText: String = "%d件のメッセージ",
    override val noSessionsText: String = "セッションなし",
    override val contextMenuHint: String = "\n右クリック - コンテキストメニュー",
    override val contentUnavailable: String = "コンテンツ利用不可",
    override val imageDisplayText: String = "🖼️ [画像 %s - %d文字のBase64]",
    override val parseErrorText: String = "⚠️ 構造解析に失敗",
    override val clearSearchText: String = "検索をクリア",
    override val recordingText: String = "録音中",
    override val pushToTalkText: String = "プッシュ・トゥ・トーク",
) : Translation() {

    @Serializable
    data class JapaneseSettingsTranslation(
        override val voiceSynthesisTitle: String = "音声合成",
        override val speechRecognitionTitle: String = "音声認識",
        override val aiSettingsTitle: String = "AI",
        override val apiKeysTitle: String = "APIキー",
        override val interfaceSettingsTitle: String = "インターフェース",
        override val localizationTitle: String = "ローカライゼーション",
        override val notificationsTitle: String = "通知",
        override val logsAndDiagnosticsTitle: String = "ログと診断",
        override val developerSettingsTitle: String = "開発者",

        override val enableTtsLabel: String = "テキスト読み上げを有効",
        override val voiceModelLabel: String = "音声モデル",
        override val voiceTypeLabel: String = "音声タイプ",
        override val speechSpeedLabel: String = "読み上げ速度",
        override val enableSttLabel: String = "音声テキスト化を有効",
        override val recognitionLanguageLabel: String = "認識言語",
        override val autoSendMessagesLabel: String = "メッセージ自動送信",
        override val globalPttHotkeyLabel: String = "グローバルPTTホットキー",
        override val muteAudioDuringPttLabel: String = "PTT中システム音声をミュート",
        override val responseFormatLabel: String = "応答フォーマット",
        override val includeCurrentTimeLabel: String = "現在時刻を含める",
        override val openaiApiKeyLabel: String = "OpenAI APIキー",
        override val showSystemMessagesLabel: String = "システムメッセージを表示",
        override val alwaysOnTopLabel: String = "常に前面表示",
        override val showTabsAtBottomLabel: String = "タブを下部に表示",
        override val errorSoundsLabel: String = "エラー音",
        override val messageSoundsLabel: String = "メッセージ音",
        override val readySoundsLabel: String = "準備完了音",
        override val soundVolumeLabel: String = "音量",
        override val showOriginalJsonLabel: String = "元のJSONを表示",
        override val localizationModeLabel: String = "文字列ソース",
        override val exportStringsButton: String = "現在の翻訳をファイルにエクスポート",
        override val exportStringsTooltip: String = "現在アクティブな翻訳を編集用カスタムJSONファイルにエクスポート",
        override val localizationModeBuiltin: String = "内蔵言語",
        override val localizationModeCustom: String = "カスタムJSONファイル",
        override val builtinLanguageLabel: String = "内蔵言語",

        override val ttsDescription: String = "AI応答を音声に変換",
        override val ttsModelDescription: String = "テキスト読み上げモデル",
        override val ttsVoiceDescription: String = "音声合成用の音声",
        override val ttsSpeedDescription: String = "読み上げ速度: 0.25x（最遅）から4.0x（最速）",
        override val sttDescription: String = "音声入力をテキストに変換",
        override val sttLanguageDescription: String = "音声認識言語",
        override val autoSendDescription: String = "音声入力後すぐにメッセージを送信",
        override val globalPttDescription: String = "どこからでもプッシュ・トゥ・トークを有効化（Cmd+Shift+Space）",
        override val muteAudioDescription: String = "録音時の音声フィードバックを防止",
        override val responseFormatDescription: String = "AIが音声応答を構造化する方法（XML_INLINEを推奨）",
        override val includeTimeDescription: String = "会話開始時に現在の日時を一度追加",
        override val openaiKeyDescription: String = "TTSとSTTサービスに必要",
        override val showSystemDescription: String = "チャットにシステム通知を表示（エラーは常に表示）",
        override val alwaysOnTopDescription: String = "ウィンドウを他のアプリケーションより前面に保持",
        override val showTabsAtBottomDescription: String = "タブを上部ではなく下部に配置",
        override val errorSoundsDescription: String = "エラーメッセージの音声通知を再生",
        override val messageSoundsDescription: String = "新着メッセージの音声通知を再生",
        override val readySoundsDescription: String = "Claude処理完了時に音声を再生",
        override val soundVolumeDescription: String = "すべての通知音の音量レベル",
        override val showJsonDescription: String = "チャットに生のAPI応答を表示",

        override val customTranslationInfoLabel: String = "カスタム翻訳情報",
        override val customTranslationInfoMessage: String = "💡 override.jsonファイルが存在する場合、カスタム翻訳が自動的に読み込まれます。エクスポート → ファイル編集 → チェックでカスタマイズ可能。",
        override val translationOverrideStatusLabel: String = "翻訳オーバーライド状態",
        override val overrideSuccessMessage: String = "✅ カスタム翻訳が読み込まれました。%d個のフィールドがカスタマイズされています。",
        override val overrideFailureMessage: String = "❌ オーバーライドに失敗: %s",
        override val refreshTranslationsLabel: String = "翻訳を更新",
        override val refreshTranslationsDescription: String = "現在の言語設定を適用し、オーバーライドファイルをチェック",
        override val refreshTranslationsButton: String = "更新",
        override val exportTranslationLabel: String = "現在の翻訳をエクスポート",
        override val exportTranslationDescription: String = "現在の翻訳をカスタマイズ用override.jsonファイルにエクスポート",
        override val exportTranslationButton: String = "エクスポート",

        override val languageSelectionDescription: String = "インターフェース言語を選択",

        override val themingTitle: String = "テーマ設定",
        override val themeSelectionLabel: String = "テーマ",
        override val themeSelectionDescription: String = "アプリケーションの視覚テーマを選択",
        override val customThemeInfoLabel: String = "カスタムテーマオーバーライド",
        override val customThemeInfoMessage: String = "~/.gromozeka/themes/override.jsonにカスタムJSONファイルを作成してテーマ色をオーバーライドできます。下のエクスポートボタンを使用して開始。",
        override val themeOverrideStatusLabel: String = "テーマオーバーライド状態",
        override val themeOverrideSuccessMessage: String = "テーマオーバーライドが正常に適用されました（%d個のカスタムフィールド）",
        override val themeOverrideFailureMessage: String = "テーマオーバーライドの適用に失敗: %s",
        override val refreshThemesLabel: String = "テーマを更新",
        override val refreshThemesDescription: String = "テーマ設定を再読み込みし、オーバーライドファイルの変更を適用",
        override val refreshThemesButton: String = "更新",
        override val exportThemeLabel: String = "現在のテーマをエクスポート",
        override val exportThemeDescription: String = "現在のテーマを編集可能でオーバーライドとして使用できるJSONファイルとしてエクスポート",
        override val exportThemeButton: String = "テーマをエクスポート",

        override val themeNameDark: String = "ダーク",
        override val themeNameLight: String = "ライト",
        override val themeNameGromozeka: String = "グロモゼカ",

        override val themeDeserializationError: String = "デシリアライゼーションに失敗",
        override val themeFileError: String = "ファイルエラー",
        override val themeInvalidFormat: String = "無効なフォーマット",

        override val settingsTitle: String = "設定",
        override val closeSettingsText: String = "設定を閉じる",
    ) : SettingsTranslation()

    override val settings: SettingsTranslation = JapaneseSettingsTranslation()

    companion object {
        const val LANGUAGE_CODE = "ja"
    }
}