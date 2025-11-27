# План рефакторинга: Удаление `bot` из пакетной структуры

**Цель:** `com.gromozeka.bot.*` → `com.gromozeka.*`

**Масштаб:**
- 29 файлов для перемещения (domain модуль)
- ~27 файлов с импортами для обновления (infrastructure-ai, infrastructure-db, presentation)
- НЕТ конфликтов имен (файлы не пересекаются)

## Текущая структура

```
domain/src/commonMain/kotlin/com/gromozeka/
├── bot/
│   └── domain/
│       ├── model/            # 13 файлов
│       │   └── memory/       # 3 файла (MemoryLink, MemoryObject, EntityType)
│       ├── repository/       # 16 файлов
│       └── service/          # пустая
└── domain/
    ├── model/                # 3 файла (TtsTask, AIProvider, AppMode)
    ├── service/              # 7 файлов
    └── presentation/
        └── desktop/
            ├── component/
            └── logic/
```

## Целевая структура

```
domain/src/commonMain/kotlin/com/gromozeka/
└── domain/
    ├── model/                # 16 файлов (3 старых + 13 новых)
    │   └── memory/           # 3 файла
    ├── repository/           # 16 файлов (НОВАЯ ДИРЕКТОРИЯ)
    ├── service/              # 7 файлов
    └── presentation/
        └── desktop/
            ├── component/
            └── logic/
```

---

## Этап 1: Подготовка

### 1.1 Создать недостающие директории

```bash
mkdir -p domain/src/commonMain/kotlin/com/gromozeka/domain/repository
mkdir -p domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory
```

### 1.2 Backup (на всякий случай)

```bash
git checkout -b refactor/remove-bot-package
git add -A
git commit -m "checkpoint: before removing bot package"
```

---

## Этап 2: Перемещение файлов

### 2.1 Переместить model файлы

```bash
mv domain/src/commonMain/kotlin/com/gromozeka/bot/domain/model/*.kt \
   domain/src/commonMain/kotlin/com/gromozeka/domain/model/
```

**Файлы (13):**
- Agent.kt
- Conversation.kt
- ConversationInitiator.kt
- MessageTag.kt
- ModelContextWindows.kt
- Project.kt
- Prompt.kt
- SquashType.kt
- Tab.kt
- TokenUsageStatistics.kt

### 2.2 Переместить memory файлы

```bash
mv domain/src/commonMain/kotlin/com/gromozeka/bot/domain/model/memory/*.kt \
   domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory/
```

**Файлы (3):**
- EntityType.kt
- MemoryLink.kt
- MemoryObject.kt

### 2.3 Переместить repository файлы

```bash
mv domain/src/commonMain/kotlin/com/gromozeka/bot/domain/repository/*.kt \
   domain/src/commonMain/kotlin/com/gromozeka/domain/repository/
```

**Файлы (16):**
- AgentDomainService.kt
- AgentRepository.kt
- ConversationDomainService.kt
- ConversationRepository.kt
- KnowledgeGraphStore.kt
- MemoryManagementService.kt
- MessageRepository.kt
- ProjectDomainService.kt
- ProjectRepository.kt
- PromptDomainService.kt
- PromptRepository.kt
- SquashOperationRepository.kt
- TabManager.kt
- ThreadMessageRepository.kt
- ThreadRepository.kt
- TokenUsageStatisticsRepository.kt

---

## Этап 3: Обновление package деклараций

Заменить в перемещенных файлах:

### 3.1 Model файлы (13 файлов)

```bash
# В каждом файле domain/model/*.kt
package com.gromozeka.bot.domain.model
→
package com.gromozeka.domain.model
```

**Автоматизация:**
```bash
find domain/src/commonMain/kotlin/com/gromozeka/domain/model -maxdepth 1 -name "*.kt" -type f \
  -exec sed -i '' 's/package com\.gromozeka\.bot\.domain\.model/package com.gromozeka.domain.model/g' {} \;
```

### 3.2 Memory файлы (3 файла)

```bash
find domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory -name "*.kt" -type f \
  -exec sed -i '' 's/package com\.gromozeka\.bot\.domain\.model\.memory/package com.gromozeka.domain.model.memory/g' {} \;
```

### 3.3 Repository файлы (16 файлов)

```bash
find domain/src/commonMain/kotlin/com/gromozeka/domain/repository -name "*.kt" -type f \
  -exec sed -i '' 's/package com\.gromozeka\.bot\.domain\.repository/package com.gromozeka.domain.repository/g' {} \;
```

### 3.4 Обновить внутренние импорты в domain модуле

В перемещенных файлах могут быть импорты друг друга:

```bash
find domain/src/commonMain/kotlin/com/gromozeka/domain -name "*.kt" -type f \
  -exec sed -i '' 's/import com\.gromozeka\.bot\.domain\./import com.gromozeka.domain./g' {} \;
```

---

## Этап 4: Обновление импортов в других модулях

### 4.1 Infrastructure-DB (3 файла)

**Файлы:**
- infrastructure-db/src/.../Neo4jKnowledgeGraphStore.kt
- infrastructure-db/src/.../MemoryExtractionPrompts.kt
- infrastructure-db/src/.../MemoryManagementService.kt
- infrastructure-db/src/.../GraphPersistenceService.kt

```bash
find infrastructure-db/src -name "*.kt" -type f \
  -exec sed -i '' 's/import com\.gromozeka\.bot\.domain\./import com.gromozeka.domain./g' {} \;
```

### 4.2 Infrastructure-AI (~20 файлов)

**Файлы:**
- tools (memory, mcp)
- services
- memory extraction

```bash
find infrastructure-ai/src -name "*.kt" -type f \
  -exec sed -i '' 's/import com\.gromozeka\.bot\.domain\./import com.gromozeka.domain./g' {} \;
```

### 4.3 Application модуль

```bash
find application/src -name "*.kt" -type f \
  -exec sed -i '' 's/import com\.gromozeka\.bot\.domain\./import com.gromozeka.domain./g' {} \;
```

### 4.4 Presentation (4 файла)

**Файлы:**
- SessionListScreen.kt
- AppViewModel.kt
- ChatWindow.kt
- UIState.kt

```bash
find presentation/src -name "*.kt" -type f \
  -exec sed -i '' 's/import com\.gromozeka\.bot\.domain\./import com.gromozeka.domain./g' {} \;
```

---

## Этап 5: Очистка

### 5.1 Удалить bot директорию

```bash
rm -rf domain/src/commonMain/kotlin/com/gromozeka/bot
```

### 5.2 Очистить build артефакты

```bash
./gradlew clean
rm -rf domain/build/classes/kotlin/jvm/main/com/gromozeka/bot
```

---

## Этап 6: Проверка

### 6.1 Компиляция domain

```bash
./gradlew :domain:compileKotlin -q || ./gradlew :domain:compileKotlin
```

### 6.2 Полная сборка

```bash
./gradlew build -q || ./gradlew build
```

### 6.3 Проверка Spring контекста

```bash
./gradlew :presentation:jvmTest --tests ApplicationContextTest -q
```

### 6.4 Поиск оставшихся упоминаний bot

```bash
rg "com\.gromozeka\.bot\." --type kotlin -l
```

Должен вернуть пустой результат!

---

## Этап 7: Фиксация

```bash
git add -A
git commit -m "refactor: remove 'bot' package from structure

- Move com.gromozeka.bot.domain.* → com.gromozeka.domain.*
- Update all imports in infrastructure and presentation layers
- Clean architecture: com.gromozeka.{domain,application,infrastructure,presentation}

Affected:
- 29 files moved (domain module)
- ~27 files updated (imports in other modules)
- No naming conflicts"

git push origin refactor/remove-bot-package
```

---

## Возможные проблемы и решения

### Проблема 1: Конфликты слияния в model/

**Симптом:** Файлы с одинаковыми именами в обоих пакетах

**Решение:** Проверено - конфликтов НЕТ, файлы разные

### Проблема 2: Ошибки компиляции после перемещения

**Симптом:** Cannot find symbol/Unresolved reference

**Решение:** 
1. Проверить что package декларации обновлены
2. Проверить импорты в файле
3. Запустить `./gradlew clean build`

### Проблема 3: Spring не находит бины

**Симптом:** NoSuchBeanDefinitionException

**Решение:** Проверить component scan в Spring конфигурации:
```kotlin
@ComponentScan("com.gromozeka")  // Должно работать для всех пакетов
```

### Проблема 4: Старые импорты остались

**Симптом:** Есть упоминания `com.gromozeka.bot` после рефакторинга

**Решение:**
```bash
rg "com\.gromozeka\.bot\." -l | xargs sed -i '' 's/com\.gromozeka\.bot\./com.gromozeka./g'
```

---

## Итоговая статистика

**До рефакторинга:**
- ✗ Дублирующая структура (`bot.domain` и `domain`)
- ✗ Лишний уровень вложенности
- ✗ Запутанные импорты

**После рефакторинга:**
- ✓ Чистая структура: `com.gromozeka.{domain,application,infrastructure,presentation}`
- ✓ Удален лишний уровень `bot`
- ✓ Единообразные импорты
- ✓ 29 файлов переехали
- ✓ ~27 файлов обновлено

**Время выполнения:** ~30-40 минут (с проверками)

---

## Checklist выполнения

- [ ] Создать ветку `refactor/remove-bot-package`
- [ ] Сделать checkpoint commit
- [ ] Создать директории `domain/repository` и `domain/model/memory`
- [ ] Переместить 13 model файлов
- [ ] Переместить 3 memory файла
- [ ] Переместить 16 repository файлов
- [ ] Обновить package в model файлах
- [ ] Обновить package в memory файлах
- [ ] Обновить package в repository файлах
- [ ] Обновить импорты в domain модуле
- [ ] Обновить импорты в infrastructure-db
- [ ] Обновить импорты в infrastructure-ai
- [ ] Обновить импорты в application
- [ ] Обновить импорты в presentation
- [ ] Удалить bot директорию
- [ ] Очистить build артефакты
- [ ] Проверить компиляцию domain
- [ ] Проверить полную сборку
- [ ] Проверить Spring контекст
- [ ] Проверить отсутствие bot упоминаний
- [ ] Commit и push

---

**Готов к выполнению!** 

Выйти из readonly режима и начинать?
