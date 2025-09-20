package com.gromozeka.bot.services.translation.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    override val forkButton: String = "פורק",
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
    override val quickActionFileList: String = "📁 הרץ ls",

    override val searchingForText: String = "מחפש \"%s\"...",
    override val enterSearchQuery: String = "הכנס שאילתת חיפוש",
    override val nothingFoundForText: String = "לא נמצא דבר עבור \"%s\"",
    override val foundSessionsText: String = "נמצאו %d סשנים:",
    override val noSavedProjectsText: String = "אין פרויקטים שמורים\nלחץ על \"סשן חדש\" כדי להתחיל לעבוד",
    override val expandCollapseText: String = "הרחב/כווץ",
    override val sessionsCountText: String = "%d סשנים",
    override val messagesCountText: String = "%d הודעות",
    override val noSessionsText: String = "אין סשנים",
    override val contextMenuHint: String = "\nלחיצה ימנית - תפריט הקשר",
    override val contentUnavailable: String = "תוכן לא זמין",
    override val imageDisplayText: String = "🖼️ [תמונה %s - %d תווים Base64]",
    override val parseErrorText: String = "⚠️ נכשל בפענוח המבנה",
    override val clearSearchText: String = "נקה חיפוש",
    override val recordingText: String = "מקליט",
    override val pushToTalkText: String = "לחץ ודבר",
) : Translation() {

    @Serializable
    data class HebrewSettingsTranslation(
        override val voiceSynthesisTitle: String = "סינתזת קול",
        override val speechRecognitionTitle: String = "זיהוי דיבור",
        override val aiSettingsTitle: String = "בינה מלאכותית",
        override val apiKeysTitle: String = "מפתחות API",
        override val interfaceSettingsTitle: String = "ממשק",
        override val localizationTitle: String = "לוקליזציה",
        override val notificationsTitle: String = "התראות",
        override val logsAndDiagnosticsTitle: String = "לוגים ואבחון",
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
        override val showTabsAtBottomLabel: String = "הצג לשוניות בתחתית",
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

        // Settings Descriptions
        override val ttsDescription: String = "המרת תגובות AI לדיבור",
        override val ttsModelDescription: String = "מודל סינתזת קול",
        override val ttsVoiceDescription: String = "קול לסינתזת דיבור",
        override val ttsSpeedDescription: String = "קצב דיבור: 0.25x (הכי איטי) עד 4.0x (הכי מהיר)",
        override val sttDescription: String = "המרת קלט קולי לטקסט",
        override val sttLanguageDescription: String = "שפת זיהוי דיבור",
        override val autoSendDescription: String = "שלח הודעות מיידית לאחר קלט קולי",
        override val globalPttDescription: String = "הפעל push-to-talk מכל מקום (Cmd+Shift+Space)",
        override val muteAudioDescription: String = "מנע משוב קולי בעת הקלטה",
        override val claudeModelDescription: String = "מודל AI לשימוש עבור תגובות",
        override val responseFormatDescription: String = "איך AI מבנה תגובות קוליות (מומלץ XML_INLINE)",
        override val includeTimeDescription: String = "הוסף תאריך/שעה נוכחיים פעם אחת בתחילת השיחה",
        override val openaiKeyDescription: String = "נדרש עבור שירותי TTS ו-STT",
        override val showSystemDescription: String = "הצג התראות מערכת בצ'אט (שגיאות תמיד מוצגות)",
        override val alwaysOnTopDescription: String = "החזק חלון מעל כל האפליקציות האחרות",
        override val showTabsAtBottomDescription: String = "מקם לשוניות בתחתית החלון במקום בראש",
        override val errorSoundsDescription: String = "השמע התראה קולית עבור הודעות שגיאה",
        override val messageSoundsDescription: String = "השמע התראה קולית עבור הודעות חדשות",
        override val readySoundsDescription: String = "השמע צליל כאשר Claude מסיים עיבוד",
        override val soundVolumeDescription: String = "רמת עוצמה עבור כל ההתראות הקוליות",
        override val showJsonDescription: String = "הצג תגובות API גולמיות בצ'אט",

        // Translation Override Section
        override val customTranslationInfoLabel: String = "מידע על תרגומים מותאמים אישית",
        override val customTranslationInfoMessage: String = "💡 תרגומים מותאמים אישית נטענים אוטומטית אם קיים קובץ override.json. השתמש בייצוא → ערוך קובץ → בדוק להתאמה אישית.",
        override val translationOverrideStatusLabel: String = "סטטוס החלפת תרגומים",
        override val overrideSuccessMessage: String = "✅ תרגומים מותאמים אישית נטענו. %d שדות הותאמו.",
        override val overrideFailureMessage: String = "❌ החלפה נכשלה: %s",
        override val refreshTranslationsLabel: String = "רענן תרגומים",
        override val refreshTranslationsDescription: String = "החל הגדרות שפה נוכחיות ובדוק קבצי החלפה",
        override val refreshTranslationsButton: String = "רענן",
        override val exportTranslationLabel: String = "ייצא תרגום נוכחי",
        override val exportTranslationDescription: String = "ייצא תרגום נוכחי לקובץ override.json להתאמה אישית",
        override val exportTranslationButton: String = "ייצא",

        // Language Selection
        override val languageSelectionDescription: String = "בחר שפת ממשק",

        // Theming Section
        override val themingTitle: String = "עיצוב",
        override val themeSelectionLabel: String = "ערכת נושא",
        override val themeSelectionDescription: String = "בחר את ערכת הנושא החזותית עבור האפליקציה",
        override val customThemeInfoLabel: String = "דריסת ערכת נושא מותאמת",
        override val customThemeInfoMessage: String = "ניתן ליצור קובץ JSON בנתיב ~/.gromozeka/themes/override.json כדי לדרוס צבעי ערכת הנושא. השתמש בכפתור הייצוא למטה כדי להתחיל.",
        override val themeOverrideStatusLabel: String = "סטטוס דריסת ערכת נושא",
        override val themeOverrideSuccessMessage: String = "דריסת ערכת נושא יושמה בהצלחה עם %d שדות מותאמים",
        override val themeOverrideFailureMessage: String = "נכשל ביישום דריסת ערכת נושא: %s",
        override val refreshThemesLabel: String = "רענן ערכות נושא",
        override val refreshThemesDescription: String = "טען מחדש הגדרות ערכות נושא והחל שינויים מקובץ הדריסה",
        override val refreshThemesButton: String = "רענן",
        override val exportThemeLabel: String = "ייצא ערכת נושא נוכחית",
        override val exportThemeDescription: String = "ייצא את ערכת הנושא הנוכחית כקובץ JSON שניתן לערוך ולהשתמש בו כדריסה",
        override val exportThemeButton: String = "ייצא ערכת נושא",

        // Theme Names
        override val themeNameDark: String = "כהה",
        override val themeNameLight: String = "בהיר",
        override val themeNameGromozeka: String = "גרומוזקה",

        // Theme Errors
        override val themeDeserializationError: String = "נכשל בניתוח",
        override val themeFileError: String = "שגיאת קובץ",
        override val themeInvalidFormat: String = "פורמט לא תקין",

        override val settingsTitle: String = "הגדרות",
        override val closeSettingsText: String = "סגור הגדרות",
    ) : SettingsTranslation()

    override val settings: SettingsTranslation = HebrewSettingsTranslation()

    companion object {
        const val LANGUAGE_CODE = "he"
    }
}