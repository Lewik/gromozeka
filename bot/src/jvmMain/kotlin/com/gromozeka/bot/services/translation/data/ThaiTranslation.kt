package com.gromozeka.bot.services.translation.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("thai")
data class ThaiTranslation(
    override val languageCode: String = LANGUAGE_CODE,
    override val languageName: String = "ภาษาไทย",
    override val textDirection: TextDirection = TextDirection.LTR,

    override val appName: String = "โกรโมเซกา",
    override val helloWorld: String = "สวัสดีชาวโลก!",
    override val switchLanguage: String = "เปลี่ยนภาษา",

    override val newSessionButton: String = "เซสชันใหม่",
    override val newButton: String = "ใหม่",
    override val continueButton: String = "ดำเนินต่อ",
    override val newSessionShort: String = "ใหม่",
    override val cancelButton: String = "ยกเลิก",
    override val saveButton: String = "บันทึก",
    override val builtinStringsMode: String = "ภายใน",
    override val externalStringsMode: String = "ภายนอก",

    override val viewOriginalJson: String = "JSON ต้นฉบับ",

    override val renameTabTitle: String = "เปลี่ยนชื่อแท็บ",
    override val tabNameLabel: String = "ชื่อแท็บ",
    override val projectsTabTooltip: String = "โปรเจกต์",

    override val refreshSessionsTooltip: String = "รีเฟรชรายการเซสชัน",
    override val settingsTooltip: String = "การตั้งค่า",
    override val searchSessionsTooltip: String = "ค้นหาเซสชัน",
    override val messageCountTooltip: String = "ข้อความทั้งหมด: %d\n(กรองตามการตั้งค่า)",
    override val closeTabTooltip: String = "ปิดแท็บ",
    override val screenshotTooltip: String = "จับภาพหน้าจอ",
    override val sendingMessageTooltip: String = "กำลังส่งข้อความ...",
    override val sendMessageTooltip: String = "ส่งข้อความ (Shift+Enter)",
    override val recordingTooltip: String = "กำลังบันทึก... (ปล่อยเพื่อหยุด)",
    override val pttButtonTooltip: String = "กดค้างเพื่อบันทึก (PTT)",
    override val builtinStringsTooltip: String = "ใช้ข้อความภายใน",
    override val externalStringsTooltip: String = "ใช้ไฟล์ JSON ภายนอก",

    override val searchSessionsPlaceholder: String = "ค้นหาในเซสชัน...",

    override val showJsonMenuItem: String = "แสดง JSON",
    override val copyMarkdownMenuItem: String = "คัดลอกเป็น Markdown",
    override val speakMenuItem: String = "พูด",

    override val executingStatus: String = "กำลังดำเนินการ...",
    override val errorClickToViewStatus: String = "ข้อผิดพลาด - คลิกเพื่อดู",
    override val successClickToViewStatus: String = "สำเร็จ - คลิกเพื่อดูผลลัพธ์",

    override val alwaysOnTopSuffix: String = " [อยู่บนสุดเสมอ]",
    override val devModeSuffix: String = " [การพัฒนา]",

    override val quickActionTongueTwister: String = "🗣 ลิ้นพันจ่า",
    override val quickActionTable: String = "📊 ตาราง",
    override val quickActionGoogleSearch: String = "🔍 ค้นหาเรื่อง Google",
    override val quickActionFileList: String = "📁 รัน ls",

    override val searchingForText: String = "กำลังค้นหา \"%s\"...",
    override val enterSearchQuery: String = "ใส่คำค้นหา",
    override val nothingFoundForText: String = "ไม่พบอะไรสำหรับ \"%s\"",
    override val foundSessionsText: String = "พบ %d เซสชัน:",
    override val noSavedProjectsText: String = "ไม่มีโปรเจกต์ที่บันทึกไว้\nคลิก \"เซสชันใหม่\" เพื่อเริ่มทำงาน",
    override val expandCollapseText: String = "ขยาย/ย่อ",
    override val sessionsCountText: String = "%d เซสชัน",
    override val messagesCountText: String = "%d ข้อความ",
    override val noSessionsText: String = "ไม่มีเซสชัน",
    override val contextMenuHint: String = "\nคลิกขวา - เมนูบริบท",
    override val contentUnavailable: String = "เนื้อหาไม่พร้อมใช้งาน",
    override val imageDisplayText: String = "🖼️ [รูปภาพ %s - %d อักขระ Base64]",
    override val parseErrorText: String = "⚠️ ไม่สามารถแปลงโครงสร้างได้",
    override val clearSearchText: String = "ล้างการค้นหา",
    override val recordingText: String = "บันทึก",
    override val pushToTalkText: String = "กดเพื่อพูด",
) : Translation() {

    @Serializable
    data class ThaiSettingsTranslation(
        override val voiceSynthesisTitle: String = "การสังเคราะห์เสียง",
        override val speechRecognitionTitle: String = "การรู้จำเสียงพูด",
        override val aiSettingsTitle: String = "AI",
        override val apiKeysTitle: String = "API Keys",
        override val interfaceSettingsTitle: String = "อินเทอร์เฟซ",
        override val localizationTitle: String = "การแปลภาษา",
        override val notificationsTitle: String = "การแจ้งเตือน",
        override val developerSettingsTitle: String = "การพัฒนา",

        override val enableTtsLabel: String = "เปิดการแปลงข้อความเป็นเสียง",
        override val voiceModelLabel: String = "โมเดลเสียง",
        override val voiceTypeLabel: String = "ประเภทเสียง",
        override val speechSpeedLabel: String = "ความเร็วการพูด",
        override val enableSttLabel: String = "เปิดการแปลงเสียงเป็นข้อความ",
        override val recognitionLanguageLabel: String = "ภาษาการรู้จำ",
        override val autoSendMessagesLabel: String = "ส่งข้อความอัตโนมัติ",
        override val globalPttHotkeyLabel: String = "ปุ่มลัด PTT ทั่วระบบ",
        override val muteAudioDuringPttLabel: String = "ปิดเสียงระบบระหว่าง PTT",
        override val claudeModelLabel: String = "โมเดล Claude",
        override val responseFormatLabel: String = "รูปแบบการตอบสนong",
        override val includeCurrentTimeLabel: String = "รวมเวลาปัจจุบัน",
        override val openaiApiKeyLabel: String = "OpenAI API Key",
        override val showSystemMessagesLabel: String = "แสดงข้อความระบบ",
        override val alwaysOnTopLabel: String = "อยู่บนสุดเสมอ",
        override val showTabsAtBottomLabel: String = "แสดงแท็บที่ด้านล่าง",
        override val errorSoundsLabel: String = "เสียงแจ้งข้อผิดพลาด",
        override val messageSoundsLabel: String = "เสียงแจ้งข้อความ",
        override val readySoundsLabel: String = "เสียงแจ้งความพร้อม",
        override val soundVolumeLabel: String = "ระดับเสียง",
        override val showOriginalJsonLabel: String = "แสดง JSON ต้นฉบับ",
        override val localizationModeLabel: String = "แหล่งข้อความ",
        override val exportStringsButton: String = "ส่งออกการแปลปัจจุบันเป็นไฟล์",
        override val exportStringsTooltip: String = "ส่งออกการแปลที่ใช้งานอยู่เป็นไฟล์ JSON สำหรับแก้ไข",
        override val localizationModeBuiltin: String = "ภาษาในตัว",
        override val localizationModeCustom: String = "ไฟล์ JSON กำหนดเอง",
        override val builtinLanguageLabel: String = "ภาษาในตัว",

        override val ttsDescription: String = "แปลงคำตอบ AI เป็นเสียงพูด",
        override val ttsModelDescription: String = "โมเดลสังเคราะห์เสียง",
        override val ttsVoiceDescription: String = "เสียงสำหรับการสังเคราะห์",
        override val ttsSpeedDescription: String = "ความเร็วเสียง: 0.25x (ช้าที่สุด) ถึง 4.0x (เร็วที่สุด)",
        override val sttDescription: String = "แปลงเสียงพูดเป็นข้อความ",
        override val sttLanguageDescription: String = "ภาษาการรู้จำเสียงพูด",
        override val autoSendDescription: String = "ส่งข้อความทันทีหลังจากป้อนเสียง",
        override val globalPttDescription: String = "เปิดการกดเพื่อพูดจากทุกที่ (Cmd+Shift+Space)",
        override val muteAudioDescription: String = "ป้องกันเสียงย้อนกลับขณะบันทึก",
        override val claudeModelDescription: String = "โมเดล AI สำหรับคำตอบ",
        override val responseFormatDescription: String = "วิธี AI จัดโครงสร้างคำตอบเสียง (แนะนำ XML_INLINE)",
        override val includeTimeDescription: String = "เพิ่มวันที่/เวลาปัจจุบันครั้งเดียวเมื่อเริ่มบทสนทนา",
        override val openaiKeyDescription: String = "จำเป็นสำหรับบริการ TTS และ STT",
        override val showSystemDescription: String = "แสดงการแจ้งเตือนระบบในแชท (ข้อผิดพลาดแสดงเสมอ)",
        override val alwaysOnTopDescription: String = "รักษาหน้าต่างให้อยู่เหนือแอปพลิเคชันอื่น",
        override val showTabsAtBottomDescription: String = "วางแท็บที่ด้านล่างของหน้าต่างแทนที่ด้านบน",
        override val errorSoundsDescription: String = "เล่นเสียงแจ้งเตือนสำหรับข้อความผิดพลาด",
        override val messageSoundsDescription: String = "เล่นเสียงแจ้งเตือนสำหรับข้อความใหม่",
        override val readySoundsDescription: String = "เล่นเสียงเมื่อ Claude ประมวลผลเสร็จ",
        override val soundVolumeDescription: String = "ระดับความดังสำหรับเสียงแจ้งเตือนทั้งหมด",
        override val showJsonDescription: String = "แสดงการตอบสนอง API ดิบในแชท",

        override val customTranslationInfoLabel: String = "ข้อมูลการแปลกำหนดเอง",
        override val customTranslationInfoMessage: String = "💡 การแปลกำหนดเองจะโหลดโดยอัตโนมัติหากมีไฟล์ override.json ใช้ ส่งออก → แก้ไขไฟล์ → ตรวจสอบ เพื่อปรับแต่ง",
        override val translationOverrideStatusLabel: String = "สถานะการแทนที่การแปล",
        override val overrideSuccessMessage: String = "✅ โหลดการแปลกำหนดเองแล้ว ปรับแต่ง %d ฟิลด์",
        override val overrideFailureMessage: String = "❌ การแทนที่ล้มเหลว: %s",
        override val refreshTranslationsLabel: String = "รีเฟรชการแปล",
        override val refreshTranslationsDescription: String = "ใช้การตั้งค่าภาษาปัจจุบันและตรวจสอบไฟล์แทนที่",
        override val refreshTranslationsButton: String = "รีเฟรช",
        override val exportTranslationLabel: String = "ส่งออกการแปลปัจจุบัน",
        override val exportTranslationDescription: String = "ส่งออกการแปลปัจจุบันเป็นไฟล์ override.json สำหรับปรับแต่ง",
        override val exportTranslationButton: String = "ส่งออก",

        override val languageSelectionDescription: String = "เลือกภาษาอินเทอร์เฟซ",

        override val themingTitle: String = "ธีม",
        override val themeSelectionLabel: String = "ธีม",
        override val themeSelectionDescription: String = "เลือกธีมสีสำหรับแอปพลิเคชัน",
        override val customThemeInfoLabel: String = "การแทนที่ธีมกำหนดเอง",
        override val customThemeInfoMessage: String = "คุณสามารถสร้างไฟล์ JSON กำหนดเองที่ ~/.gromozeka/themes/override.json เพื่อแทนที่สีธีม ใช้ปุ่มส่งออกด้านล่างเพื่อเริ่มต้น",
        override val themeOverrideStatusLabel: String = "สถานะการแทนที่ธีม",
        override val themeOverrideSuccessMessage: String = "ใช้การแทนที่ธีมสำเร็จด้วย %d ฟิลด์กำหนดเอง",
        override val themeOverrideFailureMessage: String = "ไม่สามารถใช้การแทนที่ธีม: %s",
        override val refreshThemesLabel: String = "รีเฟรชธีม",
        override val refreshThemesDescription: String = "โหลดการตั้งค่าธีมใหม่และใช้การเปลี่ยนแปลงในไฟล์แทนที่",
        override val refreshThemesButton: String = "รีเฟรช",
        override val exportThemeLabel: String = "ส่งออกธีมปัจจุบัน",
        override val exportThemeDescription: String = "ส่งออกธีมปัจจุบันเป็นไฟล์ JSON ที่สามารถแก้ไขและใช้เป็นการแทนที่",
        override val exportThemeButton: String = "ส่งออกธีม",

        override val themeNameDark: String = "มืด",
        override val themeNameLight: String = "สว่าง",
        override val themeNameGromozeka: String = "โกรโมเซกา",

        override val themeDeserializationError: String = "ไม่สามารถแปลงข้อมูลได้",
        override val themeFileError: String = "ข้อผิดพลาดไฟล์",
        override val themeInvalidFormat: String = "รูปแบบไม่ถูกต้อง",

        override val settingsTitle: String = "การตั้งค่า",
        override val closeSettingsText: String = "ปิดการตั้งค่า",
    ) : SettingsTranslation()

    override val settings: SettingsTranslation = ThaiSettingsTranslation()

    companion object {
        const val LANGUAGE_CODE = "th"
    }
}