# LongMemEval Memory Benchmark

LongMemEval data is not downloaded by tests. Prepare it explicitly:

```bash
./gradlew :server:downloadLongMemEvalData -q
```

Default data path:

```text
.sources/longmemeval/data/longmemeval_oracle.json
```

Smoke record/replay:

```bash
./gradlew :server:test \
  --tests 'com.gromozeka.server.LongMemEvalMemorySmokeTest' \
  -Dgromozeka.longmemeval=true \
  -Dgromozeka.longmemeval.caseFilter=1903aded \
  -Dgromozeka.llm.cassette.mode=record-missing \
  -q

./gradlew :server:test \
  --tests 'com.gromozeka.server.LongMemEvalMemorySmokeTest' \
  -Dgromozeka.longmemeval=true \
  -Dgromozeka.longmemeval.caseFilter=1903aded \
  -Dgromozeka.llm.cassette.mode=replay-only \
  -q
```

Full run:

```bash
./gradlew :server:test \
  --tests 'com.gromozeka.server.LongMemEvalMemorySmokeTest' \
  -Dgromozeka.longmemeval=true \
  -Dgromozeka.longmemeval.limit=all \
  -Dgromozeka.llm.cassette.mode=record-missing \
  -q
```

Useful selection knobs:

```text
-Dgromozeka.longmemeval.caseFilter=<question-id-or-substring>
-Dgromozeka.longmemeval.type=<question_type>[,<question_type>]
-Dgromozeka.longmemeval.sample=balanced[:per-type-count][@page]
-Dgromozeka.longmemeval.data=/absolute/path/to/longmemeval_*.json
-Dgromozeka.longmemeval.modelName=<provider-model-id>
-Dgromozeka.longmemeval.readSearchModelName=<provider-model-id>
-Dgromozeka.longmemeval.readSearchReasoningEffort=low|medium|high|max
```

`readSearchModelName` and `readSearchReasoningEffort` change only the memory
planner and selector. Memory write, answer hypothesis generation, and the judge
remain on the base model so read-stage optimizations can be compared without
changing the rest of the benchmark.

`balanced:4` selects the first four cases of each question type. `balanced:4@2`
selects the next four cases of each type. This keeps benchmark shards
deterministic and cassette-friendly without random sampling.

Artifacts are written to:

```text
server/build/test-artifacts/longmemeval/LongMemEvalMemorySmokeTest/
```

Key artifacts:

```text
results.jsonl              # Gromozeka memory smoke details per case
official-hypotheses.jsonl  # LongMemEval-compatible {question_id, hypothesis}
summary.md                 # Human-readable run summary
cases/*.md                 # Per-case dossiers with write/read traces
```

The test first ingests LongMemEval sessions, then calls `memory_enrich_context`,
then generates a concise default-Gromozeka answer hypothesis from retrieved
memory without seeing the expected answer. A separate internal judge compares
that hypothesis with the expected answer for development feedback. The
`official-hypotheses.jsonl` file is the one to pass to LongMemEval's external
`evaluate_qa.py gpt-4o ...` scorer.

`memory smoke pass` is intentionally answer-level: evidence-source hits are
reported as retrieval diagnostics, but they do not make an unsupported answer
pass.

The default-Gromozeka prompt used for hypothesis generation is pinned in:

```text
server/src/test/resources/eval/default-gromozeka-prompt-snapshot-2026-06-15.md
```

Do not update that snapshot as a side effect of normal prompt edits. Update it
only when intentionally changing the benchmark baseline.
