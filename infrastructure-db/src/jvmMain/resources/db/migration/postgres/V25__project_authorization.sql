ALTER TABLE users
    ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'MEMBER';

ALTER TABLE users
    ADD CONSTRAINT users_role_check CHECK (role IN ('OWNER', 'MEMBER'));

UPDATE users
SET role = 'OWNER'
WHERE id = (
    SELECT id
    FROM users
    ORDER BY created_at, id
    LIMIT 1
);

CREATE TABLE project_memberships (
    project_id VARCHAR(255) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT project_memberships_role_check CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER'))
);

CREATE INDEX project_memberships_user_id_idx
    ON project_memberships(user_id);

INSERT INTO project_memberships (
    project_id,
    user_id,
    role,
    created_at,
    created_by_user_id
)
SELECT
    projects.id,
    first_user.id,
    'OWNER',
    first_user.created_at,
    first_user.id
FROM projects
CROSS JOIN LATERAL (
    SELECT id, created_at
    FROM users
    ORDER BY created_at, id
    LIMIT 1
) first_user;
