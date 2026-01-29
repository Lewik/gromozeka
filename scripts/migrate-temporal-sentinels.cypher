// Migration script: Update temporal sentinel values from extreme dates to realistic dates
// 
// BREAKING CHANGE: Sentinel values changed from DISTANT_PAST/DISTANT_FUTURE to 0001/9999
// 
// Old sentinels (Kotlin Instant.DISTANT_PAST/DISTANT_FUTURE):
//   DISTANT_PAST:   -292275055-05-16T16:47:04.192Z (year -292275055)
//   DISTANT_FUTURE: +292278994-08-17T07:12:55.807Z (year +292278994)
// 
// New sentinels (realistic dates for Neo4j):
//   ALWAYS_VALID_FROM: 0001-01-01T00:00:00Z (year 1)
//   STILL_VALID:       9999-12-31T23:59:59Z (year 9999)
//
// Why: Neo4j datetime() function doesn't reliably handle extreme dates outside 0001-9999 range
//
// Run this script ONCE after upgrading to the new temporal model.

// 1. Update MemoryObject nodes
MATCH (n:MemoryObject)
WHERE n.valid_at IS NOT NULL OR n.invalid_at IS NOT NULL
WITH n,
     CASE 
       WHEN datetime(n.valid_at).year < 1 THEN datetime('0001-01-01T00:00:00Z')
       ELSE n.valid_at
     END AS new_valid_at,
     CASE
       WHEN datetime(n.invalid_at).year > 9999 THEN datetime('9999-12-31T23:59:59Z')
       ELSE n.invalid_at
     END AS new_invalid_at
SET n.valid_at = new_valid_at,
    n.invalid_at = new_invalid_at
RETURN count(n) AS updated_nodes;

// 2. Update LINKS_TO relationships
MATCH ()-[r:LINKS_TO]->()
WHERE r.valid_at IS NOT NULL OR r.invalid_at IS NOT NULL
WITH r,
     CASE 
       WHEN datetime(r.valid_at).year < 1 THEN datetime('0001-01-01T00:00:00Z')
       ELSE r.valid_at
     END AS new_valid_at,
     CASE
       WHEN datetime(r.invalid_at).year > 9999 THEN datetime('9999-12-31T23:59:59Z')
       ELSE r.invalid_at
     END AS new_invalid_at
SET r.valid_at = new_valid_at,
    r.invalid_at = new_invalid_at
RETURN count(r) AS updated_relationships;

// 3. Verify migration - should return 0 rows
MATCH (n:MemoryObject)
WHERE datetime(n.valid_at).year < 1 OR datetime(n.invalid_at).year > 9999
RETURN count(n) AS nodes_with_extreme_dates;

MATCH ()-[r:LINKS_TO]->()
WHERE datetime(r.valid_at).year < 1 OR datetime(r.invalid_at).year > 9999
RETURN count(r) AS relationships_with_extreme_dates;
