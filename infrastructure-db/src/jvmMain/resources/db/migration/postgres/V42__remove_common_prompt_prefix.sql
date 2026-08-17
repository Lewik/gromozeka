UPDATE agents
SET prompts_json = (
    SELECT COALESCE(jsonb_agg(prompt_id ORDER BY position), '[]'::jsonb)::text
    FROM jsonb_array_elements_text(agents.prompts_json::jsonb)
        WITH ORDINALITY AS prompt(prompt_id, position)
    WHERE prompt_id <> 'global:common-prompt-prefix.md'
)
WHERE agents.prompts_json::jsonb ? 'global:common-prompt-prefix.md';

DELETE FROM prompts
WHERE id = 'global:common-prompt-prefix.md';
