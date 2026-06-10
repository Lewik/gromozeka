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
-Dgromozeka.longmemeval.sample=balanced[:per-type-count]
-Dgromozeka.longmemeval.data=/absolute/path/to/longmemeval_*.json
```

Artifacts are written to:

```text
server/build/test-artifacts/longmemeval/LongMemEvalMemorySmokeTest/
```

The test reports semantic support through an LLM judge and also tracks whether selected memory refs point back to expected LongMemEval evidence source ids.
