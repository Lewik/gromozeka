# Sequence Diagrams

Здесь лежат sequence-диаграммы в `PlantUML`, чтобы быстро понять основные workflow memory layer.

Файлы:

- `01_direct_structured_write_sequence.puml`
  - явные facts / preferences / tasks
- `02_note_and_mixed_write_sequence.puml`
  - `note_write` и `mixed`
- `03_read_retrieval_modes_sequence.puml`
  - read path и выбор retrieval mode
- `04_background_maintenance_sequence.puml`
  - consolidation, repair, retention
- `05_fast_slow_escalation_sequence.puml`
  - optional fast/slow runtime path

Если захочешь, следующий шаг — добавить еще activity/state диаграммы поверх этих sequence, чтобы отдельно показать lifecycle `Claim` и `Note`.
