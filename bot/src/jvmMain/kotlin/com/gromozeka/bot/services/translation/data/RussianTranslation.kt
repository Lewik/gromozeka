package com.gromozeka.bot.services.translation.data
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
@SerialName("russian")
data class RussianTranslation(
    override val languageCode: String = LANGUAGE_CODE,
    override val languageName: String = "Русский",
    override val textDirection: TextDirection = TextDirection.LTR,

    override val appName: String = "Громозека",
    override val helloWorld: String = "Привет, мир!",
    override val switchLanguage: String = "Переключить язык",

    override val newSessionButton: String = "Новая сессия",
    override val newButton: String = "Новая",
    override val continueButton: String = "Продолжить",
    override val newSessionShort: String = "Новая",
    override val cancelButton: String = "Отмена",
    override val saveButton: String = "Сохранить",
    override val builtinStringsMode: String = "Встроенные",
    override val externalStringsMode: String = "Внешние",

    override val viewOriginalJson: String = "Оригинальный JSON",

    override val renameTabTitle: String = "Переименовать таб",
    override val tabNameLabel: String = "Название таба",
    override val projectsTabTooltip: String = "Проекты",

    override val refreshSessionsTooltip: String = "Обновить список сессий",
    override val settingsTooltip: String = "Настройки",
    override val searchSessionsTooltip: String = "Поиск по сессиям",
    override val messageCountTooltip: String = "Всего сообщений: %d\n(с учетом фильтрации)",
    override val closeTabTooltip: String = "Закрыть таб",
    override val screenshotTooltip: String = "Скриншот окна",
    override val sendingMessageTooltip: String = "Отправка сообщения...",
    override val sendMessageTooltip: String = "Отправить сообщение (Shift+Enter)",
    override val recordingTooltip: String = "Запись... (отпустите для остановки)",
    override val pttButtonTooltip: String = "Зажмите для записи (PTT)",
    override val builtinStringsTooltip: String = "Использовать встроенные строки",
    override val externalStringsTooltip: String = "Использовать внешний JSON файл",

    override val searchSessionsPlaceholder: String = "Поиск в сессиях...",

    override val voiceSynthesisTitle: String = "Синтез речи",
    override val speechRecognitionTitle: String = "Распознавание речи",
    override val aiSettingsTitle: String = "ИИ",
    override val apiKeysTitle: String = "API ключи",
    override val interfaceSettingsTitle: String = "Интерфейс",
    override val localizationTitle: String = "Локализация",
    override val notificationsTitle: String = "Уведомления",
    override val developerSettingsTitle: String = "Разработка",

    override val enableTtsLabel: String = "Включить синтез речи",
    override val voiceModelLabel: String = "Модель голоса",
    override val voiceTypeLabel: String = "Тип голоса",
    override val speechSpeedLabel: String = "Скорость речи",
    override val enableSttLabel: String = "Включить распознавание речи",
    override val recognitionLanguageLabel: String = "Язык распознавания",
    override val autoSendMessagesLabel: String = "Автоотправка сообщений",
    override val globalPttHotkeyLabel: String = "Глобальная горячая клавиша PTT",
    override val muteAudioDuringPttLabel: String = "Отключать звук системы во время PTT",
    override val claudeModelLabel: String = "Модель Claude",
    override val responseFormatLabel: String = "Формат ответа",
    override val includeCurrentTimeLabel: String = "Включать текущее время",
    override val openaiApiKeyLabel: String = "API ключ OpenAI",
    override val showSystemMessagesLabel: String = "Показывать системные сообщения",
    override val alwaysOnTopLabel: String = "Поверх всех окон",
    override val errorSoundsLabel: String = "Звуки ошибок",
    override val messageSoundsLabel: String = "Звуки сообщений",
    override val readySoundsLabel: String = "Звуки готовности",
    override val soundVolumeLabel: String = "Громкость звука",
    override val showOriginalJsonLabel: String = "Показывать оригинальный JSON",
    override val localizationModeLabel: String = "Источник строк",
    override val exportStringsButton: String = "Экспортировать текущий перевод в файл",
    override val exportStringsTooltip: String = "Экспортировать активный перевод в кастомный JSON файл для редактирования",
    override val localizationModeBuiltin: String = "Встроенные языки",
    override val localizationModeCustom: String = "Кастомный JSON файл",
    override val builtinLanguageLabel: String = "Встроенный язык",

    override val showJsonMenuItem: String = "Показать JSON",
    override val copyMarkdownMenuItem: String = "Копировать как Markdown",
    override val speakMenuItem: String = "Произнести",

    override val executingStatus: String = "Выполняется...",
    override val errorClickToViewStatus: String = "Ошибка - нажмите для просмотра",
    override val successClickToViewStatus: String = "Успешно - нажмите для просмотра результата",

    override val alwaysOnTopSuffix: String = " [Поверх всех окон]",
    override val devModeSuffix: String = " [РАЗРАБОТКА]",

    override val quickActionTongueTwister: String = "🗣 Скороговорка",
    override val quickActionTable: String = "📊 Таблица",
    override val quickActionGoogleSearch: String = "🔍 Загугли про гугл",
    override val quickActionFileList: String = "📁 выполни ls"
) : Translation() {
    companion object {
        const val LANGUAGE_CODE = "ru"
    }
}