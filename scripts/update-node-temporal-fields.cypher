// Update existing MemoryObject nodes to use relationship temporal values
//
// Problem: Nodes currently have sentinel values (0001/9999) ignoring actual temporal data
// Solution: For nodes created via relationships, inherit temporal values from their relationships
//
// This is a one-time migration after fixing MemoryManagementService to use validTime/invalidTime

// Update nodes that have relationships - use the earliest valid_at and latest invalid_at
MATCH (n:MemoryObject)
OPTIONAL MATCH (n)-[r:LINKS_TO]-()
WITH n, 
     COALESCE(min(r.valid_at), n.valid_at) as earliest_valid,
     COALESCE(max(r.invalid_at), n.invalid_at) as latest_invalid
WHERE earliest_valid IS NOT NULL OR latest_invalid IS NOT NULL
SET n.valid_at = earliest_valid,
    n.invalid_at = latest_invalid
RETURN count(n) as updated_nodes;

// Verify - should show nodes with varied temporal values
MATCH (n:MemoryObject)
RETURN DISTINCT n.valid_at, n.invalid_at
ORDER BY n.valid_at
LIMIT 10;
