ALTER TABLE users ADD COLUMN login_allowed BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN ai_allowed BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE user_identities (
    identity_key VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    identity_json TEXT NOT NULL
);
CREATE INDEX user_identities_user_idx ON user_identities(user_id);

INSERT INTO user_identities(identity_key, user_id, identity_json)
SELECT 'local:' || username, id,
    json_build_object('type', 'local_login', 'username', username)::text
FROM users;

ALTER TABLE users DROP COLUMN username;
