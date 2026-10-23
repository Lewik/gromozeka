-- Spring Security Authorization Server 7.1 JDBC schema, adapted for PostgreSQL
-- (TEXT instead of BLOB, TIMESTAMPTZ instead of TIMESTAMP). Protocol serialization
-- stays in the framework; these records are separate from Gromozeka user sessions/PATs.
CREATE TABLE oauth2_registered_client (
    id varchar(100) PRIMARY KEY,
    client_id varchar(2048) NOT NULL UNIQUE,
    client_id_issued_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200),
    client_secret_expires_at timestamptz,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris text,
    post_logout_redirect_uris text,
    scopes varchar(1000) NOT NULL,
    client_settings text NOT NULL,
    token_settings text NOT NULL
);

CREATE TABLE oauth2_authorization (
    id varchar(100) PRIMARY KEY,
    registered_client_id varchar(100) NOT NULL REFERENCES oauth2_registered_client(id) ON DELETE CASCADE,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000),
    attributes text,
    state varchar(500),
    authorization_code_value text,
    authorization_code_issued_at timestamptz,
    authorization_code_expires_at timestamptz,
    authorization_code_metadata text,
    access_token_value text,
    access_token_issued_at timestamptz,
    access_token_expires_at timestamptz,
    access_token_metadata text,
    access_token_type varchar(100),
    access_token_scopes varchar(1000),
    oidc_id_token_value text,
    oidc_id_token_issued_at timestamptz,
    oidc_id_token_expires_at timestamptz,
    oidc_id_token_metadata text,
    refresh_token_value text,
    refresh_token_issued_at timestamptz,
    refresh_token_expires_at timestamptz,
    refresh_token_metadata text,
    user_code_value text,
    user_code_issued_at timestamptz,
    user_code_expires_at timestamptz,
    user_code_metadata text,
    device_code_value text,
    device_code_issued_at timestamptz,
    device_code_expires_at timestamptz,
    device_code_metadata text
);
CREATE INDEX oauth2_authorization_principal_idx ON oauth2_authorization(principal_name);
CREATE INDEX oauth2_authorization_access_idx ON oauth2_authorization(access_token_value) WHERE access_token_value IS NOT NULL;
CREATE INDEX oauth2_authorization_refresh_idx ON oauth2_authorization(refresh_token_value) WHERE refresh_token_value IS NOT NULL;
CREATE INDEX oauth2_authorization_code_idx ON oauth2_authorization(authorization_code_value) WHERE authorization_code_value IS NOT NULL;
CREATE INDEX oauth2_authorization_state_idx ON oauth2_authorization(state) WHERE state IS NOT NULL;

CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL REFERENCES oauth2_registered_client(id) ON DELETE CASCADE,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

-- Retain only hashes of every issued refresh token until its grant is removed.
-- A previous token must remain recognizable after rotation so reuse can revoke
-- the entire grant, rather than merely rejecting the old token and leaving its
-- successor usable by an attacker.
CREATE TABLE oauth2_refresh_history (
    token_hash char(64) PRIMARY KEY,
    authorization_id varchar(100) NOT NULL REFERENCES oauth2_authorization(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);
CREATE INDEX oauth2_refresh_history_grant_idx ON oauth2_refresh_history(authorization_id);
