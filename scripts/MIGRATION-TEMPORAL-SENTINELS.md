# Temporal Sentinel Values Migration

## Overview

This migration updates temporal sentinel values from Kotlin's extreme dates to realistic dates that Neo4j can reliably handle.

## Breaking Change

**Old sentinels (problematic):**
- `ALWAYS_VALID_FROM`: `-292275055-05-16T16:47:04.192Z` (Kotlin `Instant.DISTANT_PAST`)
- `STILL_VALID`: `+292278994-08-17T07:12:55.807Z` (Kotlin `Instant.DISTANT_FUTURE`)

**New sentinels (Neo4j-compatible):**
- `ALWAYS_VALID_FROM`: `0001-01-01T00:00:00Z` (Year 1)
- `STILL_VALID`: `9999-12-31T23:59:59Z` (Year 9999)

## Why This Change?

1. **Neo4j datetime() limitations**: Neo4j's datetime() function doesn't reliably parse dates outside the 0001-9999 year range
2. **Industry standard**: PostgreSQL, Oracle, SQL Server all use 0001/9999 as min/max dates for temporal databases
3. **Practical sufficiency**: 0001-9999 covers all human history and 8000 years into the future
4. **Query reliability**: Temporal queries now work consistently without parsing errors

## Impact

**Before migration:**
- `unified_search` shows all objects as "INVALIDATED" (incorrect)
- `asOf` parameter doesn't work (returns "No results found")
- Temporal filtering fails silently

**After migration:**
- Correct validity status display
- Working point-in-time queries
- Reliable temporal filtering

## How to Migrate

### Option 1: Fresh Start (Recommended for Development)

If you're in development and can afford to lose test data:

```bash
# Delete all knowledge graph data
docker exec -it gromozeka-neo4j cypher-shell -u neo4j -p password "MATCH (n) DETACH DELETE n"
```

Application will create new entities with correct sentinel values.

### Option 2: In-Place Migration (Production)

If you have important data to preserve:

```bash
# Run migration script
docker exec -i gromozeka-neo4j cypher-shell -u neo4j -p password < scripts/migrate-temporal-sentinels.cypher
```

**What it does:**
1. Updates all `MemoryObject` nodes: converts extreme dates to 0001/9999 sentinels
2. Updates all `LINKS_TO` relationships: same conversion
3. Verifies migration: checks for remaining extreme dates (should be 0)

**Expected output:**
```
updated_nodes: 42
updated_relationships: 67
nodes_with_extreme_dates: 0
relationships_with_extreme_dates: 0
```

## Verification

After migration, test temporal queries:

```json
// Should return currently valid objects (not show INVALIDATED)
{"query": "test", "entityTypes": ["memory_objects"]}

// Should return historical state
{"query": "test", "entityTypes": ["memory_objects"], "asOf": "2023-01-01T00:00:00Z"}
```

## Rollback

**No rollback available** - this is a one-way migration. 

If you need to rollback the code:
1. Revert to previous commit
2. Run migration script in reverse (replace 0001→DISTANT_PAST, 9999→DISTANT_FUTURE)
3. **Not recommended** - old sentinels have known issues

## Timeline

- **Development**: Migrate immediately (use fresh start)
- **Beta**: Migrate before next deployment
- **Production**: Coordinate with team, use in-place migration

## Questions?

See commit message for technical details or ask in #gromozeka-dev.
