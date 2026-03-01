# Smart Fire Hub — 데이터 플랫폼 로드맵

> 작성일: 2026-03-01
> 목적: 소방 도메인 특화 이전에 범용 데이터 플랫폼의 기반을 탄탄히 구축한다.
> 원칙: 기초 기술 → 범용 기능 → 도메인 특화 순서로 확장한다.

---

## 현재 플랫폼 완성도

| 모듈 | 완성도 | 상태 |
|------|--------|------|
| 데이터 수집 (CSV/Excel/API) | 80% | CSV/Excel 업로드 완성, API 수집은 파이프라인으로만 |
| ETL 파이프라인 | 75% | SQL/Python/API_CALL DAG 실행, 조건부 분기 없음 |
| 데이터 관리 | 85% | CRUD/검색/필터/카테고리/태그 완성 |
| 분석/시각화 | 70% | SQL 쿼리 + 차트 7종 + 대시보드, 지도/고급 시각화 없음 |
| AI 에이전트 | 60% | MCP 도구 36종 CRUD, Text-to-SQL 미구현 |
| 보안/거버넌스 | 65% | JWT + RBAC, RLS/감사 추적 부분적 |

---

## Phase 1: GIS 범용 기반 (첫 번째 개발 항목)

> **목표**: 데이터 플랫폼의 범용 기능으로 공간 데이터를 지원한다.
> 소방 도메인 특화가 아닌, 어떤 도메인이든 위치 데이터를 다룰 수 있는 기반.

### 1A. Backend — GEOMETRY 타입 지원

**변경 대상 파일:**

| 파일 | 변경 내용 |
|------|---------|
| `DataTableService.mapDataType()` | `"GEOMETRY"` → `"GEOMETRY(Point, 4326)"` 케이스 추가 |
| `DataTableService.createTable()` | GEOMETRY 컬럼에 GIST 인덱스 자동 생성 |
| `DataTableService.setColumnIndex()` | GEOMETRY 컬럼 → GIST 인덱스 분기 |
| `DatasetService.VALID_DATA_TYPES` | `"GEOMETRY"` 추가 |
| `DataTableRowService.insertBatch()` | GeoJSON 입력 → `ST_GeomFromGeoJSON()` 변환 |
| `DataTableRowService.queryData()` | GEOMETRY 컬럼 → `ST_AsGeoJSON()` 래핑 출력 |
| `DataTableQueryService.executeQuery()` | GEOMETRY 결과를 GeoJSON으로 변환 |

**새로운 공간 쿼리 API:**

```
GET  /api/v1/datasets/{id}/data/nearby?lat=37.5&lng=127.0&radius=500&geometryColumn=location
     → 반경 검색 (ST_DWithin)

GET  /api/v1/datasets/{id}/data/bbox?minLat=37.4&maxLat=37.6&minLng=126.9&maxLng=127.1&geometryColumn=location
     → 바운딩박스 검색 (ST_Within + ST_MakeEnvelope)

GET  /api/v1/datasets/{id}/data/geojson?geometryColumn=location&limit=1000
     → GeoJSON FeatureCollection 응답 (지도 렌더링용)
```

**수용 기준:**
- [ ] 데이터셋 생성 시 GEOMETRY 타입 컬럼 선택 가능
- [ ] CSV 업로드 시 위도/경도 컬럼 → GEOMETRY 자동 변환 옵션
- [ ] GEOMETRY 컬럼에 GIST 인덱스 자동 생성
- [ ] GeoJSON 입출력 왕복 동작
- [ ] 반경 검색/바운딩박스 검색 API 동작
- [ ] TC: GEOMETRY CRUD 5개 + 공간 쿼리 5개

### 1B. Frontend — 지도 시각화 컴포넌트

**패키지 설치:**
```bash
pnpm --filter firehub-web add maplibre-gl
pnpm --filter firehub-web add -D @types/maplibre-gl
```

**새 컴포넌트:**

```
apps/firehub-web/src/components/map/
  ├── MapView.tsx           — 범용 지도 컴포넌트 (MapLibre GL JS)
  ├── MapControls.tsx       — 줌, 내비게이션, 전체화면
  ├── useMapStyle.ts        — 배경지도 스타일 관리 (OSM 기본)
  └── GeoJsonLayer.tsx      — GeoJSON 데이터 → 지도 마커/폴리곤
```

**기존 차트 시스템에 MAP 타입 추가:**

| 파일 | 변경 |
|------|------|
| `types/analytics.ts` | `ChartType`에 `'MAP'` 추가 |
| `ChartTypeSelector.tsx` | 지도 아이콘 + 라벨 추가 |
| `ChartRenderer.tsx` | `case 'MAP': return <MapChartView />` |
| `ChartConfig` | `latitudeColumn`, `longitudeColumn`, `geometryColumn` 필드 추가 |
| `ColumnTypeSelect.tsx` | GEOMETRY 타입 옵션 추가 |
| `validations/dataset.ts` | Zod 스키마에 GEOMETRY 추가 |

**Vite 번들 분리:**
```typescript
// vite.config.ts — manualChunks
maplibre: ['maplibre-gl'],
```

**수용 기준:**
- [ ] 분석 쿼리 결과에 위도/경도 또는 GEOMETRY 컬럼이 있으면 MAP 차트 타입 선택 가능
- [ ] 대시보드에 지도 위젯 추가 가능
- [ ] OSM 배경지도 + GeoJSON 마커 렌더링
- [ ] 마커 클릭 시 팝업으로 행 데이터 표시
- [ ] MapLibre 청크가 메인 번들에 미포함 (lazy load)

### 1C. DB 마이그레이션 — chart_type 확장

```sql
-- V31__add_map_chart_type.sql
ALTER TABLE chart DROP CONSTRAINT IF EXISTS chart_chart_type_check;
ALTER TABLE chart ADD CONSTRAINT chart_chart_type_check
    CHECK (chart_type IN ('BAR','LINE','PIE','AREA','SCATTER','DONUT','TABLE','MAP'));
```

### 1D. AI 에이전트 — 공간 쿼리 도구

**새 MCP 도구:**

| 도구 | 설명 |
|------|------|
| `query_nearby_data` | 특정 좌표 반경 내 데이터 검색 |
| `query_bbox_data` | 바운딩박스 영역 내 데이터 검색 |
| `get_geojson` | 데이터셋의 GeoJSON FeatureCollection 반환 |

**예시 AI 질의:**
- "이 데이터셋에서 서울시청 반경 1km 내 데이터 보여줘"
- "강남구 영역 안에 있는 데이터만 필터링해줘"

---

## Phase 2: AI Text-to-SQL

> **목표**: 비개발자가 자연어로 데이터를 조회/분석할 수 있다.

### 2A. 스키마 컨텍스트 제공

- AI에게 `data` 스키마의 테이블/컬럼 정보를 자동 제공
- 데이터셋 메타데이터 (이름, 설명, 컬럼 타입, 샘플 값) → 시스템 프롬프트 주입
- `search_path = 'data'` 제약 준수

### 2B. SQL 생성 + 실행 파이프라인

```
사용자 질문 → AI SQL 생성 → 검증(DDL 거부) → 실행 → 결과 요약 + 차트 추천
```

- AI가 생성한 SQL을 기존 `executeQuery` API로 실행
- 결과를 자연어로 요약하고, 적합한 차트 타입 추천
- GEOMETRY 컬럼 포함 시 MAP 차트 자동 추천

### 2C. 새 MCP 도구

| 도구 | 설명 |
|------|------|
| `get_dataset_schema` | 데이터셋의 컬럼 정보 + 샘플 데이터 5행 반환 |
| `generate_and_execute_sql` | 자연어 → SQL 생성 → 실행 → 결과 반환 |
| `suggest_chart` | 쿼리 결과 기반 차트 타입 + 설정 추천 |

**수용 기준:**
- [ ] "이 데이터셋에서 월별 건수를 보여줘" → SQL 생성 + 실행 + 차트 추천
- [ ] GEOMETRY 컬럼이 있는 쿼리 → MAP 차트 자동 추천
- [ ] DDL/DML(INSERT/UPDATE/DELETE) 시도 시 거부
- [ ] TC: Text-to-SQL 시나리오 10개

---

## Phase 3: 대시보드 실시간 갱신

> **목표**: 운영 모니터링이 가능한 라이브 대시보드.

### 3A. 자동 갱신 메커니즘

- TanStack Query `refetchInterval` 활용 (위젯별 갱신 주기 설정)
- 대시보드 설정에 갱신 주기 옵션: 없음 / 10초 / 30초 / 1분 / 5분
- 파이프라인 실행 상태 폴링 → 완료 시 관련 위젯 자동 갱신

### 3B. SSE 기반 실시간 알림

- 파이프라인 실행 완료/실패 시 대시보드에 토스트 알림
- 기존 `AsyncJobService`의 `SseEmitter` 패턴 활용
- 데이터셋 변경 이벤트 → 관련 차트 자동 갱신

**수용 기준:**
- [ ] 대시보드 위젯별 자동 갱신 주기 설정 가능
- [ ] 갱신 중 로딩 인디케이터 표시 (데이터는 유지)
- [ ] 파이프라인 완료 시 관련 대시보드 자동 갱신

---

## Phase 4: 데이터 내보내기

> **목표**: 분석 결과를 외부로 가져갈 수 있다.

### 4A. 데이터셋 내보내기

| 포맷 | 지원 범위 |
|------|---------|
| CSV | 전체/필터링된 데이터 |
| Excel (XLSX) | 전체/필터링된 데이터 + 헤더 서식 |
| GeoJSON | GEOMETRY 컬럼이 있는 데이터셋 |

### 4B. 쿼리 결과 내보내기

- 분석 쿼리 실행 결과를 CSV/Excel로 다운로드
- 대시보드 차트 데이터를 CSV로 다운로드
- 지도 데이터를 GeoJSON으로 다운로드

### 4C. 프론트엔드 UI

- 데이터셋 목록/상세 페이지에 "내보내기" 버튼
- 쿼리 결과 테이블에 "다운로드" 버튼
- 포맷 선택 드롭다운 (CSV / Excel / GeoJSON)

**수용 기준:**
- [ ] 데이터셋 10만 행을 CSV로 스트리밍 다운로드 (메모리 초과 없이)
- [ ] GEOMETRY 컬럼이 있는 데이터셋을 GeoJSON으로 다운로드
- [ ] 대용량 내보내기는 백그라운드 작업으로 처리

---

## Phase 5: 소방 도메인 특화

> **목표**: Phase 1~4의 범용 플랫폼 위에 소방 전문 기능을 올린다.
> Phase 1~4가 완성된 상태에서 진행.

### 5A. fire 스키마 활용

- Phase 1에서 구축한 GIS 기반 위에 소방 CRUD API 추가
- 기존 파이프라인으로 공공데이터 → fire 스키마 ETL
- 소방 전용 대시보드 (Phase 3 기반)

### 5B. 소방 MCP 도구

- AI가 fire 스키마 데이터를 자연어로 분석
- Phase 2 Text-to-SQL 기반으로 소방 데이터 쿼리

### 5C. 소방 전용 지도

- Phase 1 MapLibre 컴포넌트에 V-World 배경지도 추가
- 소방서/소화전/사건 전용 레이어
- 반경 검색, 커버리지 분석

### 5D. 공공데이터 연동

- 공공데이터포털 API → 파이프라인 → fire 스키마
- V-World WFS → 건물/행정경계 데이터
- Kakao Maps API → 지오코딩

---

## 우선순위와 의존성

```
Phase 1: GIS 범용 기반        ← 현재 착수
  │
  ├── Phase 2: AI Text-to-SQL  (Phase 1과 병렬 가능)
  │
  ├── Phase 3: 대시보드 실시간   (Phase 1 이후)
  │
  ├── Phase 4: 데이터 내보내기   (독립, 언제든 가능)
  │
  └── Phase 5: 소방 도메인 특화  (Phase 1~3 이후)
```

| Phase | 의존성 | 예상 규모 |
|-------|--------|---------|
| **Phase 1** | PostGIS (완료) | Backend 10+, Frontend 8+, AI 3+ 파일 |
| **Phase 2** | 없음 (병렬 가능) | AI Agent 5+, Backend 2+ 파일 |
| **Phase 3** | Phase 1 (지도 위젯) | Frontend 5+, Backend 2+ 파일 |
| **Phase 4** | 없음 (독립) | Backend 3+, Frontend 3+ 파일 |
| **Phase 5** | Phase 1, 2, 3 | 전체 3개 앱 대규모 |

---

## 기술 스택 기반 현황

### 이미 완료 (이번 스프린트)
- [x] Docker: `postgis/postgis:16-3.5`
- [x] Flyway: V29 PostGIS 확장 활성화
- [x] Flyway: V30 fire 스키마 + 7개 테이블
- [x] jOOQ: 멀티스키마 코드젠 (public + fire)
- [x] jOOQ: PostgisGeometryBinding 커스텀 바인딩
- [x] Java: GeoJsonUtil (GeoJSON ↔ JTS Geometry)
- [x] 테스트: PoC 통합 테스트 8개 + 단위 테스트 10개

### Phase 1에서 추가할 것
- [ ] Backend: DataTableService GEOMETRY 타입 지원
- [ ] Backend: 공간 쿼리 API (nearby, bbox, geojson)
- [ ] Frontend: MapLibre GL JS 맵 컴포넌트
- [ ] Frontend: MAP 차트 타입 + 대시보드 위젯
- [ ] DB: V31 chart_type MAP 추가
- [ ] AI: 공간 쿼리 MCP 도구 3종

---

## 참고 문서

- `docs/research/gis-deep-analysis.md` — GIS 심층 분석 (경쟁사, 사례, 유저스토리, 공공데이터)
- `docs/research/gis-spatial-research.md` — 기술 스택 비교 (MapLibre, PostGIS, jOOQ)
- `docs/planning/fire-foundation-sprint.md` — 소방 기반 스프린트 계획 (fire 스키마 상세)
- `docs/planning/fire-domain-roadmap.md` — 소방 도메인 장기 로드맵
