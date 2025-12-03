# TypeScript to Kotlin Type Generation Test

Экспериментальная утилита для генерации Kotlin data классов из TypeScript `.d.ts` определений.

## Цель

Создание типобезопасных Kotlin моделей, которые:
- Точно соответствуют TypeScript типам из Claude Agent SDK
- Поддерживают сериализацию/десериализацию через kotlinx.serialization
- Могут использоваться для type-safe взаимодействия с SDK

## Процесс генерации

```bash
./run.sh
```

### Пайплайн

1. **Извлечение типов**: `ts-json-schema-generator` читает `sdk.d.ts` из `@anthropic-ai/claude-agent-sdk`
2. **JSON Schema**: Генерируется JSON Schema для всех типов (`--type "*"`)
3. **Kotlin генерация**: `quicktype` преобразует схему в Kotlin data классы с `kotlinx.serialization`

### Результат

Файл `Models.kt` содержит все типы SDK в виде Kotlin data классов с аннотациями:
- `@Serializable` для сериализации
- `@SerialName` для соответствия оригинальным именам
- Правильные типы (nullable, collections, unions)

## Зависимости

- `@anthropic-ai/claude-agent-sdk` - источник типов
- `ts-json-schema-generator` - TypeScript → JSON Schema
- `quicktype` - JSON Schema → Kotlin

## Примечание

Это экспериментальный инструмент для исследования возможности автоматической генерации типов.
Для production использования требуется дополнительная валидация и тестирование сгенерированных классов.
