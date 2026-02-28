-- ============================================================
-- Analytics Layer: 저장된 쿼리, 차트, 대시보드
-- ============================================================

-- 저장된 쿼리 (Saved Queries)
CREATE TABLE saved_query (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    sql_text        TEXT NOT NULL,
    dataset_id      BIGINT REFERENCES dataset(id) ON DELETE SET NULL,
    folder          VARCHAR(100),
    is_shared       BOOLEAN NOT NULL DEFAULT FALSE,
    parameters      JSONB,
    created_by      BIGINT NOT NULL REFERENCES "user"(id),
    updated_by      BIGINT REFERENCES "user"(id),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_saved_query_created_by ON saved_query(created_by);
CREATE INDEX idx_saved_query_dataset ON saved_query(dataset_id);
CREATE INDEX idx_saved_query_folder ON saved_query(folder);

-- 차트 (Charts)
CREATE TABLE chart (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    saved_query_id  BIGINT NOT NULL REFERENCES saved_query(id) ON DELETE CASCADE,
    chart_type      VARCHAR(30) NOT NULL
        CHECK (chart_type IN (
            'BAR', 'LINE', 'PIE', 'AREA', 'SCATTER', 'DONUT', 'TABLE'
        )),
    config          JSONB NOT NULL DEFAULT '{}',
    is_shared       BOOLEAN NOT NULL DEFAULT FALSE,
    created_by      BIGINT NOT NULL REFERENCES "user"(id),
    updated_by      BIGINT REFERENCES "user"(id),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chart_created_by ON chart(created_by);
CREATE INDEX idx_chart_query ON chart(saved_query_id);

-- 대시보드 (Dashboards)
CREATE TABLE dashboard (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(200) NOT NULL,
    description             TEXT,
    is_shared               BOOLEAN NOT NULL DEFAULT FALSE,
    auto_refresh_seconds    INTEGER,
    created_by              BIGINT NOT NULL REFERENCES "user"(id),
    updated_by              BIGINT REFERENCES "user"(id),
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dashboard_created_by ON dashboard(created_by);

-- 대시보드 위젯 (Dashboard Widgets)
CREATE TABLE dashboard_widget (
    id              BIGSERIAL PRIMARY KEY,
    dashboard_id    BIGINT NOT NULL REFERENCES dashboard(id) ON DELETE CASCADE,
    chart_id        BIGINT NOT NULL REFERENCES chart(id) ON DELETE CASCADE,
    position_x      INTEGER NOT NULL DEFAULT 0,
    position_y      INTEGER NOT NULL DEFAULT 0,
    width           INTEGER NOT NULL DEFAULT 6,
    height          INTEGER NOT NULL DEFAULT 4,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dashboard_widget_dashboard ON dashboard_widget(dashboard_id);
CREATE INDEX idx_dashboard_widget_chart ON dashboard_widget(chart_id);

-- 권한 (Permissions)
INSERT INTO permission (code, description, category) VALUES
    ('analytics:read', '분석 자산 조회 (쿼리, 차트, 대시보드)', 'analytics'),
    ('analytics:write', '분석 자산 생성/수정/삭제', 'analytics');

-- ADMIN 역할에 권한 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.code IN ('analytics:read', 'analytics:write');

-- USER 역할에 권한 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'USER' AND p.code IN ('analytics:read', 'analytics:write');
