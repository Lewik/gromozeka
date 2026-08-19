ALTER TABLE messages
    ADD COLUMN search_text TEXT NOT NULL DEFAULT '';

UPDATE messages
SET search_text = COALESCE(
    (
        SELECT string_agg(
            CASE item ->> 'type'
                WHEN 'Message' THEN item ->> 'text'
                WHEN 'IntermediateMessage' THEN item #>> '{structured,fullText}'
                ELSE NULL
            END,
            E'\n'
            ORDER BY position
        )
        FROM jsonb_array_elements(message_json::jsonb -> 'content') WITH ORDINALITY AS content(item, position)
    ),
    ''
);

CREATE INDEX idx_messages_search_text_trgm
    ON messages USING GIN(search_text gin_trgm_ops);

CREATE INDEX idx_conversations_display_name_trgm
    ON conversations USING GIN(display_name gin_trgm_ops);

CREATE INDEX idx_projects_name_trgm
    ON projects USING GIN(name gin_trgm_ops);

CREATE INDEX idx_projects_description_trgm
    ON projects USING GIN(description gin_trgm_ops);
