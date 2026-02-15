-- data 스키마 생성 (동적 테이블 저장용)
CREATE SCHEMA IF NOT EXISTS data;

-- 데이터셋 카테고리 (동적)
CREATE TABLE dataset_category (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 기본 카테고리 시딩
INSERT INTO dataset_category (name, description) VALUES
    ('행정', '행정 업무 관련 데이터'),
    ('운영', '현장 운영 관련 데이터'),
    ('통계', '통계 및 분석 데이터');

-- 데이터셋 정의
CREATE TABLE dataset (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    table_name      VARCHAR(100) NOT NULL,
    description     TEXT,
    category_id     BIGINT REFERENCES dataset_category(id),
    dataset_type    VARCHAR(10) NOT NULL CHECK (dataset_type IN ('SOURCE', 'DERIVED')),
    created_by      BIGINT NOT NULL REFERENCES "user"(id),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_dataset_name ON dataset(name);
CREATE UNIQUE INDEX idx_dataset_table_name ON dataset(table_name);
CREATE INDEX idx_dataset_category ON dataset(category_id);
CREATE INDEX idx_dataset_type ON dataset(dataset_type);

-- 칼럼 정의 (dataset에 직접 연결)
CREATE TABLE dataset_column (
    id            BIGSERIAL PRIMARY KEY,
    dataset_id    BIGINT NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    column_name   VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100),
    data_type     VARCHAR(20) NOT NULL
                  CHECK (data_type IN ('TEXT','INTEGER','DECIMAL','BOOLEAN','DATE','TIMESTAMP')),
    is_nullable   BOOLEAN NOT NULL DEFAULT TRUE,
    is_indexed    BOOLEAN NOT NULL DEFAULT FALSE,
    description   VARCHAR(255),
    column_order  INT NOT NULL,
    UNIQUE (dataset_id, column_name)
);
CREATE INDEX idx_dataset_column_dataset ON dataset_column(dataset_id);

-- 임포트 이력
CREATE TABLE data_import (
    id             BIGSERIAL PRIMARY KEY,
    dataset_id     BIGINT NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    file_name      VARCHAR(255) NOT NULL,
    file_size      BIGINT NOT NULL,
    file_type      VARCHAR(10) NOT NULL CHECK (file_type IN ('CSV', 'XLSX')),
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    total_rows     INT,
    success_rows   INT,
    error_rows     INT,
    error_details  JSONB,
    imported_by    BIGINT NOT NULL REFERENCES "user"(id),
    started_at     TIMESTAMP,
    completed_at   TIMESTAMP,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_data_import_dataset ON data_import(dataset_id);

-- 데이터셋/데이터 권한 시딩
INSERT INTO permission (code, description, category) VALUES
    ('dataset:read', '데이터셋 조회', 'dataset'),
    ('dataset:write', '데이터셋 생성/수정', 'dataset'),
    ('dataset:delete', '데이터셋 삭제', 'dataset'),
    ('data:read', '데이터 행 조회', 'data'),
    ('data:import', '데이터 임포트', 'data'),
    ('data:export', '데이터 내보내기', 'data');

-- ADMIN에게 모든 데이터셋/데이터 권한 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.category IN ('dataset', 'data');

-- USER에게 조회 권한 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'USER' AND p.code IN ('dataset:read', 'data:read');
