CREATE TABLE audit_log (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT REFERENCES "user"(id),
    username      VARCHAR(50) NOT NULL,
    action_type   VARCHAR(50) NOT NULL,
    resource      VARCHAR(50) NOT NULL,
    resource_id   VARCHAR(100),
    description   TEXT,
    action_time   TIMESTAMP NOT NULL DEFAULT NOW(),
    ip_address    VARCHAR(45),
    user_agent    TEXT,
    result        VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    error_message TEXT,
    metadata      JSONB
);

CREATE INDEX idx_audit_log_resource ON audit_log(resource, resource_id);
CREATE INDEX idx_audit_log_user ON audit_log(user_id);
CREATE INDEX idx_audit_log_action_time ON audit_log(action_time);
CREATE INDEX idx_audit_log_action_type ON audit_log(action_type);
