#!/usr/bin/env bash
set -euo pipefail
set -x

# Path to the TypeScript SDK d.ts file
SDK_DTS="node_modules/@anthropic-ai/claude-agent-sdk/sdk.d.ts"

# Output Kotlin file
OUTPUT_KOTLIN="Models.kt"

# Generate JSON Schema (all types) → pipe into quicktype → generate Kotlin data classes
./node_modules/.bin/ts-json-schema-generator \
  --path "$SDK_DTS" \
  --type "*" \
  --tsconfig ts-json-schema-config.json \
  | ./node_modules/.bin/quicktype --lang kotlin --src - -o "$OUTPUT_KOTLIN"  --framework kotlinx

echo "✅ Kotlin models generated: $OUTPUT_KOTLIN"