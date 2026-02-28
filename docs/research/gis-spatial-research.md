# Smart Fire Hub — GIS/공간정보 기능 심층 리서치 보고서

> 작성일: 2026-02-28
> 대상 프로젝트: Smart Fire Hub (React 19 + Spring Boot 3.4 + PostgreSQL 16 + jOOQ)

---

## 목차

1. [프론트엔드 매핑 라이브러리 비교](#1-프론트엔드-매핑-라이브러리-비교)
2. [백엔드 공간정보 기술](#2-백엔드-공간정보-기술)
3. [소방 서비스 GIS 활용 사례](#3-소방-서비스-gis-활용-사례)
4. [한국 GIS 인프라](#4-한국-gis-인프라)
5. [아키텍처 권장사항](#5-아키텍처-권장사항-smart-fire-hub-통합)

---

## 1. 프론트엔드 매핑 라이브러리 비교

### 1.1 라이브러리 종합 비교표

| 항목 | MapLibre GL JS | Leaflet / React-Leaflet | deck.gl | Mapbox GL JS | OpenLayers | Google Maps | Kepler.gl | CesiumJS |
|------|---------------|------------------------|---------|-------------|-----------|-------------|-----------|----------|
| **최신 버전** | v5.8.0 (2025.09) | Leaflet 2.0-alpha / React-Leaflet 5.0.0 | v9.x (2025) | v3.x | v10.x | — | 3.x (2025.01) | v1.12x+ |
| **라이선스** | BSD-3-Clause (완전 무료) | BSD-2-Clause (완전 무료) | MIT (완전 무료) | 독점 라이선스 (v2+) | BSD-2-Clause (완전 무료) | 유료 (사용량 기반) | MIT (완전 무료) | Apache 2.0 |
| **비용** | 무료 (타일 서버 별도) | 무료 (타일 서버 별도) | 무료 | 50,000 로드/월 무료, 이후 과금 | 무료 (타일 서버 별도) | 10,000 이벤트/월 무료 (2025.03~) | 무료 (Mapbox 토큰 필요) | 무료 (Cesium Ion 유료) |
| **렌더링 방식** | WebGL 벡터 | Canvas/SVG 래스터 | WebGL/WebGPU GPU | WebGL 벡터 | Canvas/WebGL 혼합 | 독점 렌더러 | WebGL (deck.gl 기반) | WebGL/WebGPU 3D |
| **React 통합** | react-map-gl v8 (우수) | react-leaflet v5 (우수) | @deck.gl/react (우수) | react-map-gl v8 (우수) | rlayers, react-openlayers-fiber (보통) | @vis.gl/react-google-maps (보통) | React 컴포넌트 (Redux 필수) | resium (보통) |
| **TypeScript** | 완전 지원 | 완전 지원 (v5+) | 완전 지원 | 완전 지원 | 완전 지원 | 완전 지원 | 부분 지원 | 완전 지원 |
| **대용량 데이터 (10K+)** | 우수 (벡터 타일) | 보통 (플러그인 필요) | 최우수 (GPU 렌더링) | 우수 (벡터 타일) | 우수 | 보통 | 최우수 (수백만 포인트) | 우수 (3D Tiles) |
| **히트맵** | 내장 지원 | 플러그인 (leaflet.heat) | HeatmapLayer 내장 | 내장 지원 | 내장 지원 | 내장 지원 | 내장 지원 | 커스텀 필요 |
| **클러스터링** | 내장 지원 (supercluster) | 플러그인 (markercluster) | ClusterTileLayer 내장 | 내장 지원 | 내장 지원 | 내장 지원 | 내장 지원 | 커스텀 필요 |
| **폴리곤/도형** | 완전 지원 | 완전 지원 | 완전 지원 (홀 포함) | 완전 지원 | 완전 지원 (가장 강력) | 완전 지원 | 완전 지원 | 완전 지원 |
| **커스텀 레이어** | 완전 지원 | 플러그인 기반 | 완전 지원 (서브클래싱) | 완전 지원 | 완전 지원 | 제한적 | deck.gl 레이어 | 완전 지원 |
| **오프라인** | 가능 (타일 캐싱) | leaflet.offline 플러그인 | 불가 (데이터 의존) | 가능 (타일 캐싱) | 가능 | 불가 | 불가 | 가능 (타일 캐싱) |
| **모바일 반응형** | 우수 | 최우수 (경량) | 보통 (GPU 의존) | 우수 | 보통 | 우수 | 보통 (데스크톱 최적화) | 보통 (무거움) |
| **3D 지원** | 제한적 (2.5D 건물) | 불가 | 3D 레이어 지원 | 제한적 (2.5D) | 제한적 | 제한적 | 2.5D | 최우수 (풀 3D 글로브) |
| **커뮤니티** | 성장 중 (2024~ 급성장) | 최대 (월 140만 다운로드) | 활발 (Uber/vis.gl) | 대규모 (상업) | 대규모 (OSGeo) | 최대 (Google) | 보통 (11K GitHub 스타) | 보통 (Cesium 커뮤니티) |
| **npm 월 다운로드** | ~300K+ (급성장) | ~1.4M | ~200K+ | ~800K | ~200K+ | — | ~30K | ~50K |

### 1.2 라이브러리 상세 분석

#### MapLibre GL JS — 권장 1순위

```
라이선스: BSD-3-Clause (완전 오픈소스)
최신 버전: v5.8.0 (2025년 9월)
React 래퍼: react-map-gl v8 (@visgl/react-map-gl/maplibre)
```

**핵심 장점:**
- Mapbox GL JS v1의 완전한 오픈소스 포크로, 독점 라이선스 없이 벡터 맵 렌더링 가능
- 2024년 중반부터 다운로드 수가 급성장하며 Mapbox의 대안으로 자리매김
- react-map-gl v8에서 전용 엔드포인트 제공 (`react-map-gl/maplibre`), ESM 빌드 크기 219K -> 57K로 경량화
- deck.gl과의 완벽한 통합 (MapboxOverlay 어댑터로 MapLibre v5 글로브 뷰 지원)
- V-World, OpenStreetMap 등 다양한 타일 소스 지원
- 최근 릴리스에서 data-driven styling, globe projection, MLT 인코딩 등 기능 추가

**소방 플랫폼 적합성:**
- 무료로 무제한 사용 가능 (공공기관 예산 절감)
- 한국 V-World WMS/WFS 타일과 직접 연동 가능
- 벡터 타일 기반으로 관할구역 경계, 소방서 위치 등 대용량 폴리곤 렌더링에 우수
- deck.gl 레이어를 오버레이하여 히트맵, 클러스터링 강화 가능

```typescript
// React 19 + MapLibre 통합 예시
import Map from 'react-map-gl/maplibre';
import 'maplibre-gl/dist/maplibre-gl.css';

function FireStationMap() {
  return (
    <Map
      initialViewState={{
        longitude: 126.978,
        latitude: 37.5665,
        zoom: 11
      }}
      style={{ width: '100%', height: '100%' }}
      mapStyle="https://api.vworld.kr/req/wmts/..." // V-World 타일
    />
  );
}
```

#### deck.gl — 대용량 데이터 시각화 레이어

```
라이선스: MIT (완전 오픈소스)
최신 버전: v9.x (2025)
React 통합: @deck.gl/react
```

**핵심 장점:**
- WebGL2/WebGPU 기반 GPU 렌더링으로 수백만 포인트 처리 가능
- 64비트 부동소수점 에뮬레이션으로 초정밀 좌표 렌더링
- React 상태 변경 시 변경된 WebGL 속성만 갱신 (React reconciliation과 유사)
- HeatmapLayer, ScatterplotLayer, GeoJsonLayer, H3HexagonLayer 등 50+ 내장 레이어
- MapLibre GL JS와 자연스러운 오버레이 통합

**소방 플랫폼 활용:**
- 화재 발생 히트맵 (수만 건의 출동 이력 시각화)
- 소방용수시설 클러스터링 (전국 수만 개 시설)
- H3 기반 화재 위험도 헥사곤 맵
- 출동 경로 애니메이션 (TripLayer)

```typescript
// deck.gl + MapLibre 통합 예시
import { DeckGL } from '@deck.gl/react';
import { HeatmapLayer, ScatterplotLayer } from '@deck.gl/aggregation-layers';
import Map from 'react-map-gl/maplibre';

function IncidentHeatmap({ incidents }: { incidents: Incident[] }) {
  const layers = [
    new HeatmapLayer({
      id: 'incident-heatmap',
      data: incidents,
      getPosition: d => [d.longitude, d.latitude],
      getWeight: d => d.severity,
      radiusPixels: 60,
    }),
  ];

  return (
    <DeckGL layers={layers} initialViewState={INITIAL_VIEW}>
      <Map mapStyle={VWORLD_STYLE} />
    </DeckGL>
  );
}
```

#### Leaflet / React-Leaflet — 경량 대안

```
라이선스: BSD-2-Clause (완전 오픈소스)
최신 버전: React-Leaflet 5.0.0 (React 19 지원)
Leaflet: 2.0.0-alpha.1 (2025년 8월)
```

**핵심 장점:**
- 월 140만 다운로드로 가장 큰 생태계와 커뮤니티
- 모바일 친화적으로 가장 경량
- leaflet.offline 플러그인으로 오프라인 맵 지원 (PWA 활용 가능)
- 학습 곡선이 가장 낮음

**한계:**
- Canvas/SVG 기반 래스터 렌더링으로 대용량 데이터(50K+) 시 성능 저하
- 벡터 타일 네이티브 미지원 (플러그인 필요)
- 3D 지원 없음

**적합 시나리오:** 소방차 위치 추적용 경량 임베디드 맵, 모바일 현장 앱

#### OpenLayers — 풀 GIS 기능

```
라이선스: BSD-2-Clause (완전 오픈소스)
최신 버전: v10.x
React 래퍼: rlayers (React 18+), react-openlayers-fiber
```

**핵심 장점:**
- GIS 전문 기능 최강 (좌표계 변환, WMS/WFS/WMTS, 프로젝션 등)
- 한국 좌표계(EPSG:5179, 5186) 네이티브 지원
- OGC 표준 완전 준수

**한계:**
- React 통합이 MapLibre 대비 미숙
- 학습 곡선이 높음
- 번들 크기가 상대적으로 큼

**적합 시나리오:** GIS 전문가용 분석 도구, OGC 표준 데이터 소비

#### Google Maps Platform

```
라이선스: 독점 (유료)
비용: 2025년 3월부터 SKU별 무료 사용량 제한 (Essentials: 10,000 이벤트/월)
```

**핵심 장점:**
- 길찾기(Routes API), 지오코딩 등 부가 서비스 풍부
- 한국 지도 데이터 품질 우수
- Street View 지원

**한계:**
- 사용량 기반 과금 (공공기관 예산 제약)
- 커스터마이징 제한적
- 벡터 타일 직접 접근 불가

#### Kepler.gl — 데이터 분석 특화

```
라이선스: MIT (무료, 단 Mapbox 토큰 필요)
최신 릴리스: 2025년 1월 29일
기반: MapLibre GL + deck.gl + Redux
```

**핵심 장점:**
- 데이터 분석가 친화적 UI (드래그 앤 드롭 시각화)
- 수백만 포인트 렌더링 (deck.gl 기반)
- 시계열 필터, 3D 헥사곤 등 고급 시각화

**한계:**
- Redux 의존성 (기존 앱 상태 관리와 충돌 가능)
- 커스터마이징 제한적 (분석 도구 위젯으로 활용)
- React 앱 내 임베딩 시 번들 크기 큼

**적합 시나리오:** 화재 데이터 탐색적 분석 전용 페이지

#### CesiumJS — 3D 글로브

```
라이선스: Apache 2.0 (오픈소스, Cesium Ion은 유료)
React 래퍼: resium
2025년: WebGPU 렌더러 브랜치, 2-4x 성능 향상
```

**핵심 장점:**
- 풀 3D 글로브 시각화 (지구본 기반)
- 3D Tiles로 건물 모델, 지형 데이터 처리
- 항공/위성 이미지 레이어링

**한계:**
- 매우 무거움 (번들 크기, 초기 로딩)
- 2D 맵 유스케이스에는 과도한 사양

**적합 시나리오:** 대규모 산불 시뮬레이션, 3D 건물 사전조사

### 1.3 권장 조합 (Smart Fire Hub)

```
┌─────────────────────────────────────────────────┐
│              프론트엔드 매핑 스택                   │
│                                                   │
│  ┌──────────────┐   ┌──────────────┐             │
│  │ MapLibre GL  │ + │   deck.gl    │  (메인 맵)   │
│  │   JS v5      │   │    v9        │             │
│  └──────┬───────┘   └──────┬───────┘             │
│         │                   │                     │
│         └───────┬───────────┘                     │
│                 │                                 │
│    ┌────────────┴─────────────┐                   │
│    │  react-map-gl v8         │                   │
│    │  (@visgl/react-map-gl)   │                   │
│    └──────────────────────────┘                   │
│                                                   │
│  ┌──────────────┐                                │
│  │  Turf.js     │  (클라이언트 공간 분석)         │
│  └──────────────┘                                │
│                                                   │
│  ┌──────────────┐                                │
│  │  Kepler.gl   │  (데이터 분석 전용 페이지)      │
│  │  (선택사항)   │                                │
│  └──────────────┘                                │
└─────────────────────────────────────────────────┘
```

**설치 명령:**
```bash
cd apps/firehub-web
pnpm add maplibre-gl @visgl/react-map-gl @deck.gl/core @deck.gl/layers @deck.gl/aggregation-layers @deck.gl/geo-layers @deck.gl/react @turf/turf
pnpm add -D @types/maplibre-gl
```

---

## 2. 백엔드 공간정보 기술

### 2.1 PostGIS — PostgreSQL 공간 확장

#### 개요

PostGIS는 PostgreSQL용 공간 데이터 확장으로, 지리적 객체(포인트, 라인, 폴리곤)를 저장하고 공간 쿼리를 실행할 수 있게 해준다. Smart Fire Hub가 이미 PostgreSQL 16을 사용하므로, PostGIS 확장만 활성화하면 된다.

#### 핵심 기능

| 기능 | 설명 | 소방 활용 예시 |
|------|------|--------------|
| `geometry` 타입 | 2D/3D 좌표 저장 | 소방서 위치(Point), 관할구역 경계(Polygon) |
| `geography` 타입 | 지구 곡률 고려 거리 계산 | 소방서~화재 현장 실거리 계산 |
| 공간 인덱스 (GiST) | R-tree 기반 공간 인덱스 | 반경 내 소방용수시설 고속 검색 |
| ST_Distance | 두 지점 간 거리 | 출동 거리 계산 |
| ST_Within | 포인트가 폴리곤 내에 있는지 | 건물이 어떤 관할구역에 속하는지 |
| ST_Buffer | 포인트 주변 버퍼 영역 생성 | 소방서 반경 5km 커버리지 |
| ST_Intersects | 두 도형의 교차 여부 | 관할구역 경계 중복 분석 |
| ST_Contains | 포함 여부 | 관할 내 건물 목록 |
| ST_DWithin | 거리 내 포함 여부 | 반경 300m 내 소방용수시설 |
| ST_ConvexHull | 볼록 껍질 | 화재 발생 밀집 지역 외곽선 |
| ST_Union | 도형 합치기 | 인접 관할구역 병합 |
| ST_Centroid | 무게 중심 | 관할구역 중심점 계산 |
| ST_Area | 면적 계산 | 관할구역 면적 |
| ST_Transform | 좌표계 변환 | EPSG:5179 -> WGS84 변환 |

#### Docker 구성 변경

현재 `docker-compose.yml`에서 `postgres:16` 이미지를 `postgis/postgis:16-3.5` 로 교체:

```yaml
# docker-compose.yml 변경
services:
  db:
    image: postgis/postgis:16-3.5  # postgres:16 → postgis/postgis:16-3.5
    environment:
      POSTGRES_DB: smartfirehub
      POSTGRES_USER: app
      POSTGRES_PASSWORD: app
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U app -d smartfirehub"]
      interval: 5s
      timeout: 5s
      retries: 5
```

`postgis/postgis:16-3.5` 이미지는 PostgreSQL 16 위에 PostGIS 3.5.x가 사전 설치되어 있으며, 기본 데이터베이스 생성 시 PostGIS 확장이 자동으로 설치된다. Alpine 변형(`postgis/postgis:16-3.5-alpine`)도 사용 가능하다.

#### Flyway 마이그레이션

```sql
-- V27__enable_postgis.sql
-- PostGIS 확장 활성화
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- H3 확장 (선택사항, 헥사곤 공간 인덱싱)
-- CREATE EXTENSION IF NOT EXISTS h3;

-- 공간 참조 시스템 확인 (한국 좌표계)
-- EPSG:4326 (WGS84) — 기본
-- EPSG:5179 (Korea 2000 Unified CS) — 국가 통합좌표계
-- EPSG:5186 (Korea 2000 Central Belt 2010) — 중부원점
```

```sql
-- V28__create_spatial_tables.sql
-- 소방서 위치 테이블
CREATE TABLE IF NOT EXISTS fire_station (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    address TEXT,
    headquarters VARCHAR(50),        -- 시도 본부명
    district VARCHAR(50),            -- 소방서명
    phone VARCHAR(20),
    location GEOMETRY(Point, 4326),  -- WGS84 좌표
    coverage_area GEOMETRY(Polygon, 4326), -- 관할구역
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_fire_station_location ON fire_station USING GIST(location);
CREATE INDEX idx_fire_station_coverage ON fire_station USING GIST(coverage_area);

-- 소방용수시설 테이블
CREATE TABLE IF NOT EXISTS fire_hydrant (
    id BIGSERIAL PRIMARY KEY,
    type VARCHAR(30) NOT NULL,       -- 지상식, 지하식, 비상소화장치 등
    address TEXT,
    water_supply_type VARCHAR(30),   -- 상수도, 저수조, 급수탑 등
    capacity_tons NUMERIC(10,2),     -- 저수량(톤)
    pipe_diameter_mm INTEGER,        -- 관경(mm)
    flow_rate_lpm NUMERIC(10,2),     -- 유량(LPM)
    status VARCHAR(20) DEFAULT 'ACTIVE',
    location GEOMETRY(Point, 4326),
    last_inspection_date DATE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_fire_hydrant_location ON fire_hydrant USING GIST(location);

-- 화재 사건 테이블
CREATE TABLE IF NOT EXISTS fire_incident (
    id BIGSERIAL PRIMARY KEY,
    incident_number VARCHAR(50),
    incident_date TIMESTAMP NOT NULL,
    incident_type VARCHAR(50),       -- 건물화재, 차량화재, 산불 등
    severity VARCHAR(20),            -- 대응1단계 ~ 3단계
    casualties INTEGER DEFAULT 0,
    property_damage_krw BIGINT DEFAULT 0,
    cause VARCHAR(100),
    building_type VARCHAR(50),
    response_time_seconds INTEGER,   -- 출동~현장 도착 시간
    location GEOMETRY(Point, 4326),
    station_id BIGINT REFERENCES fire_station(id),
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_fire_incident_location ON fire_incident USING GIST(location);
CREATE INDEX idx_fire_incident_date ON fire_incident(incident_date);

-- 관할구역 경계 테이블
CREATE TABLE IF NOT EXISTS fire_district (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    district_code VARCHAR(20),
    station_id BIGINT REFERENCES fire_station(id),
    boundary GEOMETRY(MultiPolygon, 4326),
    area_sqkm NUMERIC(10,4),
    population INTEGER,
    risk_score NUMERIC(5,2),         -- 화재 위험 점수 (0-100)
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_fire_district_boundary ON fire_district USING GIST(boundary);
```

#### 주요 공간 쿼리 예시

```sql
-- 1. 특정 좌표에서 반경 3km 내 소방용수시설 검색
SELECT id, type, capacity_tons,
       ST_Distance(location::geography, ST_SetSRID(ST_MakePoint(126.978, 37.566), 4326)::geography) AS distance_m
FROM fire_hydrant
WHERE ST_DWithin(
    location::geography,
    ST_SetSRID(ST_MakePoint(126.978, 37.566), 4326)::geography,
    3000  -- 3000m = 3km
)
ORDER BY distance_m;

-- 2. 특정 건물이 어떤 관할구역에 속하는지
SELECT fd.name, fd.district_code, fs.name AS station_name
FROM fire_district fd
JOIN fire_station fs ON fd.station_id = fs.id
WHERE ST_Contains(fd.boundary, ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326));

-- 3. 관할구역별 화재 건수 통계
SELECT fd.name, COUNT(fi.id) AS incident_count,
       AVG(fi.response_time_seconds) AS avg_response_time
FROM fire_district fd
LEFT JOIN fire_incident fi ON ST_Contains(fd.boundary, fi.location)
WHERE fi.incident_date >= '2025-01-01'
GROUP BY fd.name
ORDER BY incident_count DESC;

-- 4. 소방서 커버리지 갭 분석 (소방용수시설이 반경 300m 내 없는 지역)
SELECT fi.id, fi.name, fi.address
FROM fire_station fi
WHERE NOT EXISTS (
    SELECT 1 FROM fire_hydrant fh
    WHERE ST_DWithin(fi.location::geography, fh.location::geography, 300)
);

-- 5. 좌표계 변환 (EPSG:5179 → WGS84)
SELECT ST_AsGeoJSON(ST_Transform(ST_SetSRID(ST_MakePoint(953898, 1952025), 5179), 4326));

-- 6. 이소크론(도달 시간 분석) 기반 쿼리 — 소방서 반경 5분 도달 영역
-- (이소크론은 별도 라우팅 엔진 필요, 여기서는 직선 거리 근사)
SELECT ST_AsGeoJSON(ST_Buffer(location::geography, 4000)::geometry) AS coverage_5min
FROM fire_station WHERE id = 1;
```

### 2.2 jOOQ + PostGIS 통합

Smart Fire Hub는 jOOQ를 ORM으로 사용하므로, PostGIS 타입을 jOOQ에서 처리하기 위한 바인딩이 필요하다.

#### jooq-postgis-spatial 라이브러리

```kotlin
// build.gradle.kts 추가
dependencies {
    // PostGIS + JTS (Java Topology Suite)
    implementation("org.locationtech.jts:jts-core:1.20.0")
    implementation("net.postgis:postgis-jdbc:2023.1.0")

    // jOOQ PostGIS 바인딩 (JitPack)
    implementation("com.github.dmitry-zhuravlev:jooq-postgis-spatial:1.0.0")

    // 또는 직접 바인딩 구현 (권장 — 의존성 최소화)
}
```

#### 커스텀 바인딩 구현 (권장)

```java
// PostgisGeometryBinding.java
public class PostgisGeometryBinding implements Binding<Object, Geometry> {

    @Override
    public Converter<Object, Geometry> converter() {
        return new Converter<>() {
            @Override
            public Geometry from(Object databaseObject) {
                if (databaseObject == null) return null;
                try {
                    PGgeometry pgGeometry = new PGgeometry(databaseObject.toString());
                    return JTS.toGeometry(pgGeometry.getGeometry());
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public Object to(Geometry userObject) {
                if (userObject == null) return null;
                org.postgis.Geometry pgGeom = JTS.fromGeometry(userObject);
                PGobject pgObject = new PGobject();
                pgObject.setType("geometry");
                try {
                    pgObject.setValue(pgGeom.toString());
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                return pgObject;
            }

            @Override
            public Class<Object> fromType() { return Object.class; }
            @Override
            public Class<Geometry> toType() { return Geometry.class; }
        };
    }
    // ... register/sql/get/set 메서드 구현
}
```

#### jOOQ 코드 생성 설정

```kotlin
// build.gradle.kts — jOOQ 설정 수정
jooq {
    configurations {
        create("main") {
            jooqConfiguration.apply {
                generator.apply {
                    database.apply {
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        inputSchema = "public"
                        // PostGIS 타입 강제 바인딩
                        forcedTypes.addAll(listOf(
                            ForcedType().apply {
                                userType = "org.locationtech.jts.geom.Geometry"
                                binding = "com.smartfirehub.global.jooq.PostgisGeometryBinding"
                                includeExpression = ".*"
                                includeTypes = "geometry|GEOMETRY"
                            }
                        ))
                    }
                }
            }
        }
    }
}
```

### 2.3 H3 — 헥사곤 공간 인덱싱

#### 개요

Uber가 개발하고 오픈소스로 공개한 H3는 지구를 계층적 헥사곤 격자로 분할하는 공간 인덱싱 시스템이다. 각 헥사곤 셀에 고유한 64비트 정수 ID가 부여된다.

```
해상도  |  셀 면적         |  활용 예시
--------|-----------------|------------------
  0     |  4,357,449 km²  |  대륙 단위
  3     |  12,392 km²     |  시도 단위
  5     |  252.9 km²      |  시군구 단위
  7     |  5.16 km²       |  관할구역 단위
  9     |  0.105 km²      |  블록 단위
 11     |  0.002 km²      |  건물 단위
 15     |  0.0000009 km²  |  최대 해상도
```

#### PostGIS + H3 통합

```sql
-- H3 확장 설치 (PostgreSQL)
CREATE EXTENSION IF NOT EXISTS h3;
CREATE EXTENSION IF NOT EXISTS h3_postgis;

-- 좌표를 H3 인덱스로 변환
SELECT h3_lat_lng_to_cell(POINT(37.5665, 126.978), 9) AS h3_index;

-- 화재 발생 건수를 H3 해상도 7로 집계
SELECT h3_lat_lng_to_cell(POINT(
    ST_Y(location), ST_X(location)
), 7) AS h3_cell,
COUNT(*) AS incident_count
FROM fire_incident
WHERE incident_date >= '2025-01-01'
GROUP BY h3_cell
ORDER BY incident_count DESC;
```

#### 소방 플랫폼 활용

- **화재 위험도 맵**: H3 해상도 7~9로 지역별 화재 위험 점수 집계
- **응답 시간 분석**: H3 셀별 평균 응답 시간 시각화
- **자원 배치 최적화**: H3 셀별 소방차/인력 밀도 분석
- **성능**: 공간 조인 대비 73~77% 빠른 최근접 검색, 지역 분석 99% 빠름

### 2.4 Turf.js — 클라이언트 공간 분석

```
라이선스: MIT
최신 버전: v7.x (TypeScript 완전 지원)
설치: pnpm add @turf/turf (전체) 또는 개별 모듈
```

**핵심 기능:**
| 함수 | 용도 | 소방 활용 |
|------|------|----------|
| `buffer()` | 포인트 주변 원형 영역 | 소방서 커버리지 영역 시각화 |
| `distance()` | 두 점 간 거리 | 소방서~화재 현장 거리 |
| `within()` | 포인트가 폴리곤 내에 있는지 | 관할구역 내 건물 판별 |
| `area()` | 폴리곤 면적 | 관할구역 면적 계산 |
| `centroid()` | 무게중심 | 관할구역 중심점 |
| `dissolve()` | 인접 폴리곤 병합 | 상호응원구역 병합 |
| `booleanContains()` | 포함 관계 | 건물이 관할구역에 포함되는지 |
| `nearestPoint()` | 최근접 포인트 | 가장 가까운 소방용수시설 |
| `voronoi()` | 보로노이 다이어그램 | 소방서 기반 관할 영역 자동 생성 |
| `interpolate()` | 보간 | 화재 위험도 보간 맵 |

**장점:** 서버 왕복 없이 브라우저에서 즉시 공간 분석, 모듈별 import로 번들 최적화

```typescript
import { buffer, distance, booleanPointInPolygon } from '@turf/turf';

// 소방서에서 반경 5km 커버리지 영역
const station = turf.point([126.978, 37.5665]);
const coverageArea = buffer(station, 5, { units: 'kilometers' });

// 화재 현장이 관할구역 내인지 확인
const fireLocation = turf.point([127.0, 37.56]);
const isInDistrict = booleanPointInPolygon(fireLocation, districtPolygon);

// 가장 가까운 소방용수시설 검색
const nearestHydrant = turf.nearestPoint(fireLocation, hydrantCollection);
const dist = distance(fireLocation, nearestHydrant, { units: 'meters' });
```

### 2.5 지오코딩 서비스 비교

| 서비스 | 한국 주소 지원 | 무료 한도 | 정확도 | 한국 특화 |
|--------|-------------|---------|--------|----------|
| **국토교통부 지오코더 API** | 도로명/지번 완전 지원 | 40,000건/일 | 높음 | 최고 (공식) |
| **행정안전부 주소정보 API** | 실시간 검색 | 무제한 | 높음 | 최고 (공식) |
| **Kakao Maps Geocoding** | 완전 지원 | 300,000건/일 | 높음 | 매우 높음 |
| **Naver Maps Geocoding** | 완전 지원 | 제한적 | 높음 | 매우 높음 |
| **Google Geocoding API** | 지원 (제한적) | 10,000건/월 | 보통 | 보통 |
| **Nominatim (OSM)** | 부분 지원 | 1건/초 | 낮음 | 낮음 |

**권장**: 한국 소방 플랫폼에서는 **국토교통부 지오코더 API** (공식 + 무료) + **Kakao Maps Geocoding** (보조) 조합이 최적

### 2.6 지리 데이터 포맷

| 포맷 | 특성 | 용도 | PostGIS 지원 |
|------|------|------|-------------|
| **GeoJSON** | JSON 기반, 읽기 쉬움, WGS84 전제 | 웹 API, 프론트엔드 전달 | `ST_AsGeoJSON()` / `ST_GeomFromGeoJSON()` |
| **WKT** | 텍스트 기반, 디버깅 용이 | 로깅, 수동 입력 | `ST_AsText()` / `ST_GeomFromText()` |
| **WKB** | 바이너리, 40-60% 압축 | DB 내부 저장, 전송 최적화 | 기본 저장 포맷 |
| **EWKT/EWKB** | PostGIS 확장 (SRID 포함) | PostGIS 내부 | `ST_AsEWKT()` / `ST_GeomFromEWKB()` |

**Smart Fire Hub 전략:**
- **API 응답**: GeoJSON (프론트엔드 친화적, MapLibre/deck.gl 직접 소비)
- **DB 저장**: WKB (PostGIS 기본, 공간 효율적)
- **내부 처리**: JTS Geometry 객체 (Java)
- **디버깅**: WKT (로그 출력)

```java
// Spring Boot Controller — GeoJSON 응답 예시
@GetMapping("/api/v1/geo/stations")
public FeatureCollection getStations(@RequestParam double lng, @RequestParam double lat,
                                      @RequestParam(defaultValue = "10000") double radiusM) {
    List<FireStation> stations = fireStationService.findWithinRadius(lng, lat, radiusM);

    List<Feature> features = stations.stream().map(s -> {
        Point point = geometryFactory.createPoint(new Coordinate(s.getLng(), s.getLat()));
        Feature feature = new Feature();
        feature.setGeometry(point);
        feature.setProperty("id", s.getId());
        feature.setProperty("name", s.getName());
        feature.setProperty("address", s.getAddress());
        return feature;
    }).toList();

    return new FeatureCollection(features);
}
```

---

## 3. 소방 서비스 GIS 활용 사례

### 3.1 핵심 GIS 기능 — 소방 서비스

#### A. 출동 구역 매핑 (Response Zone Mapping)

**목적:** 소방서별 커버리지 영역과 응답 시간 이소크론(등시간선) 시각화

| 기능 | 구현 방식 | 기술 |
|------|----------|------|
| 관할구역 경계 표시 | GeoJSON 폴리곤 레이어 | MapLibre + GeoJsonLayer |
| 응답 시간 이소크론 | 라우팅 엔진 기반 등시간선 | OSRM/Valhalla + PostGIS |
| 커버리지 갭 분석 | 관할구역 밖 화재 발생 지점 | PostGIS ST_Difference |
| 소방서 위치 | 포인트 마커 + 팝업 | MapLibre Marker |

```
이소크론 구현 접근 방식:
1. OSRM (Open Source Routing Machine) — 무료, 셀프호스팅
2. Valhalla (Mapzen) — 무료, 셀프호스팅, 이소크론 네이티브 지원
3. Kakao Mobility API — 한국 도로 데이터 최적화, 유료
4. 근사 방식 — PostGIS ST_Buffer + 직선거리 (간단, 덜 정확)
```

#### B. 사건 매핑 (Incident Mapping)

**목적:** 화재/구조/구급 출동 데이터의 공간 시각화 및 패턴 분석

| 시각화 유형 | 용도 | 기술 |
|-----------|------|------|
| **히트맵** | 화재 발생 밀집 지역 식별 | deck.gl HeatmapLayer |
| **클러스터링** | 대용량 사건 포인트 그룹핑 | MapLibre supercluster |
| **시계열 필터** | 월별/시간대별 화재 패턴 | deck.gl + 슬라이더 UI |
| **유형별 필터** | 건물화재/차량화재/산불 구분 | GeoJSON 속성 필터링 |
| **심각도 시각화** | 대응 단계별 색상 구분 | deck.gl ScatterplotLayer |

```typescript
// 화재 사건 히트맵 + 시계열 필터
const IncidentMap = () => {
  const [timeRange, setTimeRange] = useState<[Date, Date]>([start, end]);
  const { data: incidents } = useQuery({
    queryKey: ['incidents', 'geo', timeRange],
    queryFn: () => geoApi.getIncidents({ from: timeRange[0], to: timeRange[1] }),
  });

  const heatmapLayer = new HeatmapLayer({
    id: 'incident-heatmap',
    data: incidents,
    getPosition: d => [d.longitude, d.latitude],
    getWeight: d => d.severity === '3단계' ? 10 : d.severity === '2단계' ? 5 : 1,
    radiusPixels: 50,
    intensity: 1,
    threshold: 0.03,
  });

  return (
    <DeckGL layers={[heatmapLayer]} ...>
      <Map mapStyle={vworldStyle} />
      <TimeSlider value={timeRange} onChange={setTimeRange} />
    </DeckGL>
  );
};
```

#### C. 소방용수시설 매핑 (Hydrant/Water Supply Mapping)

**목적:** 소화전, 저수조, 급수탑 등의 위치, 상태, 용량 시각화

| 기능 | 설명 | 기술 |
|------|------|------|
| NFPA 291 색상 코딩 | 유량 기준 색상 구분 | MapLibre data-driven styling |
| 상태 필터 | 활성/점검 필요/고장 | GeoJSON 속성 필터 |
| 반경 분석 | 건물에서 가장 가까운 소화전 | Turf.js nearestPoint |
| 유량 정보 팝업 | 소화전 클릭 시 상세 정보 | MapLibre Popup |
| 관경/유량 시각화 | 크기별 아이콘 | deck.gl IconLayer |

```
NFPA 291 소화전 색상 기준:
- 파란색: 1500+ GPM (Class AA)
- 초록색: 1000-1499 GPM (Class A)
- 주황색: 500-999 GPM (Class B)
- 빨간색: < 500 GPM (Class C)
```

#### D. 사전조사 계획 (Pre-Incident Planning)

| 기능 | 설명 | 데이터 소스 |
|------|------|-----------|
| 건물 윤곽 | 대상 건물 폴리곤 표시 | 건축물대장 + V-World |
| 위험물 위치 | 위험물 저장/취급 시설 | 소방안전정보시스템 |
| 진입로 | 소방차 진입 가능 경로 | 도로망 데이터 |
| 층별 도면 | 건물 내부 도면 (옵션) | BIM/CAD 연동 |
| 연소 확대 예측 | 인접 건물 위험도 | PostGIS ST_Buffer + ST_Intersects |

#### E. 자원 배치 (Resource Deployment)

| 기능 | 설명 | 기술 |
|------|------|------|
| 소방차 실시간 위치 | GPS 기반 위치 추적 | WebSocket + MapLibre |
| 출동 가능 차량 | 현재 상태별 표시 | 실시간 상태 스트리밍 |
| 커버리지 갭 | 소방서 빈 구역 식별 | PostGIS 분석 |
| 최적 배치 제안 | 화재 위험도 기반 배치 | H3 + 통계 분석 |

#### F. 위험 평가 (Risk Assessment)

| 위험 요소 | 데이터 소스 | 분석 방법 |
|----------|-----------|---------|
| 노후 건축물 밀집도 | 건축물대장 | H3 해상도 9 집계 |
| 위험물 시설 | 위험물안전관리 데이터 | 버퍼 분석 |
| 인구 밀도 | 주민등록 통계 | H3 해상도 7 집계 |
| 과거 화재 이력 | 국가화재정보시스템 | 히트맵 + 추세 분석 |
| 소방 인프라 접근성 | 소방서/소화전 위치 | 이소크론 분석 |
| 도로 혼잡도 | 실시간 교통 데이터 | 출동 시간 보정 |

```sql
-- 화재 위험 점수 계산 쿼리 예시 (H3 해상도 9)
WITH h3_cells AS (
    SELECT h3_lat_lng_to_cell(POINT(ST_Y(location), ST_X(location)), 9) AS cell
    FROM generate_series(1, 1)  -- 대상 영역 H3 셀 목록
),
fire_history AS (
    SELECT h3_lat_lng_to_cell(POINT(ST_Y(location), ST_X(location)), 9) AS cell,
           COUNT(*) AS fire_count
    FROM fire_incident
    WHERE incident_date >= NOW() - INTERVAL '3 years'
    GROUP BY cell
),
old_buildings AS (
    SELECT h3_lat_lng_to_cell(POINT(ST_Y(location), ST_X(location)), 9) AS cell,
           COUNT(*) AS old_count
    FROM building_registry
    WHERE build_year < 1990
    GROUP BY cell
)
SELECT c.cell,
       COALESCE(fh.fire_count, 0) * 0.4 +
       COALESCE(ob.old_count, 0) * 0.3 +
       -- 추가 위험 요소 가중치...
       0 AS risk_score
FROM h3_cells c
LEFT JOIN fire_history fh ON c.cell = fh.cell
LEFT JOIN old_buildings ob ON c.cell = ob.cell;
```

#### G. 경로 최적화 (Route Optimization)

| 접근 방식 | 장점 | 단점 | 적합도 |
|----------|------|------|--------|
| OSRM (셀프호스팅) | 무료, 빠름 | 한국 도로 데이터 관리 필요 | 높음 |
| Valhalla | 이소크론 내장, 무료 | 셀프호스팅 필요 | 높음 |
| Kakao Navi API | 한국 도로 최적화, 실시간 교통 | 유료 | 최고 |
| Google Routes API | 글로벌, 실시간 교통 | 유료 (비쌈) | 보통 |
| pgRouting | PostGIS 통합, 무료 | 성능 제한 | 보통 |

#### H. 상호응원구역 (Mutual Aid Zones)

- 인접 소방서 간 관할 중첩 영역 시각화
- 최초 응답 소방서 자동 배정 (최단 거리/시간 기반)
- PostGIS `ST_Intersection()` 으로 중첩 영역 계산

#### I. 점검 관리 매핑 (Inspection Tracking)

- 건물별 점검 이력 및 일정 지도 표시
- 위반 사항 히트맵 (반복 위반 건물 식별)
- 점검 대상 우선순위 지도 (위험도 + 점검 이력 기반)

#### J. ISO/PPC 매핑

- 소방서~건물 거리 기준 등급 시각화 (ISO 기준: 5마일/8km)
- 소화전 커버리지 맵 (NFPA 기준: 1000피트/300m)
- 등급별 색상 코딩 지도

### 3.2 한국 소방 특화 GIS 기능

| 기능 | 설명 | 데이터 |
|------|------|-------|
| **119 관할구역** | 소방서/안전센터별 관할 경계 | V-World WFS, 공공데이터포털 |
| **소방용수시설 현황** | 전국 소화전/저수조 위치 | 소방청 공공데이터 |
| **화재 발생 현황** | 연도별/지역별 화재 통계 | 국가화재정보시스템 (NFDS) |
| **위험물 시설** | 위험물 제조/저장/취급소 | 소방안전 빅데이터 플랫폼 |
| **소방 도로 현황** | 소방차 진입 가능 도로 | 도로명주소 + V-World |
| **지역안전지수** | 화재 부문 안전지수 | 재난안전데이터공유플랫폼 |

---

## 4. 한국 GIS 인프라

### 4.1 국가공간정보포털 (NSDI) / V-World

#### V-World (브이월드) — 국가 공간정보 플랫폼

```
URL: https://www.vworld.kr
API 키: https://api.vworld.kr 에서 신청 (무료)
```

| API 유형 | 설명 | Smart Fire Hub 활용 |
|---------|------|-------------------|
| **배경지도 API** | 2D/3D 배경지도 타일 | 기본 배경지도로 활용 |
| **WMS API 2.0** | 래스터 지도 이미지 서비스 | 주제도 오버레이 |
| **WFS API 2.0** | 벡터 피처 서비스 | 관할구역 경계 데이터 직접 조회 |
| **검색 API 2.0** | 주소/POI 검색 | 지오코딩 보조 |
| **3D 데이터 API** | 3D 건물/지형 | 사전조사 계획 (옵션) |

**V-World WFS에서 제공하는 소방 관련 레이어:**

| 레이어명 | 설명 |
|---------|------|
| `lt_c_firmnbdar` | 소방서관할구역 경계 |
| `lt_c_riskareadngr` | 재해위험지구 |
| `lt_c_forestfirerisk` | 산불위험예측지도 |
| `lt_c_bldgspcuse` | 건물 용도 |
| `lt_c_usedstrc` | 용도지역/지구 |

```typescript
// V-World WFS 데이터 조회 예시
const fetchFireDistricts = async () => {
  const params = new URLSearchParams({
    service: 'WFS',
    version: '2.0.0',
    request: 'GetFeature',
    typeName: 'lt_c_firmnbdar',  // 소방서관할구역
    output: 'application/json',
    key: VWORLD_API_KEY,
    srsName: 'EPSG:4326',
  });

  const response = await fetch(`https://api.vworld.kr/req/wfs?${params}`);
  const geojson = await response.json();
  return geojson;
};
```

```typescript
// V-World 배경지도를 MapLibre에서 사용
const vworldStyle = {
  version: 8,
  sources: {
    vworld: {
      type: 'raster',
      tiles: [
        `https://api.vworld.kr/req/wmts/1.0.0/${VWORLD_API_KEY}/Base/{z}/{y}/{x}.png`
      ],
      tileSize: 256,
    }
  },
  layers: [{
    id: 'vworld-base',
    type: 'raster',
    source: 'vworld',
  }]
};
```

### 4.2 Kakao Maps / Naver Maps API

#### Kakao Maps API

```
URL: https://apis.map.kakao.com
비용: 무료 (일 300,000건)
React 래퍼: react-kakao-maps-sdk
월간 활성 사용자: 1,171만 (2025.03 기준, 한국 1위)
```

| 기능 | 설명 |
|------|------|
| 정적/동적 지도 | 래스터 타일 기반 지도 |
| 지오코딩/역지오코딩 | 한국 주소 완전 지원 |
| 키워드 검색 | POI 기반 장소 검색 |
| 교통 정보 | 실시간 교통 오버레이 |
| 로드뷰 | 거리 뷰 |

**한계:** 독점 렌더러로 MapLibre/deck.gl 레이어 오버레이 불가. 지오코딩 API는 활용 가치 있으나, 메인 맵으로는 MapLibre + V-World 조합이 더 유연.

```typescript
// Kakao Geocoding을 백엔드에서 활용 (Spring Boot)
@Service
public class KakaoGeocodingService {
    private final WebClient webClient;

    public Coordinate geocode(String address) {
        KakaoGeoResponse response = webClient.get()
            .uri("https://dapi.kakao.com/v2/local/search/address.json?query={address}", address)
            .header("Authorization", "KakaoAK " + kakaoApiKey)
            .retrieve()
            .bodyToMono(KakaoGeoResponse.class)
            .block();

        if (response != null && !response.getDocuments().isEmpty()) {
            var doc = response.getDocuments().get(0);
            return new Coordinate(
                Double.parseDouble(doc.getX()),  // 경도
                Double.parseDouble(doc.getY())   // 위도
            );
        }
        return null;
    }
}
```

#### Naver Maps API

```
URL: https://navermaps.github.io/maps.js/
비용: 무료 (일 6,000,000건/Web)
```

Kakao와 유사한 기능 제공. 한국 지도 데이터 품질 우수하나, Kakao 대비 React 생태계가 작음.

### 4.3 공공데이터 포털 (data.go.kr)

#### 소방 관련 핵심 데이터셋

| 데이터셋 | 제공기관 | 포맷 | 갱신 주기 | 좌표 포함 |
|---------|---------|------|---------|---------|
| **전국소방서 좌표현황** | 소방청 | CSV/JSON | 분기 | O (위도/경도) |
| **119안전센터 현황** | 소방청 | CSV/JSON | 분기 | X (주소만) |
| **시도 소방서 현황** | 소방청 | CSV/JSON | 분기 | X (주소만) |
| **화재발생현황** | 소방청 | CSV | 연간 | X (지역 단위) |
| **소방용수시설 현황** | 소방청 | CSV | 연간 | 부분적 |
| **위험물 제조소 현황** | 소방청 | CSV | 연간 | X |
| **건축물대장** | 국토교통부 | API | 실시간 | O (좌표 포함) |
| **도로명주소 건물** | 국토교통부 | API | 실시간 | O (좌표 포함) |

#### 활용 API 목록

```
1. 행정안전부 실시간 주소정보 조회 API
   - 도로명주소 검색 (지오코딩 보조)
   - 신청 즉시 이용 가능

2. 국토교통부 지오코더 API
   - 주소 → 좌표 변환
   - 일일 40,000건 한도

3. 국토교통부 건축물대장 API
   - 건물 상세 정보 + 좌표
   - 사전조사 계획에 활용

4. 소방청 공공데이터
   - 소방서/안전센터/소방용수시설 등
   - CSV 다운로드 또는 OpenAPI
```

### 4.4 소방안전 빅데이터 플랫폼

```
URL: https://bigdata-119.kr
운영: 소방청
```

소방안전 및 소방산업 관련 대용량 데이터를 수집/정제하여 빅데이터 분석 및 시각화를 제공하는 플랫폼.

| 제공 서비스 | 설명 |
|-----------|------|
| 데이터 분석 | 소방 데이터 통계 분석 |
| 시각화 | 대시보드 형태 데이터 시각화 |
| 데이터셋 | 소방 관련 공공 데이터셋 |

### 4.5 국가화재정보시스템 (NFDS)

```
URL: https://www.nfds.go.kr
운영: 소방청
```

| 데이터 | 설명 |
|--------|------|
| 화재 발생 현황 | 연도/지역/원인별 통계 |
| 화재 통계 연감 | 연간 상세 통계 보고서 |
| 화재 발생 대응 가이드라인 | 화재 유형별 대응 지침 |

### 4.6 한국 좌표계

#### 주요 좌표계

| EPSG 코드 | 명칭 | 설명 | 용도 |
|-----------|------|------|------|
| **4326** | WGS84 | 위도/경도 (GPS 표준) | 웹 지도 기본, GeoJSON 표준 |
| **5179** | Korea 2000 Unified CS | 국가 통합좌표계 (TM) | 국토정보, V-World |
| **5186** | Korea 2000 Central Belt 2010 | 중부원점 좌표계 | 대축척 지도, 건설 |
| **5174** | Korean 1985 Central Belt | 구 좌표계 (레거시) | 구형 시스템 호환 |
| **3857** | Web Mercator | 웹 타일 맵 투영 | MapLibre/Leaflet 내부 |

#### 좌표계 변환

```
EPSG:5179 (Korea 2000 Unified)
- 투영: 횡축 메르카토르 (TM)
- 원점 위도: 38°N, 중앙 경선: 127.5°E
- 축척 계수: 0.9996
- 가산값: E=1,000,000m, N=2,000,000m
- 타원체: GRS80
- WGS84 변환: 0 파라미터 (사실상 동일)

EPSG:5186 (Korea 2000 Central Belt 2010)
- 투영: 횡축 메르카토르 (TM)
- 원점 위도: 38°N, 중앙 경선: 127°E
- 축척 계수: 1.0
- 가산값: E=200,000m, N=600,000m
```

```sql
-- PostGIS에서 좌표계 변환
-- 5179 → 4326 (WGS84)
SELECT ST_AsGeoJSON(
    ST_Transform(
        ST_SetSRID(ST_MakePoint(953898.0, 1952025.0), 5179),
        4326
    )
);

-- 4326 (WGS84) → 5179
SELECT ST_AsText(
    ST_Transform(
        ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326),
        5179
    )
);
```

```typescript
// 프론트엔드에서 proj4js 사용 좌표 변환
import proj4 from 'proj4';

// 좌표계 정의
proj4.defs('EPSG:5179', '+proj=tmerc +lat_0=38 +lon_0=127.5 +k=0.9996 +x_0=1000000 +y_0=2000000 +ellps=GRS80 +units=m +no_defs');
proj4.defs('EPSG:5186', '+proj=tmerc +lat_0=38 +lon_0=127 +k=1 +x_0=200000 +y_0=600000 +ellps=GRS80 +units=m +no_defs');

// 5179 → WGS84
const [lng, lat] = proj4('EPSG:5179', 'EPSG:4326', [953898, 1952025]);

// WGS84 → 5179
const [x, y] = proj4('EPSG:4326', 'EPSG:5179', [126.978, 37.5665]);
```

---

## 5. 아키텍처 권장사항 (Smart Fire Hub 통합)

### 5.1 현재 시스템 분석

```
현재 스택:
- Frontend: React 19 + TypeScript + Vite 7 + Tailwind CSS v4 + shadcn/ui
- Backend: Spring Boot 3.4 + Java 21 + jOOQ + Flyway
- Database: PostgreSQL 16 (Docker, postgres:16 이미지)
- 상태 관리: TanStack Query (서버 상태)
- 라우팅: React Router v7
- 인증: JWT (HS256)
- DB 스키마: public (메타데이터) + data (동적 사용자 테이블)
- Flyway 마이그레이션: V1~V26
```

### 5.2 PostGIS 도입 (기존 PostgreSQL 확장)

#### Step 1: Docker 이미지 교체

```yaml
# docker-compose.yml
services:
  db:
    image: postgis/postgis:16-3.5   # postgres:16 에서 변경
    # 나머지 설정 동일 — 완전 호환
```

> `postgis/postgis:16-3.5`는 `postgres:16` 기반에 PostGIS 확장이 추가된 이미지이다. 기존 데이터와 완전 호환되며, 단순 이미지 교체만으로 도입 가능하다. 기존 `pgdata` 볼륨도 그대로 사용 가능하다.

#### Step 2: Flyway 마이그레이션 추가

```sql
-- V27__enable_postgis_extension.sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- 한국 좌표계 확인 (PostGIS에 기본 포함)
-- SELECT srid, srtext FROM spatial_ref_sys WHERE srid IN (4326, 5179, 5186);
```

#### Step 3: jOOQ 바인딩 추가

```kotlin
// build.gradle.kts 의존성 추가
dependencies {
    // ... 기존 의존성

    // PostGIS + JTS
    implementation("org.locationtech.jts:jts-core:1.20.0")
    implementation("net.postgis:postgis-jdbc:2023.1.0")

    // jOOQ 코드젠에도 추가
    jooqGenerator("net.postgis:postgis-jdbc:2023.1.0")
    jooqGenerator("org.locationtech.jts:jts-core:1.20.0")
}
```

#### Step 4: 프론트엔드 패키지 추가

```bash
cd apps/firehub-web
pnpm add maplibre-gl @visgl/react-map-gl
pnpm add @deck.gl/core @deck.gl/layers @deck.gl/aggregation-layers @deck.gl/geo-layers @deck.gl/react
pnpm add @turf/turf
pnpm add proj4
pnpm add -D @types/proj4
```

### 5.3 권장 매핑 라이브러리: MapLibre GL JS + deck.gl

#### React 19 + TypeScript + Tailwind 통합

```typescript
// src/components/map/FireMap.tsx
import { useCallback, useMemo, useState } from 'react';
import Map, { Source, Layer, NavigationControl, Popup } from 'react-map-gl/maplibre';
import { DeckGL } from '@deck.gl/react';
import { HeatmapLayer, ScatterplotLayer } from '@deck.gl/aggregation-layers';
import { GeoJsonLayer } from '@deck.gl/layers';
import 'maplibre-gl/dist/maplibre-gl.css';

const VWORLD_API_KEY = import.meta.env.VITE_VWORLD_API_KEY;

// V-World 배경지도 스타일
const mapStyle = {
  version: 8 as const,
  sources: {
    vworld: {
      type: 'raster' as const,
      tiles: [
        `https://api.vworld.kr/req/wmts/1.0.0/${VWORLD_API_KEY}/Base/{z}/{y}/{x}.png`
      ],
      tileSize: 256,
      attribution: '© V-World',
    }
  },
  layers: [{
    id: 'vworld-base',
    type: 'raster' as const,
    source: 'vworld',
  }]
};

interface FireMapProps {
  stations?: GeoJSON.FeatureCollection;
  hydrants?: GeoJSON.FeatureCollection;
  incidents?: Array<{ longitude: number; latitude: number; severity: number }>;
  districts?: GeoJSON.FeatureCollection;
}

export function FireMap({ stations, hydrants, incidents, districts }: FireMapProps) {
  const [viewState, setViewState] = useState({
    longitude: 126.978,
    latitude: 37.5665,
    zoom: 11,
  });

  const layers = useMemo(() => {
    const result = [];

    // 관할구역 경계 레이어
    if (districts) {
      result.push(new GeoJsonLayer({
        id: 'districts',
        data: districts,
        filled: true,
        stroked: true,
        getFillColor: [66, 133, 244, 40],
        getLineColor: [66, 133, 244, 200],
        getLineWidth: 2,
        pickable: true,
      }));
    }

    // 화재 사건 히트맵
    if (incidents?.length) {
      result.push(new HeatmapLayer({
        id: 'incident-heatmap',
        data: incidents,
        getPosition: (d: any) => [d.longitude, d.latitude],
        getWeight: (d: any) => d.severity,
        radiusPixels: 60,
        intensity: 1,
        threshold: 0.03,
      }));
    }

    // 소방용수시설 포인트
    if (hydrants) {
      result.push(new GeoJsonLayer({
        id: 'hydrants',
        data: hydrants,
        pointType: 'circle',
        getFillColor: (f: any) => {
          const flow = f.properties?.flow_rate_lpm || 0;
          if (flow >= 5678) return [0, 0, 255, 200];     // Class AA
          if (flow >= 3785) return [0, 200, 0, 200];     // Class A
          if (flow >= 1893) return [255, 165, 0, 200];   // Class B
          return [255, 0, 0, 200];                        // Class C
        },
        getPointRadius: 6,
        pickable: true,
      }));
    }

    return result;
  }, [districts, incidents, hydrants]);

  return (
    <div className="relative h-full w-full rounded-lg overflow-hidden border">
      <DeckGL
        viewState={viewState}
        onViewStateChange={({ viewState: vs }) => setViewState(vs as any)}
        layers={layers}
        controller
      >
        <Map mapStyle={mapStyle}>
          <NavigationControl position="top-right" />
        </Map>
      </DeckGL>
    </div>
  );
}
```

#### Vite 설정 (청크 분리)

```typescript
// vite.config.ts — manualChunks 추가
build: {
  rollupOptions: {
    output: {
      manualChunks: {
        // 기존 청크들...
        'maplibre': ['maplibre-gl', '@visgl/react-map-gl'],
        'deckgl': ['@deck.gl/core', '@deck.gl/layers', '@deck.gl/react',
                   '@deck.gl/aggregation-layers', '@deck.gl/geo-layers'],
        'turf': ['@turf/turf'],
      }
    }
  }
}
```

### 5.4 대용량 공간 데이터 처리 전략

#### 백엔드 전략

| 전략 | 적용 시점 | 구현 |
|------|----------|------|
| **공간 인덱스 (GiST)** | 항상 | `CREATE INDEX ... USING GIST(location)` |
| **클러스터드 인덱스** | 대용량 테이블 | `CLUSTER fire_incident USING idx_location` |
| **벡터 타일 서버** | 100K+ 피처 | pg_tileserv 또는 Martin 타일 서버 |
| **뷰포트 기반 쿼리** | 맵 이동 시 | `ST_Intersects(location, ST_MakeEnvelope(...))` |
| **H3 사전 집계** | 통계/히트맵 | 스케줄러로 주기적 H3 집계 |
| **Materialized View** | 복잡한 공간 조인 | `CREATE MATERIALIZED VIEW ...` + 주기 갱신 |

#### 벡터 타일 서버 (대용량 데이터 권장)

```
10만 개 이상의 피처를 실시간으로 서비스해야 할 경우,
GeoJSON 직접 전달 대신 벡터 타일(MVT) 서버를 도입한다.

권장 옵션:
1. Martin (https://github.com/maplibre/martin) — Rust 기반, MapLibre 공식
   - PostGIS 테이블/함수에서 직접 MVT 생성
   - 설정 파일 기반, Docker 지원

2. pg_tileserv (https://github.com/CrunchyData/pg_tileserv) — Go 기반
   - PostGIS 기반 벡터 타일 서비스
   - 최소 설정으로 바로 사용 가능
```

```yaml
# docker-compose.yml에 Martin 추가 (선택)
services:
  martin:
    image: ghcr.io/maplibre/martin:latest
    environment:
      DATABASE_URL: postgres://app:app@db:5432/smartfirehub
    ports:
      - "3000:3000"
    depends_on:
      db:
        condition: service_healthy
```

#### 프론트엔드 전략

| 전략 | 적용 시점 | 구현 |
|------|----------|------|
| **벡터 타일 소비** | 100K+ 피처 | MapLibre vector tile source |
| **클러스터링** | 1K+ 포인트 | MapLibre 네이티브 클러스터 or supercluster |
| **뷰포트 로딩** | 동적 데이터 | `moveend` 이벤트로 API 호출 |
| **데이터 청크 로딩** | 초기 로딩 | deck.gl 청크 로딩 패턴 |
| **WebWorker** | 무거운 Turf.js 분석 | Worker thread에서 공간 연산 |
| **React.useMemo** | 레이어 재생성 방지 | 데이터/설정 변경 시만 레이어 재생성 |
| **deck.gl updateTriggers** | 속성 변경 최적화 | 변경된 속성만 GPU에 전달 |

### 5.5 실시간 위치 추적 (소방차)

#### 아키텍처

```
┌──────────────┐     GPS       ┌────────────────┐
│  소방차 GPS   │ ──────────→  │  Spring Boot    │
│  (AVL 장비)   │  (TCP/UDP)   │  WebSocket Hub  │
└──────────────┘              │                  │
                               │  /ws/tracking    │
                               └────────┬─────────┘
                                        │ WebSocket
                                        │ (STOMP)
                               ┌────────┴─────────┐
                               │  React Frontend   │
                               │  MapLibre GL JS   │
                               │  (실시간 마커 이동) │
                               └──────────────────┘
```

#### 백엔드 구현

```java
// WebSocketConfig.java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/tracking")
                .setAllowedOrigins("*")
                .withSockJS();
    }
}

// VehicleTrackingService.java
@Service
public class VehicleTrackingService {
    private final SimpMessagingTemplate messagingTemplate;
    private final DSLContext dsl;

    // GPS 데이터 수신 시 호출
    public void updateVehicleLocation(Long vehicleId, double lat, double lng, String status) {
        // DB 업데이트
        dsl.update(FIRE_VEHICLE)
           .set(FIRE_VEHICLE.CURRENT_LOCATION,
                geometryFactory.createPoint(new Coordinate(lng, lat)))
           .set(FIRE_VEHICLE.STATUS, status)
           .set(FIRE_VEHICLE.LAST_UPDATED, LocalDateTime.now())
           .where(FIRE_VEHICLE.ID.eq(vehicleId))
           .execute();

        // WebSocket으로 프론트엔드에 전송
        VehicleLocationMessage msg = new VehicleLocationMessage(
            vehicleId, lat, lng, status, LocalDateTime.now()
        );
        messagingTemplate.convertAndSend("/topic/vehicle-locations", msg);
    }
}
```

#### 프론트엔드 구현

```typescript
// src/hooks/useVehicleTracking.ts
import { useEffect, useRef, useState } from 'react';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

interface VehicleLocation {
  vehicleId: number;
  latitude: number;
  longitude: number;
  status: 'AVAILABLE' | 'DISPATCHED' | 'ON_SCENE' | 'RETURNING';
  lastUpdated: string;
}

export function useVehicleTracking() {
  const [vehicles, setVehicles] = useState<Map<number, VehicleLocation>>(new Map());
  const clientRef = useRef<Client>();

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS('/ws/tracking'),
      onConnect: () => {
        client.subscribe('/topic/vehicle-locations', (message) => {
          const location: VehicleLocation = JSON.parse(message.body);
          setVehicles(prev => {
            const next = new Map(prev);
            next.set(location.vehicleId, location);
            return next;
          });
        });
      },
    });

    client.activate();
    clientRef.current = client;

    return () => { client.deactivate(); };
  }, []);

  return vehicles;
}
```

### 5.6 전체 시스템 아키텍처 (GIS 통합 후)

```
┌─────────────────────────────────────────────────────────────────────┐
│                         firehub-web (React 19)                       │
│                                                                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │
│  │ MapLibre GL  │  │   deck.gl    │  │  Turf.js     │               │
│  │   + V-World  │  │  (히트맵/    │  │ (클라이언트   │               │
│  │   배경지도    │  │  클러스터)   │  │  공간분석)   │               │
│  └──────┬───────┘  └──────┬───────┘  └──────────────┘               │
│         │                  │                                         │
│         └────────┬─────────┘                                         │
│                  │                                                   │
│    ┌─────────────┴──────────────┐                                   │
│    │  react-map-gl v8           │                                   │
│    │  TanStack Query (GeoJSON)  │                                   │
│    │  WebSocket (실시간 추적)    │                                   │
│    └────────────────────────────┘                                   │
└────────────────────────────┬────────────────────────────────────────┘
                             │ /api/v1/geo/*
                             │ /ws/tracking
                             │
┌────────────────────────────┴────────────────────────────────────────┐
│                     firehub-api (Spring Boot 3.4)                    │
│                                                                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │
│  │ GeoController│  │ GeoService   │  │ GeoRepository│               │
│  │ (GeoJSON API)│→ │ (공간 로직)  │→ │ (PostGIS     │               │
│  │              │  │              │  │  쿼리)       │               │
│  └──────────────┘  └──────────────┘  └──────────────┘               │
│                                                                       │
│  ┌──────────────┐  ┌──────────────┐                                 │
│  │ WebSocket    │  │ Geocoding    │                                 │
│  │ Tracking Hub │  │ Service      │                                 │
│  └──────────────┘  │ (Kakao+공공) │                                 │
│                     └──────────────┘                                 │
└────────────────────────────┬────────────────────────────────────────┘
                             │
┌────────────────────────────┴────────────────────────────────────────┐
│              PostgreSQL 16 + PostGIS 3.5                             │
│                                                                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │
│  │ public 스키마│  │ data 스키마  │  │ geo 스키마    │               │
│  │ (메타데이터) │  │ (동적 테이블)│  │ (공간 데이터) │               │
│  │ user, role,  │  │ 사용자 데이터│  │ fire_station, │               │
│  │ pipeline...  │  │ 셋 테이블    │  │ fire_hydrant, │               │
│  │              │  │              │  │ fire_incident,│               │
│  │              │  │              │  │ fire_district │               │
│  └──────────────┘  └──────────────┘  └──────────────┘               │
│                                                                       │
│  GiST 공간 인덱스 | H3 (선택) | ST_* 함수                          │
└─────────────────────────────────────────────────────────────────────┘

외부 데이터 소스:
┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  V-World     │  │ 공공데이터   │  │ Kakao Maps   │  │ 소방안전     │
│  WMS/WFS     │  │ 포털 API     │  │ Geocoding    │  │ 빅데이터     │
│  소방서관할   │  │ 소방서좌표   │  │ 주소→좌표    │  │ 플랫폼       │
│  경계 데이터  │  │ 건축물대장   │  │              │  │              │
└──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘
```

### 5.7 구현 로드맵

#### Phase 1: 기반 구축 (2~3주)

| 작업 | 상세 | 우선순위 |
|------|------|---------|
| Docker 이미지 교체 | `postgres:16` → `postgis/postgis:16-3.5` | 필수 |
| PostGIS 마이그레이션 | V27: 확장 활성화, V28: 공간 테이블 생성 | 필수 |
| jOOQ PostGIS 바인딩 | Geometry 타입 커스텀 바인딩 | 필수 |
| 프론트엔드 패키지 설치 | MapLibre + deck.gl + Turf.js | 필수 |
| 기본 맵 컴포넌트 | V-World 배경지도 + 네비게이션 컨트롤 | 필수 |
| GeoJSON API 엔드포인트 | `/api/v1/geo/stations`, `/api/v1/geo/hydrants` | 필수 |

#### Phase 2: 핵심 기능 (3~4주)

| 작업 | 상세 | 우선순위 |
|------|------|---------|
| 소방서 관할구역 맵 | V-World WFS 데이터 연동, 경계 폴리곤 표시 | 높음 |
| 소방용수시설 맵 | 포인트 레이어 + NFPA 색상 코딩 + 상태 필터 | 높음 |
| 화재 사건 히트맵 | deck.gl HeatmapLayer + 시계열 필터 | 높음 |
| 지오코딩 서비스 | Kakao + 공공데이터 포털 API 통합 | 높음 |
| 공간 쿼리 API | 반경 검색, 관할구역 판별 | 높음 |
| 공공데이터 수집 파이프라인 | 소방서 좌표, 소방용수시설 데이터 ETL | 높음 |

#### Phase 3: 고급 기능 (4~6주)

| 작업 | 상세 | 우선순위 |
|------|------|---------|
| 실시간 차량 추적 | WebSocket + MapLibre 애니메이션 마커 | 중간 |
| 화재 위험도 맵 | H3 기반 위험 점수 집계 + 헥사곤 시각화 | 중간 |
| 이소크론 분석 | 출동 시간 기반 커버리지 | 중간 |
| 사전조사 계획 뷰 | 건축물대장 연동 + 건물 상세 정보 | 중간 |
| 경로 최적화 | OSRM/Kakao Navi 연동 | 낮음 |
| 벡터 타일 서버 | Martin 도입 (대용량 데이터 대응) | 낮음 |
| 오프라인 맵 | Service Worker + 타일 캐시 | 낮음 |

### 5.8 비용 분석

| 항목 | 비용 | 비고 |
|------|------|------|
| MapLibre GL JS | 무료 | BSD-3 라이선스 |
| deck.gl | 무료 | MIT 라이선스 |
| Turf.js | 무료 | MIT 라이선스 |
| PostGIS | 무료 | 오픈소스 확장 |
| V-World API | 무료 | 공공 서비스 (API 키 필요) |
| 공공데이터 포털 API | 무료 | 회원가입 + 활용신청 |
| 국토교통부 지오코더 | 무료 (40K건/일) | 공공 서비스 |
| Kakao Maps Geocoding | 무료 (300K건/일) | 개발자 등록 |
| Martin 타일 서버 | 무료 | 오픈소스 |
| **총 소프트웨어 비용** | **무료** | 모두 오픈소스/공공 |
| **인프라 비용** | Docker 서버 비용만 | 기존 인프라 활용 |

---

## 참고 자료

### 매핑 라이브러리
- [MapLibre GL JS 공식 문서](https://maplibre.org/maplibre-gl-js/docs/)
- [react-map-gl v8 문서](https://visgl.github.io/react-map-gl/docs)
- [deck.gl 공식 문서](https://deck.gl/docs)
- [React-Leaflet 문서](https://react-leaflet.js.org/)
- [Turf.js 공식 사이트](https://turfjs.org/)
- [Kepler.gl GitHub](https://github.com/keplergl/kepler.gl)
- [CesiumJS 공식 사이트](https://cesium.com/platform/cesiumjs/)
- [OpenLayers 공식 사이트](https://openlayers.org/)

### 백엔드/데이터베이스
- [PostGIS 공식 문서](https://postgis.net/documentation/)
- [postgis/docker-postgis GitHub](https://github.com/postgis/docker-postgis)
- [jooq-postgis-spatial GitHub](https://github.com/dmitry-zhuravlev/jooq-postgis-spatial)
- [H3 공식 문서](https://h3geo.org/)
- [h3-pg PostgreSQL 확장](https://github.com/zachasme/h3-pg)
- [Martin 벡터 타일 서버](https://github.com/maplibre/martin)

### 한국 GIS 인프라
- [V-World 오픈 API](https://www.vworld.kr/dev/v4dv_icbsource_s001.do)
- [V-World WMS/WFS API 레퍼런스](https://www.vworld.kr/dev/v4dv_wmsguide2_s001.do)
- [공공데이터포털 — 소방청 데이터](https://www.data.go.kr/tcs/dss/selectDataSetList.do?org=소방청)
- [국가화재정보시스템 (NFDS)](https://www.nfds.go.kr/)
- [소방안전 빅데이터 플랫폼](https://bigdata-119.kr/)
- [행정안전부 주소정보 API](https://www.data.go.kr/data/15057017/openapi.do)
- [국토교통부 지오코더 API](https://www.data.go.kr/data/15101106/openapi.do)
- [EPSG:5179 좌표계 정보](https://epsg.io/5179)
- [EPSG:5186 좌표계 정보](https://epsg.io/5186)

### 소방 GIS
- [ESRI — GIS for Fire Departments](https://www.esri.com/en-us/industries/fire-rescue-ems/overview)
- [Firehouse — Using GIS to Site Fire Stations](https://www.firehouse.com/stations/article/55273616/using-gis-to-site-fire-stations-and-improve-incident-response-times)
- [GIS Mapping and Firefighting](https://alpinesoftware.com/industry-articles/gis-mapping-and-firefighting-enhancing-situational-awareness/)

### 비교/분석
- [매핑 라이브러리 비교 — LogRocket](https://blog.logrocket.com/react-map-library-comparison/)
- [Leaflet vs MapLibre vs OpenLayers — Geoapify](https://www.geoapify.com/map-libraries-comparison-leaflet-vs-maplibre-gl-vs-openlayers-trends-and-statistics/)
- [GeoJSON vs WKT vs WKB 가이드](https://gis-tools.com/gis-guide.html)
- [Mapbox GL 새 라이선스와 대안](https://www.geoapify.com/mapbox-gl-new-license-and-6-free-alternatives/)
- [Google Maps Platform 요금 변경 (2025.03)](https://developers.google.com/maps/billing-and-pricing/march-2025)
- [한국 서비스를 위한 최적의 지도 API](https://medium.com/@codeisneverodd/the-best-map-api-for-korean-services-62fa0fb5c78d)
