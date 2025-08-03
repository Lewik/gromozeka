# Research Tests

This directory contains investigative/exploratory tests that analyze data structures and generate reports.

## Purpose

These tests are used for:
- Analyzing JSON message structures from real Claude Code sessions
- Generating reports about field distributions and patterns
- Understanding data formats for development purposes

## Important Notes

⚠️ **These tests create files in the project root directory** and should not be run as part of regular test suite.

## Test Files

- `JsonStructureAnalysisTest.kt` - Analyzes JSON structures in session files
- `TypeValuesAnalysisTest.kt` - Analyzes type field values distribution 
- `MessageFieldsStructureAnalysisTest.kt` - Analyzes message and tool result field structures

## Generated Files

When run, these tests create the following files in `bot/`:
- `json-structures-analysis.txt`
- `json-structures-analysis-without-message.txt` 
- `type-values-today.txt`
- `type-values-all.txt`
- `message-and-tooluseresult-fields-analysis.txt`
- `tooluseresult-field-structures-analysis.txt`
- `message-field-structures-analysis.txt`

## Running Research Tests

Run manually when needed for investigation:
```bash
./gradlew :bot:test --tests "*.research.*"
```

**Do not run as part of regular test suite** - these are excluded from normal test execution.