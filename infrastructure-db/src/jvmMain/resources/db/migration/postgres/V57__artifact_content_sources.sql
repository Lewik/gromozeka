ALTER TABLE artifacts ADD COLUMN content_source TEXT;
UPDATE artifacts SET content_source = json_build_object('type', 'managed', 'sha256', sha256)::text;
ALTER TABLE artifacts ALTER COLUMN content_source SET NOT NULL;
ALTER TABLE artifacts DROP COLUMN sha256;
ALTER TABLE artifacts ALTER COLUMN size_bytes DROP NOT NULL;
