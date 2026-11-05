ALTER TABLE background_activity_origins ALTER COLUMN tool_call_id DROP NOT NULL;
-- Visual handlers have no tool call. They still admit at most one command per request.
CREATE UNIQUE INDEX background_activity_origins_visual_request
    ON background_activity_origins (worker_request_id, kind) WHERE tool_call_id IS NULL;
