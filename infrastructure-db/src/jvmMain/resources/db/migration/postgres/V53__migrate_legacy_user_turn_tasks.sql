UPDATE conversation_runtime_records
SET record_json = replace(
        record_json::text,
        '"type": "user_turn"',
        '"type": "agent_invocation"'
    )::jsonb,
    updated_at = CURRENT_TIMESTAMP
WHERE record_json::text LIKE '%"type": "user_turn"%';
