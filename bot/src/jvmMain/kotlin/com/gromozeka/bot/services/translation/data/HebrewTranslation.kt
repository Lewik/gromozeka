package com.gromozeka.bot.services.translation.data
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
@SerialName("hebrew")
data class HebrewTranslation(
    override val languageCode: String = LANGUAGE_CODE,
    override val languageName: String = "עברית",
    override val textDirection: TextDirection = TextDirection.RTL,

    override val appName: String = "גרומוזקה",
    override val helloWorld: String = "שלום עולם!",
    override val switchLanguage: String = "החלף שפה",

    override val newSessionButton: String = "סשן חדש",
    override val newButton: String = "חדש",
    override val continueButton: String = "המשך",
    override val newSessionShort: String = "חדש",
    override val cancelButton: String = "ביטול",
    override val saveButton: String = "שמור",
    override val builtinStringsMode: String = "מובנה",
    override val externalStringsMode: String = "חיצוני",

    override val viewOriginalJson: String = "JSON מקורי",

    override val renameTabTitle: String = "שנה שם לשונית",
    override val tabNameLabel: String = "שם שונית",
    override val projectsTabTooltip: String = "פרויקטים",

    override val refreshSessionsTooltip: String = "רענן רשימת סשנים",
    override val settingsTooltip: String = "הגדרות",
    override val searchSessionsTooltip: String = "חיפוש בסשנים",
    override val messageCountTooltip: String = "סך הכל הודעות: %d\n(לאחר סינון)",
    override val closeTabTooltip: String = "סגור שונית",
    override val screenshotTooltip: String = "צילום מסך של החלון",
    override val sendingMessageTooltip: String = "שולח הודעה...",
    override val sendMessageTooltip: String = "שלח הודעה (Shift+Enter)",
    override val recordingTooltip: String = "מקליט... (שחרר כדי לעצור)",
    override val pttButtonTooltip: String = "לחץ והחזק להקלטה (PTT)",
    override val builtinStringsTooltip: String = "השתמש במחרוזות מובנות",
    override val externalStringsTooltip: String = "השתמש בקובץ JSON חיצוני",

    override val searchSessionsPlaceholder: String = "חיפוש בסשנים...",

    override val voiceSynthesisTitle: String = "סינתזת קול",
    override val speechRecognitionTitle: String = "זיהוי דיבור",
    override val aiSettingsTitle: String = "בינה מלאכותית",
    override val apiKeysTitle: String = "מפתחות API",
    override val interfaceSettingsTitle: String = "ממשק",
    override val localizationTitle: String = "לוקליזציה",
    override val notificationsTitle: String = "התראות",
    override val developerSettingsTitle: String = "פיתוח",

    override val enableTtsLabel: String = "הפעל סינתזת קול",
    override val voiceModelLabel: String = "מודל קול",
    override val voiceTypeLabel: String = "סוג קול",
    override val speechSpeedLabel: String = "מהירות דיבור",
    override val enableSttLabel: String = "הפעל זיהוי דיבור",
    override val recognitionLanguageLabel: String = "שפת זיהוי",
    override val autoSendMessagesLabel: String = "שליחה אוטומטית של הודעות",
    override val globalPttHotkeyLabel: String = "מקש קיצור גלובלי PTT",
    override val muteAudioDuringPttLabel: String = "השתק קול מערכת במהלך PTT",
    override val claudeModelLabel: String = "מודל Claude",
    override val responseFormatLabel: String = "פורמט תגובה",
    override val includeCurrentTimeLabel: String = "כלול זמן נוכחי",
    override val openaiApiKeyLabel: String = "מפתח API של OpenAI",
    override val showSystemMessagesLabel: String = "הצג הודעות מערכת",
    override val alwaysOnTopLabel: String = "תמיד עליון",
    override val errorSoundsLabel: String = "צלילי שגיאה",
    override val messageSoundsLabel: String = "צלילי הודעה",
    override val readySoundsLabel: String = "צלילי מוכנות",
    override val soundVolumeLabel: String = "עוצמת קול",
    override val showOriginalJsonLabel: String = "הצג JSON מקורי",
    override val localizationModeLabel: String = "מקור מחרוזות",
    override val exportStringsButton: String = "ייצא תרגום נוכחי לקובץ",
    override val exportStringsTooltip: String = "ייצא תרגום פעיל לקובץ JSON מותאם אישית לעריכה",
    override val localizationModeBuiltin: String = "שפות מובנות",
    override val localizationModeCustom: String = "קובץ JSON מותאם אישית",
    override val builtinLanguageLabel: String = "שפה מובנית",

    override val showJsonMenuItem: String = "הצג JSON",
    override val copyMarkdownMenuItem: String = "העתק כ-Markdown",
    override val speakMenuItem: String = "הקרא",

    override val executingStatus: String = "מבצע...",
    override val errorClickToViewStatus: String = "שגיאה - לחץ לצפייה",
    override val successClickToViewStatus: String = "הצלחה - לחץ לצפייה בתוצאה",

    override val alwaysOnTopSuffix: String = " [תמיד עליון]",
    override val devModeSuffix: String = " [פיתוח]",

    override val quickActionTongueTwister: String = "🗣 מהירי לשון",
    override val quickActionTable: String = "📊 טבלה",
    override val quickActionGoogleSearch: String = "🔍 חפש בגוגל על גוגל",
    override val quickActionFileList: String = "📁 הרץ ls"
) : Translation() {
    companion object {
        const val LANGUAGE_CODE = "he"
    }
}