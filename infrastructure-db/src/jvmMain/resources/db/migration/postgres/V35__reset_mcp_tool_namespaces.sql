DELETE FROM ai_tool_capability_catalogs
WHERE source_id LIKE 'mcp:%';

DELETE FROM mcp_servers;
