# FRS Message Numbering Strategy

## Контекст

Для реализации Focused Reasoning System требуется механизм адресации сообщений в reasoning thread, чтобы LLM мог указывать какие сообщения нужно сжать/заменить при distillation.

**Ключевая проблема:** LLM не видит Message.Id (UUID) в промпте, т.к. это implementation detail хранения в БД.

## Вариант 1: Абсолютная нумерация (Persistent Message Numbers)

### Концепция

Каждое сообщение получает **уникальный номер в пределах треда**, который хранится в модели и передается в LLM.

### Спецификация

**Хранение:**
```kotlin
data class Message(
    val id: Id,              // UUID (как сейчас)
    val number: Int,         // NEW: Абсолютный номер в треде
    val conversationId: Conversation.Id,
    val role: Role,
    val content: List<ContentItem>,
    // ...
)
```

**Правила нумерации:**
- **Уникальность:** В пределах одного треда (Thread.Id)
- **Начало:** С 1 (первое сообщение в треде)
- **Инкремент:** Только увеличивается, никогда не уменьшается
- **Неизменность:** После присваивания номер НЕ меняется
- **Генерация:** Только Громозека, НЕ LLM
- **Gaps разрешены:** Возникают из-за squash операций

**Исключения:**
- **Thinking blocks** - не нумеруются (имеют криптографические подписи)

**Формат в промпте:**
```
[1] User: Analyze codebase
[2] Assistant: I'll search for files...
[3] Tool: [search results - 2000 chars]
[5] Assistant: Found 15 files  ← Gap (сообщение [4] было сквошено)
[6] Tool: [file contents...]
[7] Assistant: Analysis complete
```

**UI отображение:**
- Номера [i] добавляются **только при отправке в LLM**
- Пользователь в UI **НЕ видит** [i] в тексте сообщения
- Можно отображать номер другим способом (в sidebar, metadata, etc.)

**Преимущества:**
- ✅ Стабильная адресация - номер не меняется
- ✅ Простая ссылка на сообщение - просто число
- ✅ История сохраняется - gaps показывают что было удалено
- ✅ Аудит - можно отследить какие номера пропущены

**Недостатки:**
- ❌ Gaps могут путать LLM
- ❌ Нужно хранить counter в Thread модели
- ❌ Усложнение модели данных

**Реализация:**

```kotlin
// Thread model addition
data class Thread(
    val id: Id,
    val conversationId: Conversation.Id,
    val lastMessageNumber: Int = 0,  // NEW: Counter для следующего номера
    // ...
)

// При создании сообщения
suspend fun createMessage(
    threadId: Thread.Id,
    content: String,
    role: Role
): Message {
    val thread = threadRepository.findById(threadId)!!
    val nextNumber = thread.lastMessageNumber + 1
    
    val message = Message(
        id = Message.Id(uuid7()),
        number = nextNumber,  // Присваиваем номер
        conversationId = thread.conversationId,
        role = role,
        content = listOf(ContentItem.UserMessage(content)),
        createdAt = Clock.System.now()
    )
    
    messageRepository.save(message)
    threadRepository.updateLastMessageNumber(threadId, nextNumber)  // Инкрементируем counter
    
    return message
}

// При squash - номера удаленных сообщений создают gaps
suspend fun squashMessages(
    messageIds: List<Message.Id>,
    summary: String
): Message {
    // Удаляем сообщения [3], [4] из треда
    // Создаем summary сообщение с номером [5]
    // Gap [3], [4] остается в истории нумерации
}
```

**FRS Tools:**
```kotlin
// Tool с абсолютными номерами
frs_compress_range(
    numbers: [3, 4, 5],  // Абсолютные номера сообщений
    summary: "Searched 3 sources..."
)

frs_compress_last(
    count: 3,  // Последние 3 по номерам
    summary: "..."
)
```

---

## Вариант 2: Относительная адресация (Positional References)

### Концепция

LLM использует **позиции в текущем снимке треда** для адресации сообщений, без хранения номеров в модели.

### Спецификация

**Хранение:**
- Модель Message **НЕ меняется**
- Номера генерируются **динамически** при показе треда LLM

**Формат в промпте:**
```
Reasoning Thread (6 messages):
[1] User: Analyze codebase
[2] Assistant: I'll search...
[3] Tool: [search results - 2000 chars]
[4] Assistant: Found 15 files
[5] Tool: [file contents - 5000 chars]
[6] Assistant: Analysis complete
```

**Нумерация:**
- **Динамическая:** Генерируется при каждом запросе
- **Относительная:** Позиция в текущем списке сообщений
- **1-based:** Начинается с 1
- **Непрерывная:** Нет gaps (всегда последовательная)
- **Изменяется:** При удалении сообщений позиции сдвигаются

**Адресация:**
```kotlin
// Относительные позиции
frs_compress_last(count: 3)
// Сжимает сообщения с позициями [4], [5], [6]

frs_compress_range(from: 3, to: 5)
// Сжимает сообщения на позициях 3, 4, 5 в ТЕКУЩЕМ снимке треда
```

**Преимущества:**
- ✅ Простая реализация - не нужно менять модель
- ✅ Понятная адресация - "последние 3", "с 3 по 5"
- ✅ Нет gaps - всегда последовательные номера
- ✅ Минимальные изменения в кодовой базе

**Недостатки:**
- ❌ Нестабильная адресация - позиции меняются при удалении
- ❌ Невозможно сослаться на удаленное сообщение
- ❌ Сложнее отследить историю изменений

**Реализация:**

```kotlin
// Domain Service
interface ReasoningThreadService {
    
    /**
     * Compresses last N messages from temp layer into summary.
     * 
     * Positions are RELATIVE to current thread snapshot.
     * After compression, positions will shift.
     *
     * @param count number of messages from END of temp layer
     */
    suspend fun compressLast(
        sessionId: ReasoningSession.Id,
        count: Int,
        summary: String
    ): Message.Id
    
    /**
     * Compresses messages by POSITIONS (1-based) in current thread.
     *
     * Example:
     * Thread before: [1] Msg A, [2] Msg B, [3] Msg C, [4] Msg D
     * compressRange(from: 2, to: 3, summary: "BC")
     * Thread after:  [1] Msg A, [2] BC, [3] Msg D
     *
     * @param from 1-based position (inclusive)
     * @param to 1-based position (inclusive)
     */
    suspend fun compressRange(
        sessionId: ReasoningSession.Id,
        from: Int,
        to: Int,
        summary: String
    ): Message.Id
    
    /**
     * Views reasoning thread with dynamic numbering.
     */
    suspend fun viewThread(
        sessionId: ReasoningSession.Id,
        showPlan: Boolean = false
    ): String
}

// Implementation
class ReasoningThreadServiceImpl : ReasoningThreadService {
    
    override suspend fun compressRange(
        sessionId: ReasoningSession.Id,
        from: Int,
        to: Int,
        summary: String
    ): Message.Id {
        val session = sessionRepository.findById(sessionId) 
            ?: throw IllegalStateException("Session not found")
        
        // Загружаем сообщения треда
        val messages = messageRepository.getByThread(session.reasoningThreadId)
        
        // Конвертируем ПОЗИЦИИ в Message IDs
        val toCompressIds = messages.subList(from - 1, to).map { it.id }
        
        // Выполняем сжатие по IDs
        val summaryMessage = createSummaryMessage(summary)
        
        // Удаляем исходные, добавляем summary
        toCompressIds.forEach { messageRepository.deleteFromThread(session.reasoningThreadId, it) }
        messageRepository.addToThread(session.reasoningThreadId, summaryMessage.id)
        
        // Перемещаем удаленные в discarded log
        session.discardedLog.addAll(toCompressIds)
        
        return summaryMessage.id
    }
}
```

**FRS MCP Tools:**
```kotlin
@Service
class CompressLastMessagesTool : GromozekaMcpTool {
    @Serializable
    data class Input(
        val count: Int,
        val summary: String,
        val session_id: String
    )
    
    override val definition = Tool(
        name = "frs_compress_last",
        description = """
            Compress last N messages in reasoning thread into summary.
            
            IMPORTANT: Uses RELATIVE positions in current thread state.
            Example: If thread has 8 messages, compress_last(3) will compress [6], [7], [8].
        """.trimIndent(),
        // ... schema ...
    )
}
```

---

## Сравнение вариантов

| Характеристика | Вариант 1 (Абсолютная) | Вариант 2 (Относительная) |
|---------------|------------------------|---------------------------|
| Изменение модели | ✅ Нужно (Message.number) | ❌ Не нужно |
| Стабильность адресации | ✅ Стабильная | ❌ Позиции меняются |
| Gaps в нумерации | ✅ Да (показывает историю) | ❌ Нет (всегда последовательно) |
| Простота реализации | ❌ Сложнее | ✅ Проще |
| История изменений | ✅ Видна через gaps | ❌ Не видна |
| Понятность для LLM | ⚠️ Gaps могут путать | ✅ Проще |

---

## Решение

**Реализуем Вариант 2 (Относительная адресация) как первый этап.**

**Обоснование:**
1. **Минимальные изменения** - не нужно менять модель Message
2. **Быстрая реализация** - можно сделать сразу
3. **Достаточно для MVP** - позволяет реализовать FRS distillation
4. **Понятна для LLM** - "последние 3" интуитивно

**Вариант 1 можем добавить позже**, если потребуется:
- Более сложная история операций
- Точная адресация в аудите
- Ссылки на удаленные сообщения

---

## Следующие шаги

### Phase 1: Вариант 2 (сейчас)

1. **Domain models:**
   - ReasoningSession
   - ReasoningThreadStack
   - Plan, Checkpoint, ContextSource

2. **Domain services:**
   - ReasoningThreadService (compressLast, compressRange, viewThread)
   - ReasoningSessionRepository

3. **MCP Tools:**
   - frs_compress_last
   - frs_compress_range
   - frs_view_thread

### Phase 2: Вариант 1 (опционально)

1. **Migration:**
   - Добавить Message.number
   - Добавить Thread.lastMessageNumber
   - Миграция существующих данных

2. **Tools update:**
   - Поддержка абсолютных номеров
   - Обработка gaps

---

*Документ создан: 2025-11-26*
*Статус: Активное решение - реализуем Вариант 2*
