CREATE TABLE translation_states (
    user_id VARCHAR(255) PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision >= 0),
    payload_json TEXT NOT NULL
);
