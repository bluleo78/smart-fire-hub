# Smart Fire Hub — 소방 플랫폼 기반 구축 스프린트

> **기간**: 1~2주 (AI 병렬 작업 기준)
> **목표**: 소방 전문 데이터 플랫폼으로 확장할 수 있는 **기술 기반**을 구축한다
> **범위**: 기반 기술만. 소방 도메인 UI/비즈니스 로직은 다음 스프린트에서.
> **전략**: 기존 범용 플랫폼 기능을 훼손하지 않으면서, 향후 소방 기능의 전제조건이 되는 기술 레이어를 추가한다
> **상위 로드맵**: `.omc/plans/fire-domain-roadmap.md` (장기 비전)
> **연구 자료**: `docs/fire-data-platform-research.md`, `docs/GIS_RESEARCH.md`, `docs/research-realtime-analytics-2026.md`, `docs/data-platform-deep-research.md`

---

## 핵심 원칙

1. **기존 기능 무파괴**: 모든 기존 테스트가 통과해야 함
2. **최소 범위, 최대 확장성**: 지금 구현하는 것은 적지만, 향후 어떤 소방 기능이든 올릴 수 있는 구조
3. **PoC 검증 우선**: jOOQ + PostGIS 통합이 E2E로 동작하는지 먼저 검증
4. **Architect/Critic 피드백 반영**: `fire` 스키마 분리, `organization_id` FK, `incident_type` M:N 설계

---

## 스프린트 구성 (3개 트랙 병렬)

```
Week 1                              Week 2
─────────────────────────────────   ─────────────────────────────────
[Backend Track]                     [Backend Track]
 1A. PostGIS 도입 (0.5d)             1F. 소방 도메인 CRUD API (2d)
 1B. fire 스키마 + 핵심 테이블 (1d)    1G. 시드 데이터 생성기 (0.5d)
 1C. jOOQ PostGIS 바인딩 PoC (1d)     1H. 소방 권한 코드 추가 (0.5d)
 1D. 공간 데이터 API (1.5d)

[Frontend Track]                    [Frontend Track]
 2A. MapLibre 맵 컴포넌트 (1.5d)      2C. 소방 메뉴 & 라우팅 (1d)
 2B. 맵 레이어 시스템 (1.5d)          2D. 기본 목록/상세 UI (2d)

[AI Agent Track]                    [AI Agent Track]
                                     3A. 소방 MCP 도구 4종 (1d)
                                     3B. 시스템 프롬프트 소방 특화 (0.5d)
```

---

## Track 1: Backend — GIS + 소방 스키마 기반

### 1A. PostGIS 도입 (0.5일)

**목표**: PostgreSQL에 공간 데이터 기능 활성화

**변경 사항**:
- `docker-compose.yml`: `postgres:16` → `postgis/postgis:16-3.5` (기존 볼륨 완전 호환)
- Flyway 마이그레이션 `V27__enable_postgis.sql`:
  ```sql
  CREATE EXTENSION IF NOT EXISTS postgis;
  CREATE EXTENSION IF NOT EXISTS postgis_topology;
  ```
- 테스트 DB(`smartfirehub_test`)에도 PostGIS 확장 자동 적용 확인

**수용 기준**:
- `SELECT PostGIS_Version();` 정상 반환
- 기존 전체 테스트 통과 (기존 기능 무파괴)
- TC: PostGIS 확장 활성화 확인 테스트 1개

**참조**: `docs/GIS_RESEARCH.md` Section 2.1 (PostGIS Docker 구성)

---

### 1B. `fire` 스키마 + 핵심 테이블 (1일)

**목표**: 소방 도메인 전용 스키마와 최소 핵심 테이블 생성

**설계 결정** (Architect/Critic 피드백 반영):
- **`fire` 스키마 별도 생성** (Architect #1): `public`(메타데이터)과 분리하여 네임스페이스 명확화, 향후 멀티테넌시 대비
- **모든 테이블에 `organization_id` FK** (Architect #4): 향후 RLS 기반 멀티테넌시 대비
- **`incident_type` M:N 설계** (Architect #3, Critic MUST): NERIS 호환 대비. 단일 VARCHAR 대신 중간 테이블

**Flyway 마이그레이션** `V28__create_fire_schema.sql`:

```sql
-- 소방 도메인 전용 스키마
CREATE SCHEMA IF NOT EXISTS fire;

-- 1. 소방 조직 (계층 구조)
CREATE TABLE fire.organization (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    type            VARCHAR(20) NOT NULL CHECK (type IN ('HQ', 'BUREAU', 'STATION', 'CENTER', 'RESCUE', 'EMS')),
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
    category        VARCHAR(20) NOT NULL CHECK (category IN ('FIRE', 'RESCUE', 'EMS', 'OTHER')),
    description     TEXT
);

-- 사건 유형 시드 데이터
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
    severity        VARCHAR(10) CHECK (severity IN ('LEVEL_1', 'LEVEL_2', 'LEVEL_3', 'CRITICAL')),
    location        GEOMETRY(Point, 4326),
    address         TEXT,
    reported_at     TIMESTAMP NOT NULL,
    dispatched_at   TIMESTAMP,
    closed_at       TIMESTAMP,
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DISPATCHED', 'ON_SCENE', 'CLOSED', 'CANCELLED')),
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
    incident_id     BIGINT NOT NULL REFERENCES fire.incident(id) ON DELETE CASCADE,
    incident_type_id BIGINT NOT NULL REFERENCES fire.incident_type(id),
    is_primary      BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (incident_id, incident_type_id)
);

-- 6. 출동
CREATE TABLE fire.dispatch (
    id              BIGSERIAL PRIMARY KEY,
    incident_id     BIGINT NOT NULL REFERENCES fire.incident(id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES fire.organization(id),
    unit_name       VARCHAR(50) NOT NULL,
    unit_type       VARCHAR(30) CHECK (unit_type IN ('ENGINE', 'LADDER', 'RESCUE', 'EMS', 'HAZMAT', 'COMMAND', 'OTHER')),
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
    type            VARCHAR(20) NOT NULL CHECK (type IN ('ABOVEGROUND', 'UNDERGROUND', 'EMERGENCY', 'NATURAL', 'OTHER')),
    location        GEOMETRY(Point, 4326) NOT NULL,
    address         TEXT,
    capacity_lpm    INT,
    pipe_diameter_mm INT,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'MAINTENANCE', 'UNKNOWN')),
    last_inspected_at TIMESTAMP,
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_hydrant_org ON fire.hydrant(organization_id);
CREATE INDEX idx_hydrant_location ON fire.hydrant USING GIST(location);
CREATE INDEX idx_hydrant_status ON fire.hydrant(status);
```

**스키마 설계 선택 근거**:
- `fire` 스키마 분리: `public`(26개 메타데이터 테이블)과 소방 도메인(7개+) 분리. jOOQ 코드젠에서 별도 패키지로 생성
- `organization_id` FK 필수: 모든 소방 데이터가 조직에 귀속. 향후 RLS 정책으로 멀티테넌시 가능
- `incident_type_mapping` M:N: NERIS의 "복수 유형 사건" 지원. `is_primary`로 주 유형 지정
- `metadata JSONB`: 확장 필드 유연성. 소방서마다 다른 추가 데이터 수용
- EPSG:4326 (WGS84): 국제 표준 좌표계. 한국 좌표계(5179/5186)는 `ST_Transform`으로 변환

**수용 기준**:
- `fire` 스키마와 7개 테이블 생성 확인
- 사건 유형 시드 데이터 12건 INSERT 확인
- 공간 인덱스(GIST) 정상 생성
- 기존 `public` 스키마 테스트 전체 통과 (무파괴)
- TC: 스키마 존재 확인, 기본 CRUD 테스트 5개

---

### 1C. jOOQ PostGIS 바인딩 PoC (1일)

**목표**: jOOQ에서 PostGIS Geometry 타입을 안전하게 읽기/쓰기할 수 있음을 검증

**변경 사항**:
- `build.gradle.kts`: jOOQ 코드젠에 `fire` 스키마 추가 (멀티스키마)
  ```kotlin
  // inputSchema를 배열로 확장
  inputSchema = listOf("public", "fire")
  ```
- `PostgisGeometryBinding.java`: jOOQ 커스텀 바인딩 구현
  - `Geometry` (JTS) ↔ `PGgeometry` 변환
  - GeoJSON 직렬화/역직렬화 유틸
- `PostgisGeometryConverter.java`: `String` (WKT/GeoJSON) ↔ `Geometry` 변환
- `build.gradle.kts`에 `forcedType` 설정: `geometry` 컬럼 → 커스텀 바인딩 적용

**PoC 검증 게이트** (Architect #2):
이 단계 완료 후 다음이 모두 동작해야 다음 단계 진행:
1. `fire.organization`에 좌표(POINT) INSERT + SELECT 왕복
2. `fire.hydrant`에 위치(POINT) INSERT 후 `ST_DWithin` 반경 검색
3. `fire.district`에 경계(POLYGON) INSERT 후 `ST_Contains` 포함 판별
4. GeoJSON ↔ Geometry 변환 왕복 (JSON API 입력 → DB 저장 → JSON API 출력)

**수용 기준**:
- jOOQ 코드젠이 `fire` 스키마 테이블을 정상 생성
- Geometry 타입 INSERT/SELECT 왕복 동작
- ST_DWithin, ST_Contains 공간 쿼리 동작
- GeoJSON 직렬화 왕복 검증
- TC: PoC 검증 4개 시나리오 모두 통과

**참조**: `docs/GIS_RESEARCH.md` Section 2.2 (jOOQ PostGIS 바인딩 코드 예시)

---

### 1D. 공간 데이터 API 기반 (1.5일)

**목표**: 소방 도메인 API에서 공통으로 사용할 공간 데이터 유틸리티와 기반 API

**변경 사항**:
- `global/util/GeoJsonUtil.java`: GeoJSON ↔ JTS Geometry 변환 유틸
- `global/dto/GeoJsonFeature.java`, `GeoJsonFeatureCollection.java`: GeoJSON 표준 DTO
- `global/dto/SpatialQuery.java`: 공간 쿼리 파라미터 DTO (중심점, 반경, 바운딩박스)
- `DataTableService` 확장: `GEOMETRY` 데이터 타입 지원 추가 (기존 `mapDataType()` 확장)

**수용 기준**:
- GeoJSON Feature/FeatureCollection 직렬화/역직렬화 동작
- 공간 쿼리 파라미터 (lat/lng/radius 또는 bbox) 파싱 동작
- TC: GeoJSON 변환 테스트 5개, 공간 쿼리 파라미터 테스트 3개

---

### 1F. 소방 도메인 CRUD API (2일)

**목표**: 핵심 소방 엔티티(조직, 사건, 소방용수)의 기본 CRUD API

**구현 범위** (최소 — 기반만):

#### 소방 조직 API
```
GET    /api/v1/fire/organizations           — 목록 (계층 구조)
GET    /api/v1/fire/organizations/{id}      — 상세 (GeoJSON 좌표 포함)
POST   /api/v1/fire/organizations           — 생성
PUT    /api/v1/fire/organizations/{id}      — 수정
```

#### 사건 API
```
GET    /api/v1/fire/incidents               — 목록 (기간/유형/상태 필터)
GET    /api/v1/fire/incidents/{id}          — 상세 (출동 기록 포함)
POST   /api/v1/fire/incidents               — 생성 (유형 M:N 매핑 포함)
PUT    /api/v1/fire/incidents/{id}          — 수정
```

#### 소방용수시설 API
```
GET    /api/v1/fire/hydrants                — 목록
GET    /api/v1/fire/hydrants/{id}           — 상세
POST   /api/v1/fire/hydrants                — 생성
GET    /api/v1/fire/hydrants/nearby         — 반경 검색 (lat, lng, radius_m)
```

#### 출동 API
```
GET    /api/v1/fire/incidents/{id}/dispatches — 사건별 출동 목록
POST   /api/v1/fire/incidents/{id}/dispatches — 출동 기록 추가
```

**모듈 구조**: 기존 패턴(`controller/service/repository/dto/exception`) 따름
```
com.smartfirehub.fire/
  ├── controller/
  │    ├── FireOrganizationController.java
  │    ├── IncidentController.java
  │    └── HydrantController.java
  ├── service/
  │    ├── FireOrganizationService.java
  │    ├── IncidentService.java
  │    └── HydrantService.java
  ├── repository/
  │    ├── FireOrganizationRepository.java
  │    ├── IncidentRepository.java
  │    └── HydrantRepository.java
  ├── dto/
  │    ├── FireOrganizationDto.java
  │    ├── IncidentDto.java
  │    ├── IncidentCreateRequest.java
  │    ├── HydrantDto.java
  │    └── NearbyHydrantRequest.java
  └── exception/
       └── FireDomainException.java
```

**수용 기준**:
- 조직/사건/소방용수 CRUD API 정상 동작
- 사건 생성 시 복수 유형(M:N) 매핑 동작
- 소방용수 반경 검색(ST_DWithin) 동작
- GeoJSON 응답으로 좌표/경계 데이터 포함
- TC: API별 정상/예외 테스트 최소 20개 (조직 5, 사건 8, 소방용수 5, 출동 2)

---

### 1G. 시드 데이터 생성기 (0.5일)

**목표**: 개발/테스트용 합성 소방 데이터 생성 (Critic MUST)

**생성 데이터**:
- 소방 조직: 1개 소방본부 + 3개 소방서 + 9개 안전센터 (서울 기준 좌표)
- 사건: 최근 1년간 500건 (유형/심각도/시간대 분포 현실적)
- 출동: 사건당 1~3건 출동, 응답시간 분포 반영
- 소방용수: 100개 소화전 (서울 주요 지역 좌표)
- 관할구역: 3개 소방서의 관할 경계 (간단한 polygon)

**구현 방식**:
- Flyway 마이그레이션이 아닌 별도 SQL 스크립트 (`src/main/resources/db/seed/fire-seed-data.sql`)
- 프로파일 기반 로딩: `local` 프로파일에서만 실행
- 또는 REST API: `POST /api/v1/fire/seed` (개발 전용, local 프로파일만)

**수용 기준**:
- 시드 데이터 로딩 후 사건 500건, 소방용수 100건 확인
- 시드 데이터로 공간 쿼리 동작 확인 (반경 검색, 포함 판별)
- Phase 3(대시보드) 시작 시 즉시 사용 가능

---

### 1H. 소방 권한 코드 추가 (0.5일)

**목표**: 소방 도메인 API에 필요한 권한 코드를 기존 RBAC 체계에 추가 (Critic 추가 발견)

**Flyway 마이그레이션** `V29__fire_permissions.sql`:
```sql
INSERT INTO permission (code, description) VALUES
    ('fire_org:read', '소방 조직 조회'),
    ('fire_org:write', '소방 조직 관리'),
    ('fire_incident:read', '사건 조회'),
    ('fire_incident:write', '사건 관리'),
    ('fire_hydrant:read', '소방용수시설 조회'),
    ('fire_hydrant:write', '소방용수시설 관리'),
    ('fire_dashboard:read', '소방 대시보드 조회');

-- ADMIN 역할에 모든 소방 권한 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.code LIKE 'fire_%';

-- USER 역할에 읽기 권한 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'USER' AND p.code LIKE 'fire_%' AND p.code LIKE '%:read';
```

**수용 기준**:
- 권한 코드 7개 추가 확인
- ADMIN: 전체 권한, USER: 읽기 권한
- 소방 API에 `@RequirePermission` 적용 확인

---

## Track 2: Frontend — 맵 컴포넌트 + 소방 UI 기반

### 2A. MapLibre 맵 컴포넌트 (1.5일)

**목표**: 재사용 가능한 맵 컴포넌트. 향후 모든 소방 GIS 페이지의 기반.

**패키지 설치**:
```bash
pnpm --filter firehub-web add maplibre-gl react-map-gl @turf/turf
pnpm --filter firehub-web add -D @types/maplibre-gl
```

**컴포넌트 구현**:
```
apps/firehub-web/src/components/map/
  ├── FireMap.tsx              — 기본 맵 컴포넌트 (react-map-gl 래퍼)
  ├── MapControls.tsx          — 줌, 내비게이션, 전체화면, 현재 위치
  ├── useMapStyle.ts           — V-World + OSM 폴백 스타일 관리
  └── map-styles.ts            — 타일 서버 URL, 기본 스타일 설정
```

**핵심 기능**:
- V-World 배경지도 (한국 최적화) + OpenStreetMap 폴백 (Architect #8)
- 기본 컨트롤: 줌, 내비게이션, 전체화면, GPS 현재 위치
- 한국 중심 기본 뷰 (서울: 37.5665, 126.9780, zoom 11)
- 모바일 반응형 (터치 제스처 기본 지원)

**Vite 청크 분리** (Architect #7):
```typescript
// vite.config.ts — manualChunks 추가
maplibre: ['maplibre-gl', 'react-map-gl'],
```

**맵 페이지 lazy loading**:
```typescript
const FireMapPage = React.lazy(() => import('./pages/fire/FireMapPage'));
```

**수용 기준**:
- MapLibre 맵이 V-World 배경지도와 함께 렌더링
- V-World 장애 시 OSM으로 자동 폴백
- 번들 크기: maplibre 청크가 메인 번들에 미포함 (lazy load)
- 모바일 뷰포트에서 터치 줌/패닝 동작

---

### 2B. 맵 레이어 시스템 (1.5일)

**목표**: 소방 데이터를 맵 위에 표시하는 재사용 가능한 레이어 컴포넌트

**컴포넌트 구현**:
```
apps/firehub-web/src/components/map/
  ├── layers/
  │    ├── PointLayer.tsx       — 포인트 마커 레이어 (소방서, 소화전, 사건)
  │    ├── PolygonLayer.tsx     — 폴리곤 레이어 (관할구역)
  │    └── ClusterLayer.tsx     — 클러스터링 레이어 (대량 포인트)
  ├── MapPopup.tsx              — 마커 클릭 시 팝업
  ├── MapLegend.tsx             — 범례
  └── LayerToggle.tsx           — 레이어 표시/숨김 토글
```

**핵심 기능**:
- GeoJSON 데이터 소스 기반 레이어 렌더링
- 포인트 마커: 아이콘 + 색상 커스터마이징
- 폴리곤: 경계선 + 반투명 채우기
- 클러스터링: 줌 레벨에 따른 자동 클러스터/개별 마커 전환
- 팝업: 마커 클릭 시 엔티티 요약 정보 표시
- 레이어 토글: 사이드바에서 레이어별 표시/숨김

**수용 기준**:
- GeoJSON 데이터로 포인트/폴리곤 렌더링 동작
- 마커 클릭 시 팝업 표시
- 100+ 포인트에서 클러스터링 동작
- 레이어 토글로 표시/숨김 전환

---

### 2C. 소방 메뉴 & 라우팅 (1일)

**목표**: LNB(Left Navigation Bar)에 소방 도메인 메뉴 추가, 라우팅 구성

**변경 사항**:
- LNB에 "소방" 메뉴 그룹 추가 (아이콘: Flame 또는 Shield)
  - 소방 맵 (`/fire/map`)
  - 사건 관리 (`/fire/incidents`)
  - 소방용수 (`/fire/hydrants`)
  - 소방 조직 (`/fire/organizations`)
- React Router 라우트 추가 (lazy loading)
- 기존 범용 기능(데이터셋, 파이프라인 등) 메뉴 유지

**수용 기준**:
- LNB에 소방 메뉴 그룹 표시
- 각 라우트로 네비게이션 동작
- lazy loading으로 초기 번들 미포함

---

### 2D. 기본 목록/상세 UI (2일)

**목표**: 소방 엔티티의 최소 CRUD UI (맵 통합 포함)

**페이지 구현**:
```
apps/firehub-web/src/pages/fire/
  ├── FireMapPage.tsx           — 통합 맵 뷰 (조직/소화전/사건 레이어)
  ├── IncidentListPage.tsx      — 사건 목록 (테이블 + 필터)
  ├── IncidentDetailPage.tsx    — 사건 상세 (출동 타임라인)
  ├── HydrantListPage.tsx       — 소방용수 목록
  └── OrganizationPage.tsx      — 소방 조직 트리뷰
```

**핵심 기능**:
- 사건 목록: 기존 데이터셋 목록 UI 패턴 재활용 (DataTable, 필터, 페이지네이션)
- 사건 상세: 기본 정보 + 유형 태그 + 출동 기록 타임라인
- 소방 맵: 소방서/소화전/최근 사건을 레이어로 표시
- 소방용수: 목록 뷰 + 맵 뷰 토글

**수용 기준**:
- 시드 데이터가 목록/맵에 정상 표시
- 사건 목록 필터링 (기간, 유형, 상태) 동작
- 사건 상세에서 출동 기록 표시
- 소방 맵에 3개 레이어(조직/소화전/사건) 동시 표시

---

## Track 3: AI Agent — 소방 MCP 도구

### 3A. 소방 MCP 도구 4종 (1일)

**목표**: AI 에이전트가 소방 데이터에 접근할 수 있는 최소 도구

**MCP 도구**:

| 도구 | 설명 | API 호출 |
|------|------|----------|
| `list_fire_incidents` | 사건 목록 조회 (기간/유형/상태 필터) | `GET /api/v1/fire/incidents` |
| `get_fire_incident` | 사건 상세 조회 (출동 기록 포함) | `GET /api/v1/fire/incidents/{id}` |
| `search_nearby_hydrants` | 특정 위치 반경 내 소화전 검색 | `GET /api/v1/fire/hydrants/nearby` |
| `list_fire_organizations` | 소방 조직 계층 조회 | `GET /api/v1/fire/organizations` |

**구현**: 기존 `firehub-mcp-server.ts`에 `safeTool()` 래퍼로 등록, `api-client.ts`에 대응 메서드 추가

**수용 기준**:
- AI에게 "최근 화재 사건 보여줘" → `list_fire_incidents` 호출 동작
- AI에게 "강남역 근처 소화전 찾아줘" → `search_nearby_hydrants` 호출 동작
- TC: MCP 도구 테스트 4개

---

### 3B. 시스템 프롬프트 소방 특화 (0.5일)

**목표**: AI 에이전트가 소방 도메인을 이해하고 적절히 도구를 사용하도록 시스템 프롬프트 강화

**변경**: `src/agent/system-prompt.ts`에 소방 도메인 컨텍스트 추가:
- 소방 데이터 구조 설명 (조직 계층, 사건 유형, 출동 단계)
- 소방 KPI 정의 (응답시간 = 신고→도착, 출동준비시간 = 지령→출발)
- 소방 MCP 도구 사용 가이드
- 한국 소방 용어 사전 (소방서, 안전센터, 소방용수, 출동 등)

**수용 기준**:
- AI가 "응답시간"이란 질문에 정확한 정의로 답변
- AI가 소방 도구를 적절한 상황에서 자동 선택
- TC: 시스템 프롬프트 통합 테스트 2개

---

## Architect/Critic 피드백 반영 요약

| # | 피드백 | 반영 | 상세 |
|---|--------|------|------|
| A1 | `fire` 스키마 별도 생성 | **수락** | 1B에서 `CREATE SCHEMA fire` |
| A2 | jOOQ + PostGIS PoC 게이트 | **수락** | 1C에 4개 검증 시나리오 명시 |
| A3 | `incident_type` M:N 설계 | **수락** | 1B에서 `incident_type_mapping` 중간 테이블 |
| A4 | 모든 테이블에 `organization_id` FK | **수락** | 1B 모든 테이블에 FK 포함 |
| A5 | 시드 데이터 생성기 | **수락** | 1G 별도 항목 |
| A6 | Phase 2 분리 | **수락** | 이 스프린트에서는 핵심 4개 테이블만 (조직/사건/소화전/출동) |
| A7 | Vite 청크 분리 | **수락** | 2A에서 maplibre 청크 분리 + lazy loading |
| A8 | V-World 폴백 | **수락** | 2A에서 OSM 폴백 구현 |
| A9 | `data` → `fire` ETL 템플릿 | **연기** | 다음 스프린트. 현재는 `fire` 스키마 직접 API만 |
| A10 | SqlScriptExecutor 확장 | **연기** | 파이프라인 → `fire` 스키마 연동은 다음 스프린트 |
| C1 | Phase 6 수용 기준 정량화 | **해당 없음** | 이 스프린트 범위 밖 |
| C2 | antithesis 대응 | **수락** | 아래 "전략적 안전장치" 섹션 |
| C3 | 팀 리소스 명시 | **수락** | 3개 트랙 병렬 (Backend/Frontend/AI Agent) |
| C4 | 권한 코드 | **수락** | 1H 별도 항목 |

---

## 전략적 안전장치 (Architect antithesis 대응)

**"소방 전문화가 실패하면?"에 대한 답**:

1. **기존 범용 기능 무파괴**: 소방 기능은 별도 `fire` 스키마 + 별도 API 경로(`/api/v1/fire/*`)에 격리. 기존 데이터셋/파이프라인/AI 기능에 전혀 영향 없음
2. **롤백 비용 최소**: 소방 기능을 제거해야 할 경우, `DROP SCHEMA fire CASCADE` + 프론트엔드 라우트 제거만으로 완전 복원
3. **범용 기능 강화도 포함**: PostGIS, MapLibre 맵 컴포넌트, GeoJSON API는 소방에 국한되지 않는 범용 GIS 기능. 향후 환경/교통/방재 등 다른 도메인에도 활용 가능
4. **TAM 인정**: 한국 소방서 230개, 119안전센터 800개는 제한적이나, 소방본부(18개 시도) + 소방청이라는 의사결정 단위가 존재하며, B2G 시장의 단가가 높음

---

## 리스크 & 완화

| 리스크 | 영향 | 완화 |
|--------|------|------|
| jOOQ + PostGIS 통합 실패 | 전체 GIS 계획 차질 | 1C의 PoC 게이트로 조기 발견. 실패 시 raw SQL 폴백 |
| V-World 타일 서버 장애 | 맵 렌더링 불가 | OSM 폴백 구현 (2A) |
| `fire` 스키마 jOOQ 코드젠 | 멀티스키마 설정 복잡 | 기존 `public` 설정 참조. 실패 시 `public`에 테이블 추가로 폴백 |
| 소방 도메인 모델 부정확 | 향후 마이그레이션 필요 | JSONB metadata 필드로 유연성 확보. 시드 데이터로 검증 |
| 번들 크기 증가 | 초기 로딩 속도 저하 | 맵 청크 분리 + lazy loading (2A) |

---

## 수용 기준 총괄

| # | 기준 | 검증 방법 |
|---|------|-----------|
| 1 | 기존 전체 테스트 통과 | `pnpm test` (Backend + AI Agent) |
| 2 | PostGIS 공간 쿼리 동작 | TC: ST_DWithin, ST_Contains 검증 |
| 3 | jOOQ + PostGIS 왕복 | TC: Geometry INSERT → SELECT → GeoJSON 직렬화 |
| 4 | 소방 CRUD API 동작 | TC: 조직/사건/소방용수/출동 API 20개+ |
| 5 | MapLibre 맵 렌더링 | 수동: V-World 배경지도 + 마커 표시 |
| 6 | 소방 데이터 맵 표시 | 수동: 시드 데이터가 맵에 레이어로 표시 |
| 7 | AI 소방 도구 동작 | TC: 4개 MCP 도구 테스트 |
| 8 | 번들 크기 관리 | 빌드: maplibre 청크 분리 확인 |

**전체 TC 목표**: 최소 45개 (Backend 30개 + AI Agent 6개 + 기존 테스트 전체 통과)

---

## 다음 스프린트 예고

이 기반 스프린트 완료 후, 다음 스프린트에서 진행할 수 있는 항목:
- 소방 KPI 대시보드 (shadcn/ui Charts)
- 사건 분석/통계 API
- 공공데이터 연동 (소방용수시설, 소방서 좌표)
- AI Text-to-SQL 소방 특화
- 장비/인력 도메인 확장
- `data` → `fire` 스키마 ETL 템플릿
