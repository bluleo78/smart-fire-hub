# Smart Fire Hub — 소방 전문 데이터 플랫폼 로드맵

> **결정일**: 2026-02-28
> **플랫폼 정체성**: 소방 전문 데이터 플랫폼 (AI-First)
> **타겟 시장**: 한국 시장 우선 (소방서, 소방본부, 소방청)
> **협력 현황**: 소방서/소방본부 협력 관계 확보
> **전략**: 기존 UI/UX 개선 완료 → GIS 기술 기반 → 소방 도메인 기능 순차 구축

---

## 전략적 배경

### 왜 소방 전문 플랫폼인가?

1. **시장 공백**: 한국에 소방 전문 데이터 플랫폼이 부재. 소방안전 빅데이터 플랫폼(소방청)은 단순 데이터 공개에 그침
2. **AI-First 차별화**: ImageTrend/ESO 등 해외 경쟁사는 기존 RMS에 AI를 뒤늦게 추가하는 반면, Smart Fire Hub는 설계부터 AI-First
3. **기존 자산 활용**: 범용 데이터 플랫폼 기능(데이터셋, ETL, AI 에이전트)이 95% 완성 — 이 위에 소방 레이어를 추가
4. **협력 관계**: 실제 소방서/소방본부 협력 확보 → 실사용 피드백과 데이터 검증 가능

### 기존 로드맵과의 관계

기존 `platform-comparison-roadmap.md`의 범용 로드맵(Phase 0-7)을 **소방 도메인 관점에서 재구성**합니다:

| 기존 Phase | 재구성 |
|-----------|--------|
| Phase 0: 보안 강화 | → **Phase B**에 통합 (소방 도메인 구축 전 기반 작업) |
| Phase 1: 데이터 리니지 | → **Phase 6**으로 이동 (소방 데이터 흐름 추적 맥락) |
| Phase 2: 알림 시스템 | → **Phase 5**에 통합 (소방 이벤트 기반 알림) |
| Phase 3: 데이터 품질 | → **Phase 5**에 통합 (소방 데이터 검증 규칙) |
| Phase 4: 고급 대시보드 | → **Phase 3**에 통합 (소방 KPI 대시보드) |
| Phase 5: AI 강화 | → **Phase 4**에 통합 (AI 소방 도구) |
| Phase 6: 협업/거버넌스 | → **Phase 7**으로 연기 |
| Phase 7: 장기 진화 | → 일부 **Phase 6**(실시간), 나머지 연기 |

### 연구 자료 참조

이 로드맵은 다음 4개 심층 연구를 기반으로 합니다:
- `docs/fire-data-platform-research.md` — 소방 플랫폼 12종 분석, 데이터 표준, 한국 소방 체계
- `docs/GIS_RESEARCH.md` — GIS 기술 스택, 소방 GIS 활용 사례, 한국 공간 인프라
- `docs/research-realtime-analytics-2026.md` — 실시간 처리, BI, 데이터 관측성
- `docs/data-platform-deep-research.md` — Databricks/Snowflake/Palantir 심층, 20개 플랫폼 비교

---

## 로드맵 요약

```
Phase A (진행 중)     데이터셋 UI/UX 개선 완료
                      ↓
Phase B (1~2일)       보안/안정성 기반 강화
                      ↓
Phase 1 (2~3주)       GIS 기술 기반 구축
                      PostGIS + MapLibre + GeoJSON API
                      ↓
Phase 2 (2~3주)       소방 도메인 데이터 모델
                      사건, 소방시설, 관할구역, 장비, 인력
                      ↓
Phase 3 (3~4주)       사건/출동 분석 & 소방 대시보드
                      응답시간 KPI, 사건 통계, 소방 차트
                      ↓
Phase 4 (2~3주)       AI 소방 도구 & 분석 강화
                      소방 MCP 도구, Text-to-SQL 소방 특화
                      ↓
Phase 5 (3~4주)       알림 시스템 & 데이터 품질
                      소방 이벤트 알림, 소방 데이터 검증 규칙
                      ↓
Phase 6 (3~4주)       공공데이터 연동 & 고급 GIS
                      소방안전 빅데이터, 관할구역 맵, 히트맵
                      ↓
Phase 7 (장기)        리니지, 실시간, 협업, 보고서 자동화
```

---

## Phase A: 데이터셋 UI/UX 개선 완료 (현재 진행 중)

> 기존 계획 파일: `.omc/plans/dataset-ui-ux-improvement.md` (v3 Final)
> Phase 0-6, 22개 항목

**이 Phase를 먼저 완료합니다.** 범용 데이터 관리 기능이 안정적이어야 그 위에 소방 도메인 기능을 구축할 수 있습니다.

잔여 작업:
- 기존 계획의 미완료 항목 확인 및 마무리
- 데이터 그리드 고도화, 프로파일링, 검색/탐색 UX
- 완료 후 소방 도메인 Phase로 전환

---

## Phase B: 보안/안정성 기반 강화 (1~2일)

> 기존 Phase 0에서 이관. 소방 데이터는 민감 정보를 포함하므로 보안이 선행되어야 함.

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| B-1 | JWT 시크릿 키 환경변수화 | S | `application-local.yml` 하드코딩 제거 → `JWT_SECRET` 환경변수 |
| B-2 | CORS 설정 추가 | S | `SecurityConfig`에 허용 origin 설정 |
| B-3 | 로그인 brute-force 방어 | M | 5회 실패 → 15분 차단 |
| B-4 | Security 헤더 추가 | S | X-Content-Type-Options, HSTS, X-Frame-Options |
| B-5 | GlobalExceptionHandler catch-all | S | 500 에러 시 스택트레이스 노출 방지 |

**수용 기준**: 모든 보안 항목 구현 + TC 통과

---

## Phase 1: GIS 기술 기반 구축 (2~3주)

> PostGIS + MapLibre + GeoJSON API — 모든 소방 GIS 기능의 기반

### 기술 결정 (연구 기반)

| 영역 | 선택 | 근거 |
|------|------|------|
| 공간 DB | **PostGIS 3.5** (postgis/postgis:16-3.5) | 기존 PostgreSQL 16에 확장만 추가. 완전 호환 |
| 프론트엔드 맵 | **MapLibre GL JS v5** + **react-map-gl v8** | BSD-3 오픈소스, Mapbox 무료 대안, V-World 타일 연동 |
| 공간 분석 | **Turf.js** (클라이언트) + PostGIS (서버) | 브라우저/서버 이중 분석 |
| 지오코딩 | **Kakao Maps API** (한국 주소) | 무료 300K건/일, 한국 주소 최적화 |
| 데이터 포맷 | **GeoJSON** (API) + **WKB** (DB) | 표준 호환 |
| 좌표계 | **WGS84 (EPSG:4326)** 기본 + 한국 좌표계 변환 | PostGIS ST_Transform으로 EPSG:5179/5186 변환 |

### 구현 항목

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 1-1 | **PostGIS 도입** | M | Docker 이미지 교체(`postgres:16` → `postgis/postgis:16-3.5`), Flyway 마이그레이션으로 PostGIS 확장 활성화, docker-compose 수정 |
| 1-2 | **jOOQ PostGIS 바인딩** | M | Geometry 타입 커스텀 바인딩, GeoJSON ↔ Geometry 변환 유틸, jOOQ 코드젠 설정 |
| 1-3 | **공간 데이터 API 기반** | M | GeoJSON 응답 포맷, 공간 쿼리 API (ST_Within, ST_Distance, ST_Buffer), Feature/FeatureCollection DTO |
| 1-4 | **MapLibre 맵 컴포넌트** | L | React 맵 컴포넌트 (react-map-gl v8), V-World/OpenStreetMap 배경지도 타일, 기본 컨트롤 (줌, 내비게이션, 전체화면) |
| 1-5 | **맵 레이어 시스템** | M | 포인트/폴리곤/라인 레이어, 클러스터링, 팝업/툴팁, 레이어 토글 |
| 1-6 | **Turf.js 클라이언트 분석** | S | 거리 계산, 버퍼 생성, 포함 판별 유틸 |

**수용 기준**:
- PostGIS 공간 쿼리가 정상 동작 (ST_Within, ST_Distance 등)
- MapLibre 맵에 V-World 배경지도 표시
- GeoJSON API로 포인트/폴리곤 데이터 CRUD 가능
- TC: 공간 쿼리 API 테스트 10개 이상

**재활용 자산**: 없음 (완전 신규). 단, 기존 `DataTableService` 패턴 참조

---

## Phase 2: 소방 도메인 데이터 모델 (2~3주)

> 소방 핵심 데이터를 구조화. Palantir Ontology 패턴 참조 — 도메인 객체 중심 설계

### 한국 소방 데이터 체계 참조

```
소방청
 ├── 소방본부 (시도)
 │    ├── 소방서
 │    │    ├── 119안전센터 (출동 단위)
 │    │    ├── 구조대
 │    │    └── 구급대
 │    └── 소방학교
 └── 중앙소방학교
```

### 핵심 도메인 모델

#### 2-A. 소방 조직 & 관할구역

| 테이블 | 주요 컬럼 | 비고 |
|--------|-----------|------|
| `fire_organization` | id, name, type(본부/서/센터), parent_id, address, coordinates(POINT) | 계층 구조 |
| `fire_district` | id, org_id, name, boundary(POLYGON), area_km2 | 관할구역 경계 |

#### 2-B. 사건/출동

| 테이블 | 주요 컬럼 | 비고 |
|--------|-----------|------|
| `incident` | id, incident_number, type(화재/구조/구급/기타), severity, location(POINT), address, reported_at, closed_at | 사건 기본 |
| `incident_detail` | id, incident_id, cause, damage_area_m2, casualties, property_damage_krw | 사건 상세 |
| `dispatch` | id, incident_id, unit_id, dispatched_at, arrived_at, cleared_at | 출동 기록 |
| `dispatch_personnel` | dispatch_id, personnel_id, role | 출동 인력 |

#### 2-C. 소방시설

| 테이블 | 주요 컬럼 | 비고 |
|--------|-----------|------|
| `fire_hydrant` | id, district_id, type(지상/지하/비상), location(POINT), capacity_lpm, status, last_inspected_at | 소방용수시설 |
| `fire_station` | id, org_id, location(POINT), address, built_year, apparatus_count | 소방서/센터 건물 |

#### 2-D. 장비/차량

| 테이블 | 주요 컬럼 | 비고 |
|--------|-----------|------|
| `apparatus` | id, station_id, type(펌프차/물탱크차/구급차/사다리차...), model, year, status | 차량/장비 |
| `apparatus_maintenance` | id, apparatus_id, type, date, cost_krw, description | 정비 이력 |

#### 2-E. 인력

| 테이블 | 주요 컬럼 | 비고 |
|--------|-----------|------|
| `personnel` | id, org_id, name, rank, position, hire_date, certifications(JSONB) | 소방관 기본 |
| `training_record` | id, personnel_id, type, date, hours, result | 교육/훈련 이력 |

### 구현 항목

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 2-1 | **소방 도메인 스키마 설계** | L | 위 테이블들의 Flyway 마이그레이션. `public` 스키마에 추가. 인덱스 전략 (공간 인덱스 GIST) |
| 2-2 | **jOOQ 코드젠 업데이트** | S | 새 테이블에 대한 jOOQ 코드 생성 |
| 2-3 | **소방 조직 CRUD API** | M | 조직 계층 CRUD + 관할구역 CRUD. GeoJSON 경계 입력/출력 |
| 2-4 | **사건/출동 CRUD API** | L | 사건 생성/조회/수정, 출동 기록 관리, 필터링(기간/유형/심각도/지역) |
| 2-5 | **소방시설 CRUD API** | M | 소방용수시설 CRUD + 공간 조회 (반경 내 소화전 검색) |
| 2-6 | **장비/인력 CRUD API** | M | 차량/장비 CRUD, 인력 CRUD, 교육 이력 |
| 2-7 | **소방 도메인 UI 네비게이션** | M | LNB에 소방 메뉴 추가 (사건, 시설, 장비, 인력). 아이콘 + 라우팅 |
| 2-8 | **소방 조직/시설 관리 UI** | M | 조직 트리뷰, 시설 목록/상세, CRUD 폼 |
| 2-9 | **사건/출동 관리 UI** | L | 사건 목록 (필터, 정렬, 페이지네이션), 사건 상세 뷰 (출동 타임라인), 사건 등록 폼 |
| 2-10 | **AI 에이전트 소방 도구** | M | `list_incidents`, `get_incident_detail`, `list_fire_hydrants`, `search_nearby_hydrants` MCP 도구 |

**수용 기준**:
- 소방 도메인 CRUD API 전체 동작
- 사건 등록 → 출동 기록 → 조회까지 End-to-End 동작
- 공간 쿼리: 특정 위치 반경 N km 내 소화전 검색 동작
- TC: API별 정상/예외 테스트. 최소 40개
- AI 에이전트로 "최근 화재 사건 목록 보여줘" 동작

**재활용 자산**: 기존 데이터셋 CRUD 패턴 (`controller/service/repository` 구조), `@RequirePermission` 보안, `DataTableService` 참조

---

## Phase 3: 사건/출동 분석 & 소방 대시보드 (3~4주)

> 소방서의 핵심 니즈 — 응답시간 KPI, 사건 추이, 운영 현황 시각화

### 소방 KPI 정의 (NFPA 1710 참조, 한국 소방 기준 적용)

| KPI | 정의 | 벤치마크 |
|-----|------|----------|
| **신고접수 시간** | 119 전화 → 출동 지령 | < 60초 |
| **출동 준비 시간 (Turnout)** | 출동 지령 → 출발 | < 80초 (주간), < 120초 (야간) |
| **주행 시간 (Travel)** | 출발 → 현장 도착 | < 4분 (도시), < 8분 (농촌) |
| **총 응답 시간** | 신고 → 현장 도착 | < 7분 (목표) |
| **현장 활동 시간** | 현장 도착 → 철수 | 사건 유형별 상이 |
| **출동 건수** | 기간별 출동 횟수 | 일/주/월/년 집계 |
| **사건 유형 분포** | 화재/구조/구급/기타 비율 | 파이차트 |

### 구현 항목

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 3-1 | **소방 대시보드 레이아웃** | M | 대시보드 페이지 신규 생성. 기존 범용 대시보드와 분리. 소방 KPI 카드 레이아웃 |
| 3-2 | **응답시간 KPI 카드** | M | 평균/중앙값/90th 백분위 응답시간 표시. 월별 추이. 목표 대비 달성률 |
| 3-3 | **출동 통계 차트** | M | shadcn/ui Charts (Recharts) 기반. 일별/월별 출동 건수 추이, 사건 유형 분포 파이차트 |
| 3-4 | **사건 분석 리포트** | L | 기간별 사건 요약 리포트. 지역별/시간대별/요일별 분석. 전년 대비 비교 |
| 3-5 | **소방서별 성과 비교** | M | 소방서/안전센터 간 KPI 비교표. 랭킹 + 추이 |
| 3-6 | **출동 타임라인 뷰** | M | 개별 사건의 출동 단계별 시간 시각화 (신고→지령→출동→도착→활동→철수) |
| 3-7 | **분석 API 엔드포인트** | L | 집계/통계 API: 기간별 KPI, 유형별 분포, 지역별 집계. SQL 기반 집계 쿼리 |
| 3-8 | **AI 분석 도구** | M | `get_response_time_stats`, `get_incident_trends`, `compare_stations` MCP 도구 |

**수용 기준**:
- 소방 대시보드에 최소 6개 KPI 카드 표시
- 차트가 실제 데이터 기반으로 렌더링 (데이터 없을 시 빈 상태 UI)
- 응답시간 90th 백분위 계산이 정확 (SQL 검증)
- AI로 "이번 달 화재 출동 통계 알려줘" 동작
- TC: 통계 API 테스트 15개 이상

**기술 선택**:
- 차트: shadcn/ui Charts (`npx shadcn add chart` — Recharts 기반). 이미 shadcn/ui 사용 중이므로 즉시 통합
- 대시보드: 커스텀 빌드 (Metabase/Superset 임베딩 불필요 — 연구 결론)

---

## Phase 4: AI 소방 도구 & 분석 강화 (2~3주)

> Smart Fire Hub의 최대 차별화 — AI-First 소방 분석

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 4-1 | **소방 특화 시스템 프롬프트** | M | 소방 도메인 용어, KPI 정의, 한국 소방 체계를 AI 에이전트 시스템 프롬프트에 반영 |
| 4-2 | **스키마 인식 Text-to-SQL** | M | 소방 도메인 테이블 스키마 + 샘플 데이터를 AI 컨텍스트로 제공. "지난달 강남소방서 화재 출동 건수" → SQL 자동 생성 |
| 4-3 | **대화형 사건 분석** | L | AI가 사건 데이터 분석 → 인사이트 도출 → 차트 생성 제안. "화재 패턴 분석해줘" |
| 4-4 | **AI 보고서 생성** | M | 월간/분기 운영 보고서 자동 생성. 마크다운 형식. KPI 요약 + 특이사항 + 개선 제안 |
| 4-5 | **소방용수 최적 배치 제안** | M | 소화전 분포 분석 → 커버리지 갭 식별 → 신규 설치 위치 제안 (Turf.js 보로노이 분석) |
| 4-6 | **위험도 분석 보조** | M | 화재 이력 + 건물 데이터 → 지역별 화재 위험 점수 산출 보조 |

**수용 기준**:
- AI에게 자연어로 소방 데이터 질의 가능
- AI가 소방 KPI를 이해하고 정확한 SQL 생성 (5개 시나리오 검증)
- 월간 보고서 자동 생성이 의미 있는 내용 포함
- TC: AI 도구 테스트 10개 이상

---

## Phase 5: 알림 시스템 & 데이터 품질 (3~4주)

> 기존 Phase 2(알림) + Phase 3(품질)을 소방 맥락으로 재구성

### 5-A. 알림 시스템

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 5A-1 | **알림 모델** | M | `notification` 테이블: type, title, message, read, user_id, metadata(JSONB) |
| 5A-2 | **인앱 알림 API + SSE 전달** | M | PostgreSQL LISTEN/NOTIFY → SSE 실시간 전달 (새 인프라 불필요) |
| 5A-3 | **알림 UI** | M | 헤더 벨 아이콘 + 드롭다운 + unread 카운트 |
| 5A-4 | **소방 이벤트 알림** | M | 파이프라인 완료/실패, 데이터 품질 위반, 장비 정비 일정, 자격증 만료 예정 |
| 5A-5 | **외부 알림 (Webhook)** | L | Slack/Teams webhook 연동 (선택적) |

### 5-B. 데이터 품질 (소방 특화)

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 5B-1 | **품질 규칙 모델** | M | `data_quality_rule` 테이블: dataset_id, column, rule_type(NOT_NULL/UNIQUE/RANGE/REGEX/CUSTOM_SQL), config(JSONB) |
| 5B-2 | **품질 규칙 실행 엔진** | L | 규칙 일괄 실행, 결과 저장. 파이프라인 스텝으로도 실행 가능 |
| 5B-3 | **소방 데이터 검증 규칙 프리셋** | M | 응답시간 범위(0~60분), 좌표 유효성(한국 영토 내), 사건 유형 코드, 필수 필드 |
| 5B-4 | **품질 대시보드** | M | 데이터셋별 품질 점수, 규칙 통과율, 실패 상세 |
| 5B-5 | **품질 위반 알림** | S | 5A-4와 연동. 품질 규칙 위반 시 자동 알림 |

**수용 기준**:
- 인앱 알림이 실시간 전달 (PostgreSQL LISTEN/NOTIFY + SSE)
- 소방 데이터 검증 규칙 5종 이상 프리셋 제공
- 파이프라인 실행 완료/실패 시 알림 생성 확인
- TC: 알림 API 10개, 품질 규칙 실행 10개

---

## Phase 6: 공공데이터 연동 & 고급 GIS (3~4주)

> 한국 소방 공공데이터 연동 + GIS 기능 고도화

### 6-A. 공공데이터 연동

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 6A-1 | **공공데이터포털 소방 데이터 ETL** | L | 소방서 좌표, 119안전센터, 소방용수시설 위치 데이터. 기존 API_CALL 파이프라인 스텝 활용 |
| 6A-2 | **V-World 공간 데이터 연동** | M | 소방서관할구역 경계(WFS), 배경지도 타일 |
| 6A-3 | **건축물대장 API 연동** | M | 건물 상세정보 + 좌표. 사전조사계획 기초 데이터 |
| 6A-4 | **국가화재정보시스템(NFDS) 데이터 매핑** | L | NFDS 화재/통계 데이터 구조 분석 + 매핑 |

### 6-B. 고급 GIS

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 6B-1 | **관할구역 맵** | M | 119 관할구역 경계 시각화, 소속 센터/소방서 표시, 클릭 시 상세 정보 |
| 6B-2 | **소방용수시설 맵** | M | 소화전 위치 마커, 유형/상태별 색상 코딩, 반경 검색, 클러스터링 |
| 6B-3 | **사건 히트맵** | M | deck.gl HeatmapLayer, 기간/유형 필터, 시계열 애니메이션 |
| 6B-4 | **H3 기반 위험도 맵** | L | H3 헥사곤 그리드, 화재 이력 기반 위험 점수, 색상 그라데이션 |
| 6B-5 | **Kakao Maps 지오코딩** | M | 한국 주소 → 좌표 변환, 주소 자동완성, 역지오코딩 |

**수용 기준**:
- 공공데이터포털에서 소방용수시설 데이터 자동 수집 파이프라인 동작
- V-World 관할구역 경계가 맵에 표시
- 사건 히트맵이 기간/유형별 필터링 동작
- TC: ETL 파이프라인 테스트, 공간 쿼리 테스트

---

## Phase 7: 장기 진화 (Ongoing)

> 우선순위에 따라 선택적으로 구현

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 7-1 | **데이터 리니지** | L | 파이프라인 실행 시 input/output 데이터셋 관계 자동 기록. 리니지 그래프 시각화 (@xyflow 재활용) |
| 7-2 | **실시간 데이터 처리** | XL | Redis Streams 도입 (필요 시). 실시간 센서 데이터, GPS 추적 |
| 7-3 | **보고서 자동화** | L | 정기 보고서 (월간/분기) 자동 생성 + PDF 출력 |
| 7-4 | **검사/예방 관리** | L | 건물 검사 일정, 위반사항 추적, 시정조치 관리 |
| 7-5 | **협업 기능** | M | 데이터셋 코멘트, @mention, 워크스페이스 |
| 7-6 | **데이터셋 버전 관리** | XL | 트랜잭션 기반 버전 관리 (SNAPSHOT/APPEND) |
| 7-7 | **Refresh token rotation** | M | 재사용 탐지 + 토큰 로테이션 |
| 7-8 | **멀티테넌시** | XL | 소방서/소방본부별 격리 |
| 7-9 | **모바일 대응** | L | 반응형 UI, PWA |
| 7-10 | **NERIS/NFPA 호환** | XL | 글로벌 확장 시 미국 표준 지원 |

---

## 기술 스택 변경 요약

### 추가되는 기술

| 기술 | Phase | 용도 |
|------|-------|------|
| **PostGIS 3.5** | Phase 1 | 공간 데이터 저장/쿼리 |
| **MapLibre GL JS v5** | Phase 1 | 프론트엔드 맵 렌더링 |
| **react-map-gl v8** | Phase 1 | React MapLibre 래퍼 |
| **deck.gl v9** | Phase 6 | 대규모 데이터 시각화 (히트맵) |
| **Turf.js** | Phase 1 | 클라이언트 공간 분석 |
| **H3 (h3-js)** | Phase 6 | 헥사곤 공간 인덱싱 |
| **shadcn/ui Charts** | Phase 3 | 차트/그래프 (Recharts 기반) |

### 변경 없음 (기존 스택 유지)

- Spring Boot 3.4 + Java 21
- jOOQ + Flyway
- React 19 + TypeScript + Vite
- shadcn/ui + Tailwind CSS v4
- Claude Agent SDK + MCP
- PostgreSQL 16 (PostGIS 확장만 추가)

### 도입하지 않는 기술 (연구 결론)

| 기술 | 이유 |
|------|------|
| Kafka/Flink/Pulsar | 현재 규모에서 과잉. PostgreSQL LISTEN/NOTIFY + SSE로 충분 |
| Metabase/Superset | 별도 서비스 운영 부담, UI 이질감. 커스텀 대시보드가 적합 |
| dbt | 기존 파이프라인 엔진과 기능 중복. 파이프라인 엔진 내재화 |
| Redis (당분간) | PostgreSQL로 충분. Phase 7에서 실시간 필요 시 검토 |

---

## 리스크 & 완화

| 리스크 | 영향 | 완화 |
|--------|------|------|
| 소방 데이터 구조 불확실성 | 도메인 모델 재설계 필요 | 협력 소방서와 실제 데이터 구조 사전 확인. 유연한 JSONB 메타데이터 활용 |
| PostGIS 학습 곡선 | 공간 쿼리 구현 지연 | Phase 1에서 기반만 구축. 고급 기능은 Phase 6에서 점진적 |
| 공공데이터 API 불안정 | ETL 파이프라인 실패 | 기존 API_CALL 스텝의 재시도/에러핸들링 활용. 로컬 캐시 |
| 소방 도메인 전문성 부족 | 잘못된 KPI/모델 설계 | 협력 소방서 현장 검증. 반복적 피드백 루프 |
| 맵 성능 (대량 마커) | UI 버벅임 | MapLibre 클러스터링 + deck.gl WebGL 렌더링. 서버사이드 공간 필터링 |

---

## 검증 전략

| Phase | 검증 방법 |
|-------|-----------|
| Phase B | 보안 테스트 (JWT 환경변수, CORS, brute-force 방어) |
| Phase 1 | PostGIS 공간 쿼리 TC, MapLibre 맵 렌더링 수동 확인 |
| Phase 2 | 소방 도메인 API TC (40개+), AI 에이전트 소방 도구 동작 |
| Phase 3 | KPI 계산 정확성 (SQL 검증), 차트 렌더링, AI 분석 동작 |
| Phase 4 | Text-to-SQL 시나리오 5개 검증, AI 보고서 품질 |
| Phase 5 | 알림 실시간 전달, 품질 규칙 실행 정확성 |
| Phase 6 | 공공데이터 ETL 자동화, GIS 맵 성능 (1만 포인트 이상) |

---

## 다음 단계

1. **즉시**: Phase A (데이터셋 UI/UX 개선) 잔여 작업 확인 및 마무리
2. **Phase A 완료 후**: Phase B (보안 강화) → Phase 1 (GIS 기반) 순차 진행
3. **Phase 2 착수 전**: 협력 소방서와 실제 데이터 구조/요구사항 확인 미팅 권장
