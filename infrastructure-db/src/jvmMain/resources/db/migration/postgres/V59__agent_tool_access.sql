ALTER TABLE agents ADD COLUMN tool_access_json TEXT NOT NULL DEFAULT '{"type":"deny_listed","entries":[]}';
UPDATE agents SET tools_json = jsonb_build_object('names', tools_json::jsonb)::text;
