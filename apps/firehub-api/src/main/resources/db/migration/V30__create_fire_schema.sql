-- 소방 도메인 전용 스키마
CREATE SCHEMA IF NOT EXISTS fire;

-- 1. 소방 조직 (계층 구조: 소방청 > 소방본부 > 소방서 > 안전센터)
CREATE TABLE fire.organization (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    type            VARCHAR(20) NOT NULL
                    CHECK (type IN ('HQ','BUREAU','STATION','CENTER','RESCUE','EMS')),
    parent_id       BIGINT REFERENCES fire.organization(id),
    address         TEXT,
    coordinates     GEOMETRY(Point, 4326),
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_org_parent ON fire.organization(parent_id);
CREATE INDEX idx_org_coordinates ON fire.organization USING GIST(coordinates);

-- 2. 관할구역
CREATE TABLE fire.district (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES fire.organization(id),
    name            VARCHAR(100) NOT NULL,
    boundary        GEOMETRY(MultiPolygon, 4326),
    area_km2        NUMERIC(10, 2),
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_district_org ON fire.district(organization_id);
CREATE INDEX idx_district_boundary ON fire.district USING GIST(boundary);

-- 3. 사건 유형 코드 테이블
CREATE TABLE fire.incident_type (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(20) NOT NULL UNIQUE,
    name            VARCHAR(50) NOT NULL,
    category        VARCHAR(20) NOT NULL
                    CHECK (category IN ('FIRE','RESCUE','EMS','OTHER')),
    description     TEXT
);

-- 사건 유형 시드 데이터 (12건)
INSERT INTO fire.incident_type (code, name, category) VALUES
    ('FIRE_BUILDING', '건물화재', 'FIRE'),
    ('FIRE_VEHICLE', '차량화재', 'FIRE'),
    ('FIRE_WILDLAND', '산불', 'FIRE'),
    ('FIRE_OTHER', '기타화재', 'FIRE'),
    ('RESCUE_TRAFFIC', '교통사고구조', 'RESCUE'),
    ('RESCUE_WATER', '수난구조', 'RESCUE'),
    ('RESCUE_MOUNTAIN', '산악구조', 'RESCUE'),
    ('RESCUE_OTHER', '기타구조', 'RESCUE'),
    ('EMS_EMERGENCY', '응급', 'EMS'),
    ('EMS_TRANSFER', '이송', 'EMS'),
    ('OTHER_HAZMAT', '위험물', 'OTHER'),
    ('OTHER_SUPPORT', '지원', 'OTHER');

-- 4. 사건
CREATE TABLE fire.incident (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES fire.organization(id),
    incident_number VARCHAR(30) NOT NULL,
    severity        VARCHAR(10)
                    CHECK (severity IN ('LEVEL_1','LEVEL_2','LEVEL_3','CRITICAL')),
    location        GEOMETRY(Point, 4326),
    address         TEXT,
    reported_at     TIMESTAMP NOT NULL,
    dispatched_at   TIMESTAMP,
    closed_at       TIMESTAMP,
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN'
                    CHECK (status IN ('OPEN','DISPATCHED','ON_SCENE','CLOSED','CANCELLED')),
    cause           TEXT,
    damage_area_m2  NUMERIC(12, 2),
    casualties      INT DEFAULT 0,
    property_damage_krw BIGINT DEFAULT 0,
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(organization_id, incident_number)
);
CREATE INDEX idx_incident_org ON fire.incident(organization_id);
CREATE INDEX idx_incident_reported ON fire.incident(reported_at);
CREATE INDEX idx_incident_status ON fire.incident(status);
CREATE INDEX idx_incident_location ON fire.incident USING GIST(location);

-- 5. 사건-유형 M:N 매핑 (NERIS 호환: 하나의 사건에 복수 유형)
CREATE TABLE fire.incident_type_mapping (
    incident_id      BIGINT NOT NULL REFERENCES fire.incident(id) ON DELETE CASCADE,
    incident_type_id BIGINT NOT NULL REFERENCES fire.incident_type(id),
    is_primary       BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (incident_id, incident_type_id)
);

-- 6. 출동
CREATE TABLE fire.dispatch (
    id              BIGSERIAL PRIMARY KEY,
    incident_id     BIGINT NOT NULL REFERENCES fire.incident(id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES fire.organization(id),
    unit_name       VARCHAR(50) NOT NULL,
    unit_type       VARCHAR(30)
                    CHECK (unit_type IN ('ENGINE','LADDER','RESCUE','EMS','HAZMAT','COMMAND','OTHER')),
    dispatched_at   TIMESTAMP,
    en_route_at     TIMESTAMP,
    arrived_at      TIMESTAMP,
    cleared_at      TIMESTAMP,
    personnel_count INT,
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_dispatch_incident ON fire.dispatch(incident_id);
CREATE INDEX idx_dispatch_org ON fire.dispatch(organization_id);

-- 7. 소방용수시설
CREATE TABLE fire.hydrant (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES fire.organization(id),
    type            VARCHAR(20) NOT NULL
                    CHECK (type IN ('ABOVEGROUND','UNDERGROUND','EMERGENCY','NATURAL','OTHER')),
    location        GEOMETRY(Point, 4326) NOT NULL,
    address         TEXT,
    capacity_lpm    INT,
    pipe_diameter_mm INT,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','INACTIVE','MAINTENANCE','UNKNOWN')),
    last_inspected_at TIMESTAMP,
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_hydrant_org ON fire.hydrant(organization_id);
CREATE INDEX idx_hydrant_location ON fire.hydrant USING GIST(location);
CREATE INDEX idx_hydrant_status ON fire.hydrant(status);
