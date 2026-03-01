# Smart Fire Hub — ROADMAP

> **최종 수정**: 2026-03-01
> **비전**: AI-First 소방 전문 데이터 플랫폼
> **전략**: 기초 기술 → 범용 플랫폼 → 도메인 특화 순서로 확장
> **원칙**: 각 Phase는 독립적으로 가치를 제공하며, 이전 Phase가 완료되어야 다음으로 진행

---

## 진행 현황 요약

| Phase | 상태 | 진행률 | 설명 |
|-------|------|--------|------|
| [Phase 0: 기반 정비](#phase-0-기반-정비) | **완료** | 100% | 보안 강화, 데이터셋 UI/UX, 코드 품질 |
| [Phase 1: GIS 범용 기반](#phase-1-gis-범용-기반) | **진행 중** | 30% | PostGIS + MapLibre + 공간 쿼리 API |
| [Phase 2: AI Text-to-SQL](#phase-2-ai-text-to-sql) | 대기 | 0% | 자연어 → SQL 생성 → 실행 → 차트 추천 |
| [Phase 3: 대시보드 실시간 갱신](#phase-3-대시보드-실시간-갱신) | 대기 | 0% | 위젯별 자동 갱신, SSE 알림 |
| [Phase 4: 데이터 내보내기](#phase-4-데이터-내보내기) | 대기 | 0% | CSV/Excel/GeoJSON 다운로드 |
| [Phase 5: 소방 도메인 특화](#phase-5-소방-도메인-특화) | 대기 | 0% | 소방 CRUD, 대시보드, 공공데이터 |

---

## Phase 0: 기반 정비

> **상태**: 완료
> **목표**: 범용 데이터 플랫폼의 안정성과 보안을 확보한다.

### 0A. 보안 강화

- [x] JWT 시크릿 키 환경변수화
- [x] CORS 설정
- [x] 로그인 brute-force 방어 (5회 실패 → 15분 차단)
- [x] Security 헤더 (X-Content-Type-Options, HSTS, X-Frame-Options)
- [x] GlobalExceptionHandler catch-all (스택트레이스 노출 방지)
- [x] Refresh token rotation (family_id 기반)

### 0B. 코드 품질

- [x] P1~P3 코드 리뷰 항목 수정
- [x] 토큰 갱신 isActive, 시스템 역할 보호, LIKE 이스케이프
- [x] ErrorResponse 생성자 불일치 수정

### 0C. GIS 인프라 (PostGIS + jOOQ 바인딩)

- [x] Docker 이미지 교체: `postgres:16` → `postgis/postgis:16-3.5`
- [x] Flyway V29: PostGIS 확장 활성화 (postgis, postgis_topology)
- [x] Flyway V30: `fire` 스키마 + 7개 테이블 (organization, district, incident_type, incident, incident_type_mapping, dispatch, hydrant)
- [x] jOOQ 멀티스키마 코드젠 (public + fire)
- [x] `PostgisGeometryBinding` / `PostgisGeometryConverter` 커스텀 바인딩
- [x] `GeoJsonUtil` (GeoJSON ↔ JTS Geometry 변환)
- [x] PoC 통합 테스트 8개 + 단위 테스트 10개

---

## Phase 1: GIS 범용 기반

> **상태**: 진행 중
> **목표**: 데이터 플랫폼의 범용 기능으로 공간 데이터(GEOMETRY)를 지원한다.
> **의존**: Phase 0C 완료 (PostGIS 인프라)

### 1A. Backend — GEOMETRY 타입 지원

- [ ] `DataTableService.mapDataType()`에 `"GEOMETRY"` 케이스 추가
- [ ] `DataTableService.createTable()` — GEOMETRY 컬럼에 GIST 인덱스 자동 생성
- [ ] `DataTableService.setColumnIndex()` — GEOMETRY 컬럼 → GIST 인덱스 분기
- [ ] `DatasetService.VALID_DATA_TYPES`에 `"GEOMETRY"` 추가
- [ ] `DataTableRowService.insertBatch()` — GeoJSON 입력 → `ST_GeomFromGeoJSON()` 변환
- [ ] `DataTableRowService.queryData()` — GEOMETRY 컬럼 → `ST_AsGeoJSON()` 래핑 출력
- [ ] `DataTableQueryService.executeQuery()` — GEOMETRY 결과를 GeoJSON으로 변환
- [ ] TC: GEOMETRY CRUD 테스트 5개

### 1B. Backend — 공간 쿼리 API

- [ ] `GET /api/v1/datasets/{id}/data/nearby` — 반경 검색 (ST_DWithin)
- [ ] `GET /api/v1/datasets/{id}/data/bbox` — 바운딩박스 검색
- [ ] `GET /api/v1/datasets/{id}/data/geojson` — GeoJSON FeatureCollection 응답
- [ ] CSV 업로드 시 위도/경도 컬럼 → GEOMETRY 자동 변환 옵션
- [ ] TC: 공간 쿼리 테스트 5개

### 1C. Frontend — 지도 시각화 컴포넌트

- [ ] MapLibre GL JS 설치 + Vite 청크 분리 (lazy load)
- [ ] `MapView.tsx` — 범용 지도 컴포넌트 (OSM 배경지도)
- [ ] `MapControls.tsx` — 줌, 내비게이션, 전체화면
- [ ] `GeoJsonLayer.tsx` — GeoJSON 데이터 → 마커/폴리곤 렌더링
- [ ] 마커 클릭 시 팝업으로 행 데이터 표시

### 1D. Frontend — MAP 차트 타입

- [ ] `ChartType`에 `'MAP'` 추가 (`types/analytics.ts`)
- [ ] `ChartTypeSelector.tsx` — 지도 아이콘 + 라벨
- [ ] `ChartRenderer.tsx` — `case 'MAP': return <MapChartView />`
- [ ] `ChartConfig` — `latitudeColumn`, `longitudeColumn`, `geometryColumn` 필드
- [ ] `ColumnTypeSelect.tsx` — GEOMETRY 타입 옵션
- [ ] `validations/dataset.ts` — Zod 스키마에 GEOMETRY 추가
- [ ] Flyway V31: chart_type CHECK 제약에 `'MAP'` 추가

### 1E. AI 에이전트 — 공간 쿼리 도구

- [ ] `query_nearby_data` MCP 도구 — 좌표 반경 내 데이터 검색
- [ ] `query_bbox_data` MCP 도구 — 바운딩박스 영역 내 검색
- [ ] `get_geojson` MCP 도구 — GeoJSON FeatureCollection 반환
- [ ] TC: MCP 도구 테스트 3개

---

## Phase 2: AI Text-to-SQL

> **상태**: 대기
> **목표**: 비개발자가 자연어로 데이터를 조회/분석할 수 있다.
> **의존**: 없음 (Phase 1과 병렬 가능)

### 2A. 스키마 컨텍스트 제공

- [ ] AI에게 `data` 스키마의 테이블/컬럼 정보 자동 제공
- [ ] 데이터셋 메타데이터 (이름, 설명, 컬럼 타입, 샘플 값) → 시스템 프롬프트 주입
- [ ] `search_path = 'data'` 제약 준수

### 2B. SQL 생성 + 실행

- [ ] `get_dataset_schema` MCP 도구 — 컬럼 정보 + 샘플 5행
- [ ] `generate_and_execute_sql` MCP 도구 — 자연어 → SQL → 실행 → 결과
- [ ] `suggest_chart` MCP 도구 — 결과 기반 차트 타입 + 설정 추천
- [ ] DDL/DML(INSERT/UPDATE/DELETE) 시도 시 거부
- [ ] GEOMETRY 컬럼 포함 쿼리 → MAP 차트 자동 추천
- [ ] TC: Text-to-SQL 시나리오 10개

---

## Phase 3: 대시보드 실시간 갱신

> **상태**: 대기
> **목표**: 운영 모니터링이 가능한 라이브 대시보드.
> **의존**: Phase 1 (지도 위젯)

### 3A. 자동 갱신 메커니즘

- [ ] TanStack Query `refetchInterval` 활용 (위젯별 갱신 주기 설정)
- [ ] 대시보드 설정에 갱신 주기 옵션: 없음 / 10초 / 30초 / 1분 / 5분
- [ ] 갱신 중 로딩 인디케이터 (데이터 유지)

### 3B. SSE 기반 실시간 알림

- [ ] 파이프라인 실행 완료/실패 시 대시보드 토스트 알림
- [ ] 기존 `AsyncJobService` SseEmitter 패턴 활용
- [ ] 데이터셋 변경 이벤트 → 관련 차트 자동 갱신

---

## Phase 4: 데이터 내보내기

> **상태**: 대기
> **목표**: 분석 결과를 외부로 가져갈 수 있다.
> **의존**: 없음 (독립적, 언제든 착수 가능)

### 4A. 내보내기 API

- [ ] 데이터셋 → CSV 스트리밍 다운로드 (대용량 지원)
- [ ] 데이터셋 → Excel (XLSX) 다운로드
- [ ] GEOMETRY 데이터셋 → GeoJSON 다운로드
- [ ] 쿼리 결과 → CSV/Excel 다운로드

### 4B. 프론트엔드 UI

- [ ] 데이터셋 목록/상세에 "내보내기" 버튼
- [ ] 쿼리 결과 테이블에 "다운로드" 버튼
- [ ] 포맷 선택 드롭다운 (CSV / Excel / GeoJSON)
- [ ] 대용량 내보내기 → 백그라운드 작업 + 완료 알림

---

## Phase 5: 소방 도메인 특화

> **상태**: 대기
> **목표**: Phase 1~4의 범용 플랫폼 위에 소방 전문 기능을 올린다.
> **의존**: Phase 1 (GIS), Phase 2 (Text-to-SQL), Phase 3 (대시보드)

### 5A. 소방 도메인 CRUD API

- [ ] 소방 조직 API (`/api/v1/fire/organizations`) — 계층 CRUD + GeoJSON
- [ ] 사건 API (`/api/v1/fire/incidents`) — 기간/유형/상태 필터
- [ ] 소방용수시설 API (`/api/v1/fire/hydrants`) — CRUD + 반경 검색
- [ ] 출동 API (`/api/v1/fire/incidents/{id}/dispatches`)
- [ ] 소방 권한 코드 추가 (fire_org:read/write, fire_incident:read/write 등)
- [ ] 시드 데이터 생성기 (개발/테스트용 합성 데이터)
- [ ] TC: API 테스트 최소 20개

### 5B. 소방 대시보드 & KPI

- [ ] 소방 KPI 카드 (응답시간 평균/90th, 출동 건수, 사건 유형 분포)
- [ ] 응답시간 추이 차트 (월별, 소방서별)
- [ ] 사건 분석 (지역별/시간대별/유형별)
- [ ] 소방서별 성과 비교
- [ ] 출동 타임라인 뷰

### 5C. 소방 전용 지도

- [ ] V-World 배경지도 연동 (MapLibre + V-World WMTS)
- [ ] 소방서/소화전/사건 전용 레이어
- [ ] 관할구역 경계 시각화
- [ ] 사건 히트맵 (deck.gl HeatmapLayer)
- [ ] 소방용수 커버리지 분석

### 5D. AI 소방 분석

- [ ] 소방 MCP 도구 (`list_fire_incidents`, `search_nearby_hydrants` 등)
- [ ] 소방 특화 시스템 프롬프트 (용어, KPI 정의, 한국 소방 체계)
- [ ] Text-to-SQL 소방 특화 (fire 스키마 인식)
- [ ] AI 월간 보고서 자동 생성

### 5E. 공공데이터 연동

- [ ] 공공데이터포털 소방 데이터 ETL (소방용수시설, 소방서 좌표)
- [ ] V-World WFS 행정경계/건물통합정보
- [ ] 건축물대장 API 연동
- [ ] Kakao Maps API 지오코딩

---

## 백로그 (Backlog)

> 우선순위 미정. 아이디어 수집 및 향후 Phase에 편입 검토.
> `P:높음` / `P:보통` / `P:낮음`으로 우선순위 표기.

### 플랫폼 기능

| # | 아이디어 | 우선순위 | 관련 Phase | 메모 |
|---|---------|---------|-----------|------|
| BL-01 | 알림 시스템 (인앱 + Webhook) | P:높음 | Phase 3+ | PostgreSQL LISTEN/NOTIFY + SSE. Slack/Teams 연동. |
| BL-02 | 데이터 품질 규칙 엔진 | P:보통 | Phase 5+ | NOT_NULL/UNIQUE/RANGE/REGEX/CUSTOM_SQL 규칙. 소방 데이터 검증 프리셋. |
| BL-03 | 데이터 리니지 | P:보통 | 장기 | 파이프라인 input/output 관계 자동 기록. @xyflow 재활용. |
| BL-04 | 파이프라인 조건부 분기 | P:보통 | Phase 1~2 | IF/ELSE 스텝. 이전 스텝 결과에 따른 분기 실행. |
| BL-05 | 멀티테넌시 (RLS) | P:낮음 | 장기 | 소방서/소방본부별 데이터 격리. fire 스키마에 organization_id FK 이미 존재. |
| BL-06 | 데이터셋 버전 관리 | P:낮음 | 장기 | SNAPSHOT/APPEND 기반 버전. 변경 이력 추적. |
| BL-07 | 협업 기능 (코멘트, @mention) | P:낮음 | 장기 | 데이터셋/파이프라인에 코멘트. 워크스페이스. |
| BL-08 | 정기 보고서 자동화 (PDF) | P:보통 | Phase 5+ | 월간/분기 보고서 생성. 크론 트리거 연동. |
| BL-09 | data 스키마 GEOMETRY 이외 공간 타입 | P:낮음 | Phase 1+ | LineString, Polygon, MultiPolygon 등 다양한 Geometry 서브타입 지원. |
| BL-10 | 지도 타일 캐싱 + 오프라인 | P:낮음 | 장기 | PWA + MapLibre 타일 캐싱. 현장 오프라인 사용. |

### AI/분석

| # | 아이디어 | 우선순위 | 관련 Phase | 메모 |
|---|---------|---------|-----------|------|
| BL-11 | AI 이상 탐지 알림 | P:높음 | Phase 3+ | 데이터 패턴 이상 감지 → 자동 알림. 예: 특정 지역 출동 급증. |
| BL-12 | AI 차트/대시보드 자동 생성 | P:보통 | Phase 2+ | "이 데이터로 대시보드 만들어줘" → 위젯 자동 구성. |
| BL-13 | AI 소방용수 최적 배치 제안 | P:보통 | Phase 5 | 커버리지 갭 분석 + 신규 설치 위치 추천. Voronoi 분석. |
| BL-14 | AI 화재 위험도 분석 | P:보통 | Phase 5 | 건물 노후도 + 인구밀도 + 화재 이력 → 위험 점수. H3 헥사곤 기반. |
| BL-15 | What-if 소방서 배치 시뮬레이션 | P:낮음 | Phase 5+ | 가상 소방서 위치 → 이소크론 분석 → 커버리지 변화 시뮬레이션. |

### 소방 도메인 확장

| # | 아이디어 | 우선순위 | 관련 Phase | 메모 |
|---|---------|---------|-----------|------|
| BL-16 | 장비/차량 관리 | P:보통 | Phase 5+ | 차량 CRUD, 정비 이력, 상태 추적. |
| BL-17 | 인력/교육 관리 | P:보통 | Phase 5+ | 소방관 정보, 자격증, 교육 이력. |
| BL-18 | 건물 검사/예방 관리 | P:낮음 | 장기 | 검사 일정, 위반사항, 시정조치 추적. |
| BL-19 | 실시간 유닛 추적 (GPS) | P:낮음 | 장기 | Redis Streams + WebSocket. 출동 차량 실시간 위치. |
| BL-20 | NERIS/NFPA 호환 | P:낮음 | 장기 | 글로벌 확장 시 미국 소방 데이터 표준 지원. |
| BL-21 | 시민 공개 대시보드 | P:낮음 | Phase 5+ | 화재 통계, 소방 안전 정보 공개. 개인정보 제거. |

### 인프라/기술 부채

| # | 아이디어 | 우선순위 | 관련 Phase | 메모 |
|---|---------|---------|-----------|------|
| BL-22 | 실시간 데이터 처리 (Redis Streams) | P:낮음 | 장기 | 현재 PostgreSQL LISTEN/NOTIFY로 충분. 규모 커지면 검토. |
| BL-23 | 프론트엔드 테스트 프레임워크 | P:보통 | - | firehub-web에 테스트 프레임워크 미설정. Vitest + Testing Library 도입. |
| BL-24 | CI/CD 파이프라인 | P:보통 | - | GitHub Actions로 빌드/테스트/배포 자동화. |
| BL-25 | 모바일 반응형 + PWA | P:낮음 | 장기 | 현장 소방관용 모바일 UI. |

---

## 기술 스택

### 현재 (변경 없음)

| 영역 | 기술 |
|------|------|
| Backend | Spring Boot 3.4 + Java 21, jOOQ, Flyway, Spring Security + JWT |
| Frontend | Vite + React 19 + TypeScript, TanStack Query, React Router v7, shadcn/ui, Tailwind CSS v4 |
| AI Agent | Node.js + TypeScript, Express 4, Claude Agent SDK, MCP 도구 36종 |
| Database | PostgreSQL 16 + **PostGIS 3.5** (Docker), public/data/fire 3스키마 |
| Monorepo | pnpm workspaces + Turborepo |

### Phase별 추가 기술

| 기술 | Phase | 용도 |
|------|-------|------|
| MapLibre GL JS | Phase 1 | 프론트엔드 맵 렌더링 |
| deck.gl | Phase 5C | 대규모 데이터 시각화 (히트맵) |
| Turf.js | Phase 1 | 클라이언트 공간 분석 |
| H3 (h3-js) | Phase 5C | 헥사곤 공간 인덱싱 |

### 도입하지 않는 기술

| 기술 | 이유 |
|------|------|
| Kafka/Flink/Pulsar | 현재 규모에서 과잉. PostgreSQL LISTEN/NOTIFY + SSE로 충분 |
| Metabase/Superset | UI 이질감. 커스텀 대시보드가 적합 |
| dbt | 기존 파이프라인 엔진과 기능 중복 |
| Redis (당분간) | PostgreSQL로 충분. BL-22에서 재검토 |

---

## 참고 문서

| 문서 | 역할 |
|------|------|
| `docs/research/gis-deep-analysis.md` | GIS 심층 분석 (경쟁사, 도입 사례, 유저스토리, 공공데이터) |
| `docs/research/gis-spatial-research.md` | GIS 기술 스택 비교 (MapLibre, PostGIS, jOOQ) |
| `docs/planning/fire-foundation-sprint.md` | 소방 기반 스프린트 상세 계획 (fire 스키마) |
| `docs/planning/fire-domain-roadmap.md` | 소방 도메인 장기 로드맵 (Phase A~7 원본) |
| `docs/planning/data-platform-roadmap.md` | 데이터 플랫폼 로드맵 (Phase 1~5 상세) |

---

## 변경 이력

| 날짜 | 변경 내용 |
|------|---------|
| 2026-03-01 | 초안 작성. Phase 0~5 + 백로그 25건 정리. data-platform-roadmap.md와 fire-domain-roadmap.md를 통합. |
