package com.gromozeka.presentation.services.translation.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    override val forkButton: String = "Форк",
    override val restartButton: String = "Заново",
    override val continueButton: String = "Продолжить",
    override val newSessionShort: String = "Новая",
    override val cancelButton: String = "Отмена",
    override val saveButton: String = "Сохранить",
    override val builtinStringsMode: String = "Встроенные",
    override val externalStringsMode: String = "Внешние",

    override val viewOriginalJson: String = "Оригинальный JSON",

    override val renameTabTitle: String = "Переименовать таб",
    override val tabNameLabel: String = "Название таба",
    override val renameConversationTitle: String = "Переименовать обсуждение",
    override val conversationNameLabel: String = "Название обсуждения",
    override val pinnedConversationsTitle: String = "Избранное",
    override val pinConversationTooltip: String = "Добавить в избранное",
    override val unpinConversationTooltip: String = "Убрать из избранного",
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
    override val quickActionFileList: String = "📁 выполни ls",

    override val searchingForText: String = "Поиск \"%s\"...",
    override val enterSearchQuery: String = "Введите поисковый запрос",
    override val nothingFoundForText: String = "Ничего не найдено для \"%s\"",
    override val foundSessionsText: String = "Найдено сессий: %d",
    override val noSavedProjectsText: String = "Нет сохраненных проектов\nНажмите \"Новая сессия\" чтобы начать работу",
    override val expandCollapseText: String = "Развернуть/Свернуть",
    override val sessionsCountText: String = "сессий: %d",
    override val messagesCountText: String = "сообщений: %d",
    override val noSessionsText: String = "Нет сессий",
    override val contextMenuHint: String = "\nПКМ - контекстное меню",
    override val contentUnavailable: String = "Содержимое недоступно",
    override val imageDisplayText: String = "🖼️ [Изображение %s - %d символов Base64]",
    override val parseErrorText: String = "⚠️ Не удалось распарсить структуру",
    override val clearSearchText: String = "Очистить поиск",
    override val recordingText: String = "Запись",
    override val pushToTalkText: String = "Нажать и говорить",
) : Translation() {

    @Serializable
    data class RussianSettingsTranslation(
        override val voiceSynthesisTitle: String = "Синтез речи",
        override val speechRecognitionTitle: String = "Распознавание речи",
        override val aiSettingsTitle: String = "ИИ",
        override val apiKeysTitle: String = "API ключи",
        override val interfaceSettingsTitle: String = "Интерфейс",
        override val localizationTitle: String = "Локализация",
        override val notificationsTitle: String = "Уведомления",
        override val logsAndDiagnosticsTitle: String = "Логи и диагностика",
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
        override val includeCurrentTimeLabel: String = "Включать текущее время",
        override val openaiApiKeyLabel: String = "API ключ OpenAI",
        override val enableBraveSearchLabel: String = "Включить Brave Search",
        override val braveApiKeyLabel: String = "API ключ Brave",
        override val enableJinaReaderLabel: String = "Включить Jina Reader",
        override val jinaApiKeyLabel: String = "API ключ Jina",
        override val showSystemMessagesLabel: String = "Показывать системные сообщения",
        override val alwaysOnTopLabel: String = "Поверх всех окон",
        override val showTabsAtBottomLabel: String = "Показывать табы внизу",
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

        // Settings Descriptions
        override val ttsDescription: String = "Преобразование ответов ИИ в речь",
        override val ttsModelDescription: String = "Модель синтеза речи",
        override val ttsVoiceDescription: String = "Голос для синтеза речи",
        override val ttsSpeedDescription: String = "Скорость речи: 0.25x (медленно) до 4.0x (быстро)",
        override val sttDescription: String = "Преобразование голосового ввода в текст",
        override val sttLanguageDescription: String = "Язык распознавания речи",
        override val autoSendDescription: String = "Отправлять сообщения сразу после голосового ввода",
        override val globalPttDescription: String = "Включить push-to-talk из любого места (Cmd+Shift+Space)",
        override val muteAudioDescription: String = "Предотвращать звуковую обратную связь при записи",
        override val includeTimeDescription: String = "Добавлять текущую дату/время один раз в начале беседы",
        override val openaiKeyDescription: String = "Требуется для TTS и STT сервисов",
        override val braveSearchDescription: String = "Включить встроенный инструмент Brave Search для веб-поиска и локального поиска",
        override val braveApiKeyDescription: String = "API ключ Brave Search (получить на https://brave.com/search/api/)",
        override val jinaReaderDescription: String = "Включить встроенный инструмент Jina Reader для извлечения контента веб-страниц",
        override val jinaApiKeyDescription: String = "API ключ Jina AI (получить на https://jina.ai/)",
        override val showSystemDescription: String = "Отображать системные уведомления в чате (ошибки всегда показываются)",
        override val alwaysOnTopDescription: String = "Держать окно поверх всех остальных приложений",
        override val showTabsAtBottomDescription: String = "Располагать табы внизу окна вместо верха",
        override val errorSoundsDescription: String = "Воспроизводить звуковое уведомление при ошибках",
        override val messageSoundsDescription: String = "Воспроизводить звуковое уведомление для новых сообщений",
        override val readySoundsDescription: String = "Воспроизводить звук когда Claude завершает обработку",
        override val soundVolumeDescription: String = "Уровень громкости для всех звуковых уведомлений",
        override val showJsonDescription: String = "Отображать необработанные ответы API в чате",

        // Translation Override Section
        override val customTranslationInfoLabel: String = "Информация о кастомных переводах",
        override val customTranslationInfoMessage: String = "💡 Кастомные переводы загружаются автоматически при наличии файла override.json. Используйте Экспорт → Редактировать файл → Проверить для настройки.",
        override val translationOverrideStatusLabel: String = "Статус переопределения переводов",
        override val overrideSuccessMessage: String = "✅ Кастомные переводы загружены. %d полей настроено.",
        override val overrideFailureMessage: String = "❌ Переопределение не удалось: %s",
        override val refreshTranslationsLabel: String = "Обновить переводы",
        override val refreshTranslationsDescription: String = "Применить текущие языковые настройки и проверить файлы переопределений",
        override val refreshTranslationsButton: String = "Обновить",
        override val exportTranslationLabel: String = "Экспортировать текущий перевод",
        override val exportTranslationDescription: String = "Экспортировать текущий перевод в файл override.json для настройки",
        override val exportTranslationButton: String = "Экспортировать",

        // Language Selection
        override val languageSelectionDescription: String = "Выбрать язык интерфейса",

        // Theming Section
        override val themingTitle: String = "Темы",
        override val themeSelectionLabel: String = "Тема",
        override val themeSelectionDescription: String = "Выберите визуальную тему для приложения",
        override val customThemeInfoLabel: String = "Переопределение темы",
        override val customThemeInfoMessage: String = "Вы можете создать JSON файл по пути ~/.gromozeka/themes/override.json для изменения цветов темы. Используйте кнопку экспорта ниже.",
        override val themeOverrideStatusLabel: String = "Статус переопределения темы",
        override val themeOverrideSuccessMessage: String = "Успешно применено переопределение темы с %d настройками",
        override val themeOverrideFailureMessage: String = "Ошибка применения темы: %s",
        override val refreshThemesLabel: String = "Обновить темы",
        override val refreshThemesDescription: String = "Перезагрузить настройки тем и применить изменения из файла переопределения",
        override val refreshThemesButton: String = "Обновить",
        override val exportThemeLabel: String = "Экспортировать текущую тему",
        override val exportThemeDescription: String = "Экспортировать текущую тему как JSON файл для редактирования",
        override val exportThemeButton: String = "Экспортировать тему",

        // Theme Names
        override val themeNameDark: String = "Темная",
        override val themeNameLight: String = "Светлая",
        override val themeNameGromozeka: String = "Громозека",

        // Theme Errors
        override val themeDeserializationError: String = "Не удалось десериализовать",
        override val themeFileError: String = "Ошибка файла",
        override val themeInvalidFormat: String = "Неверный формат",

        override val settingsTitle: String = "Настройки",
        override val closeSettingsText: String = "Закрыть настройки",
    ) : SettingsTranslation()

    override val settings: SettingsTranslation = RussianSettingsTranslation()

    companion object {
        const val LANGUAGE_CODE = "ru"
    }
}
