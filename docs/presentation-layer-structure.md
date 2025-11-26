# Presentation Layer Structure

## Организация ViewModel интерфейсов

ViewModel интерфейсы в domain layer организованы по **клиентским платформам** и **типам VM**.

**Принцип:** Интерфейсы создаются на основе РЕАЛЬНОГО кода из presentation layer, не наоборот.

## Структура директорий

```
domain/src/commonMain/kotlin/com/gromozeka/domain/presentation/
├── desktop/               # Desktop (Compose Desktop, JVM)
│   ├── component/         # UI компоненты с layout диаграммами
│   │   ├── TabComponentVM.kt
│   │   ├── ConversationSearchComponentVM.kt
│   │   └── LoadingScreenComponentVM.kt
│   └── logic/             # Бизнес-логика без UI деталей
│       └── AppLogicVM.kt
├── mobile/                # Mobile (будущее: Android, iOS)
│   ├── component/
│   └── logic/
└── web/                   # Web (будущее: Kotlin/JS, WASM)
    ├── component/
    └── logic/
```

## Naming Convention

### ComponentVM
**Цель:** UI компоненты с детальным layout описанием

**Признаки:**
- Название заканчивается на `ComponentVM`
- KDoc содержит ASCII диаграмму UI layout
- Описывает конкретную визуальную структуру
- Определяет rendering поведение (цвета, шрифты, анимации)

**Примеры:** 
- `TabComponentVM` - отдельная вкладка conversation
- `ConversationSearchComponentVM` - поиск по conversations
- `LoadingScreenComponentVM` - экран загрузки

### LogicVM
**Цель:** Оркестрация бизнес-логики и состояния без UI деталей

**Признаки:**
- Название заканчивается на `LogicVM`
- KDoc НЕ содержит UI диаграмм
- Координирует несколько компонентов или внешних систем
- Управляет навигацией, жизненным циклом, синхронизацией

**Пример:** `AppLogicVM` - управление вкладками, сессиями, восстановление состояния

## Существующие ViewModels

### Desktop Platform

**LogicVM:**

1. **AppLogicVM** (`desktop/logic/`)
   - Управление вкладками (create, close, switch)
   - Восстановление сессии из UIState
   - Интеграция с TabManager (MCP протокол)
   - Работа с памятью (remember thread, add to graph)
   - Координация tab operations (rename, interrupt)

**ComponentVM:**

1. **TabComponentVM** (`desktop/component/`)
   - Отдельная вкладка conversation
   - Сообщения (streaming, filtering)
   - Message tags (thinking_ultrathink, mode_readonly)
   - Редактирование/удаление сообщений
   - Squashing (manual, distill, summarize)
   - Bulk selection и операции
   - Token statistics

2. **ConversationSearchComponentVM** (`desktop/component/`)
   - Поиск по conversations
   - Debounced search (300ms)
   - Результаты с группировкой по проектам
   - Dropdown visibility management

3. **LoadingScreenComponentVM** (`desktop/component/`)
   - Экран загрузки приложения
   - MCP server initialization progress
   - State machine: Initializing → LoadingMCP → Complete/Error

## Workflow: От реального кода к интерфейсам

**ВАЖНО:** Интерфейсы создаются на основе СУЩЕСТВУЮЩЕГО кода, не наоборот.

1. **UI Agent создает ViewModel** в `presentation/` layer
2. **Architect Agent изучает реальный код** via grz_read_file
3. **Architect создает domain интерфейс** с полным KDoc
4. **UI Agent рефакторит ViewModel** для имплементации интерфейса

**Правда находится в коде, не в документации.**

## Помни

- **Интерфейсы ← Реальный код** - не выдумывай, читай существующий код
- **ComponentVM с ASCII диаграммами** - спецификация layout
- **LogicVM без UI деталей** - оркестрация бизнес-логики
- **KDoc = полная спецификация** - UI Agent работает без вопросов
- **Verify, don't assume** - используй grz_read_file для проверки
