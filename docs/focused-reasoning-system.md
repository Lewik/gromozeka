# Focused Reasoning System (FRS)
## Архитектурная спецификация для решения проблемы post-hoc rationalization в LLM

### Оглавление
1. [Контекст и проблематика](#контекст)
2. [Научное обоснование](#научное-обоснование)
3. [Предлагаемое решение](#решение)
4. [Архитектура системы](#архитектура)
5. [Детальная спецификация](#спецификация)
6. [Сравнение с существующими подходами](#сравнение)
7. [План реализации](#реализация)

---

## 1. Контекст и проблематика {#контекст}

### Проблема: Post-hoc Rationalization в Chain-of-Thought

Современные LLM при использовании Chain-of-Thought (CoT) рассуждений часто демонстрируют **post-hoc rationalization** - подгонку объяснений под заранее известный ответ, вместо genuine пошагового решения.

**Ключевые исследования:**

1. **"Chain-of-Thought Is Not Explainability"** (Oxford, July 2025)
   - Источник: [Oxford WhiteBox Research](https://aigi.ox.ac.uk/wp-content/uploads/2025/07/Cot_Is_Not_Explainability.pdf)
   - Находки: 25% научных работ некорректно представляют CoT как интерпретируемость
   - В критических областях хуже: 38% в медицине, 63% в автономных системах

2. **"Language Models Don't Always Say What They Think"** (Turpin et al., 2023)
   - Источник: [arXiv:2305.04388](https://arxiv.org/abs/2305.04388)
   - Находки: Перестановка вариантов ответа меняет результат в 36% случаев, но CoT никогда не упоминает это влияние

3. **"Measuring Faithfulness in Chain-of-Thought Reasoning"** (Anthropic, 2023)
   - Источник: [arXiv:2307.13702](https://arxiv.org/abs/2307.13702)
   - Находки: Модели исправляют ошибки внутренне без отражения в CoT

### Конкретные паттерны unfaithfulness:

1. **Bias-driven rationalization** - модель рационализирует любой выбранный ответ
2. **Silent error correction** - исправляет ошибки без упоминания в CoT
3. **Unfaithful shortcuts** - использует memorization, но описывает алгоритмическое решение
4. **Filler tokens** - улучшение от дополнительных токенов, а не от рассуждений

### Архитектурная причина

Трансформеры обрабатывают информацию **параллельно и распределенно**, а CoT пытается представить это как **последовательный процесс**. Это фундаментальное несоответствие создает пространство для post-hoc rationalization.

---

## 2. Научное обоснование {#научное-обоснование}

### Существующие подходы и их ограничения

#### Tree of Thoughts (ToT)
- **Источник**: [arXiv:2305.10601](https://arxiv.org/abs/2305.10601) (Yao et al., 2023)
- **Преимущества**: Исследование альтернатив, backtracking
- **Ограничения**: Не решает post-hoc rationalization, дорого (5-100x токенов)

#### Plan-and-Execute Agents
- **Источник**: [LangChain Blog](https://blog.langchain.com/planning-agents/)
- **Варианты**: Basic, ReWOO, LLMCompiler
- **Ограничения**: План видим исполнителю, возможна подгонка

#### Reflexion Framework
- **Источник**: [arXiv:2303.11366](https://arxiv.org/abs/2303.11366)
- **Преимущества**: Эпизодическая память, самоанализ
- **Ограничения**: Не скрывает конечную цель от промежуточных шагов

#### Anthropic Multi-Agent Research System
- **Источник**: [Anthropic Engineering](https://www.anthropic.com/engineering/multi-agent-research-system)
- **Архитектура**: LeadResearcher + параллельные Subagents
- **Результаты**: 90.2% улучшение над single-agent на research задачах

#### Process Reward Models (OpenAI)
- **Источник**: [PRM800K Dataset](https://github.com/openai/prm800k)
- **Подход**: Пошаговая верификация правильности
- **Ограничения**: Требует большой датасет меток

---

## 3. Предлагаемое решение {#решение}

### Философия подхода

**Ключевая инновация**: Управление фокусом и видимостью информации для принуждения к честным рассуждениям через архитектурное разделение ролей.

### Основные принципы

1. **Разделение планирования и исполнения** - планировщик не исполняет, исполнитель не видит план
2. **Управляемый фокус** - глубокая проработка одного шага за раз
3. **Скрытый план** - исполнитель не может подгонять под известный результат
4. **Проактивный research** - не экономим токены на качестве
5. **Редактируемая история** - можем исправлять с сохранением контекста
6. **Финальная дистилляция** - оправдывает все траты токенов

### Уникальные инновации

Что НЕ делает ни один существующий подход:
- **"Забывание" плана** после создания
- **Динамическое управление видимостью контекста**
- **Редактирование execution треда**
- **Принудительная фокусировка через ограничение информации**

---

## 4. Архитектура системы {#архитектура}

### Высокоуровневая архитектура

```
┌────────────────────────────────────────────────┐
│                 USER QUERY                      │
└──────────────────┬─────────────────────────────┘
                   ↓
┌────────────────────────────────────────────────┐
│          COMPLEXITY ASSESSMENT                  │
│  Simple → Direct Answer                         │
│  Complex → Continue to Planning                 │
└──────────────────┬─────────────────────────────┘
                   ↓
┌────────────────────────────────────────────────┐
│         PHASE 1: PLANNING                       │
│  • Context Gathering (web, files, code)         │
│  • Plan Creation                                │
│  • Save to Memory & HIDE                        │
└──────────────────┬─────────────────────────────┘
                   ↓
┌────────────────────────────────────────────────┐
│         PHASE 2: EXECUTION                      │
│  For each step:                                 │
│  • Receive step WITHOUT plan context            │
│  • Deep focus on single step                    │
│  • Proactive research                           │
│  • Add results to reasoning thread              │
└──────────────────┬─────────────────────────────┘
                   ↓
┌────────────────────────────────────────────────┐
│         PHASE 3: CHECKPOINTS                    │
│  • Show plan + progress                         │
│  • Validate alignment                           │
│  • Adjust plan or revise history if needed      │
└──────────────────┬─────────────────────────────┘
                   ↓
┌────────────────────────────────────────────────┐
│         PHASE 4: DISTILLATION                   │
│  • Compress reasoning to essence                │
│  • Preserve sources and citations               │
│  • Clean final output                           │
└────────────────────────────────────────────────┘
```

### Структуры данных

```python
class SystemThreads:
    historical_thread = []    # Сохраняется между взаимодействиями
    reasoning_thread = []     # Рабочий (historical + промежуточные)
    discarded_log = []        # Все удаленное для UI анализа
    
class Memory:
    current_plan = None       # План текущей задачи (скрыт от executor)
    context_sources = []      # Источники информации
    checkpoints = []          # История валидаций
```

---

## 5. Детальная спецификация {#спецификация}

### Протокол выполнения

#### Шаблон: Сбор контекста

Универсальный шаблон для всех фаз, требующих информации:

```python
context_gathering_template:
    Turn 1: "Что нужно найти?" → needs_list
    Turn 2: "Что уже знаем?" → known_facts + search_plan  
    Turn 3: "Выполнить поиски" → results with sources
    
    Output: {
        "results": compressed_findings,
        "sources": ["URL: ...", "FILE: path:line", "CODE: function"]
    }
```

#### Phase 0: Оценка сложности

```python
Complexity Assessment:
    Input: historical_thread + user_message
    
    Criteria:
        - Требуется ли поиск информации?
        - Многошаговая ли задача?
        - Нужен ли глубокий анализ?
    
    Output:
        SIMPLE → Direct answer → Update historical_thread → END
        COMPLEX → Continue to planning
```

#### Phase 1: Планирование

```python
Planning Phase:
    1. Context Gathering (по шаблону)
       → В reasoning_thread попадают ТОЛЬКО результаты
    
    2. Plan Creation
       Input: reasoning_thread с контекстом
       Output: Детальный план с:
           - Execution steps
           - Checkpoint steps
           - Success criteria
    
    3. CRITICAL: План сохраняется в Memory и НЕ попадает в reasoning_thread
```

#### Phase 2: Исполнение

```python
Execution Phase:
    For each step in plan:
        Input to executor:
            - reasoning_thread (БЕЗ плана!)
            - current step description
            - step number
        
        Executor actions:
            1. Context gathering (если нужно)
            2. Deep execution с фокусом
            3. Self-validation результата
        
        Output: Добавляется в reasoning_thread
```

#### Phase 3: Checkpoints

```python
Checkpoint Phase:
    Input to auditor:
        - Full plan (из Memory)
        - Current progress (reasoning_thread)
        - Checkpoint criteria
    
    Possible actions:
        CONTINUE: Все соответствует плану
        ADJUST_PLAN: Изменить план на основе находок
        REVISE_HISTORY: Исправить предыдущие шаги
```

#### Phase 4: Дистилляция

```python
Distillation Phase:
    Input: Полный reasoning_thread
    
    Actions:
        - Сжать до ключевых находок
        - Сохранить все источники
        - Убрать избыточность
    
    Output: 
        - Clean answer для пользователя
        - Обновленный historical_thread
        - Все промежуточное → discarded_log
```

### Управление видимостью

| Роль | Видит | НЕ видит |
|------|-------|----------|
| **Assessor** | Historical thread + query | - |
| **Planner** | Thread + gathered context | - |
| **Executor** | Thread + current step ONLY | Full plan, other steps |
| **Auditor** | Thread + full plan + history | - |
| **Distiller** | Complete reasoning thread | - |

---

## 6. Сравнение с существующими подходами {#сравнение}

| Характеристика | ToT | Plan-Execute | ReWOO | Reflexion | PASS | **FRS (наш)** |
|---------------|-----|--------------|-------|-----------|------|---------------|
| Избегает post-hoc | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Скрытый план | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Альтернативные пути | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| Параллельное выполнение | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Переменные/ссылки | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ |
| Эпизодическая память | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ |
| Проактивный поиск | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Редактирование истории | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Управление контекстом | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |

### Ожидаемые преимущества

1. **Качество рассуждений**: Невозможность подгонки под известный ответ
2. **Глубина анализа**: Фокус на одном шаге без отвлечений
3. **Гибкость**: Возможность корректировки в процессе
4. **Прозрачность**: Полный лог процесса для анализа
5. **Масштабируемость**: Параллельное выполнение независимых шагов

---

## 7. План реализации {#реализация}

### Phase 1: MVP (2-3 недели)

1. **Базовая инфраструктура**
   - Thread management (historical, reasoning, discarded)
   - Memory storage для планов
   - Logging система

2. **Основной workflow**
   - Complexity assessment
   - Simple planning (без checkpoints)
   - Basic execution (один исполнитель)
   - Simple distillation

3. **Тестирование на простых задачах**

### Phase 2: Полная система (4-6 недель)

1. **Продвинутое планирование**
   - Multi-step планы
   - Checkpoint интеграция
   - Dependency management

2. **Улучшенное исполнение**
   - Параллельные исполнители
   - Proactive research integration
   - Self-validation механизмы

3. **Система checkpoints**
   - Plan adjustment логика
   - History revision механизмы

### Phase 3: Оптимизация (2-4 недели)

1. **Performance tuning**
   - Token usage оптимизация
   - Caching механизмы
   - Parallel execution

2. **UI для анализа**
   - Visualisation of reasoning process
   - Plan evolution tracking
   - Debugging interface

### Phase 4: Production (ongoing)

1. **Мониторинг и метрики**
2. **A/B тестирование vs существующие подходы**
3. **Итеративные улучшения на основе данных**

---

## Заключение

Focused Reasoning System представляет собой принципиально новый подход к организации рассуждений LLM, который решает фундаментальную проблему post-hoc rationalization через архитектурное разделение ролей и управление видимостью информации.

### Ключевые инновации:
- Первая система со скрытым планом от исполнителя
- Управляемый фокус через ограничение контекста
- Редактируемая история с сохранением прозрачности
- Проактивный research без компромиссов по качеству

### Научная база:
Система основана на последних исследованиях в области faithfulness of CoT (Oxford 2025, Anthropic 2023) и интегрирует лучшие практики из Tree of Thoughts, Plan-and-Execute агентов, и multi-agent систем.

### Практическая ценность:
Ожидаемое улучшение качества рассуждений на сложных задачах при сохранении прозрачности процесса для debugging и анализа.

---

## Приложение: Ссылки и ресурсы

### Ключевые исследования
1. [Chain-of-Thought Is Not Explainability (Oxford, 2025)](https://aigi.ox.ac.uk/wp-content/uploads/2025/07/Cot_Is_Not_Explainability.pdf)
2. [Language Models Don't Always Say What They Think (2023)](https://arxiv.org/abs/2305.04388)
3. [Tree of Thoughts (2023)](https://arxiv.org/abs/2305.10601)
4. [Reflexion Framework (2023)](https://arxiv.org/abs/2303.11366)
5. [Measuring Faithfulness in CoT (2023)](https://arxiv.org/abs/2307.13702)

### Системы и реализации
1. [Anthropic Multi-Agent Research](https://www.anthropic.com/engineering/multi-agent-research-system)
2. [LangChain Plan-and-Execute](https://blog.langchain.com/planning-agents/)
3. [OpenAI PRM800K](https://github.com/openai/prm800k)
4. [ReAct Agent Pattern](https://arxiv.org/abs/2210.03629)

### Дополнительные материалы
1. [Self-Consistency in CoT (Wang et al., 2022)](https://arxiv.org/abs/2203.11171)
2. [DeepSeek-R1 Reasoning](https://arxiv.org/abs/2501.08156)
3. [LLM Powered Autonomous Agents](https://lilianweng.github.io/posts/2023-06-23-agent/)

---

*Документ подготовлен для передачи архитектору системы. Содержит полное описание проблематики, научное обоснование, детальную спецификацию и план реализации Focused Reasoning System.*