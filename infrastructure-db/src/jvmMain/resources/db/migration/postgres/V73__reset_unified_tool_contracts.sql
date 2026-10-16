-- Tool fingerprints now cover output schemas, annotations and declared access policy.
-- This registry is derived from current Server/Worker declarations and is rebuilt on discovery.
-- Do not rewrite message text, historical tool calls, or Agent policies: exact-revision selections
-- are reinitialized explicitly after the coordinated Server/Worker/Client update.
DELETE FROM ai_tool_contracts;
