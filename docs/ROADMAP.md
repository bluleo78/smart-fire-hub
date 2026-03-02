# Smart Fire Hub — ROADMAP

> **최종 수정**: 2026-03-02
> **비전**: AI-First 소방 전문 데이터 플랫폼
> **전략**: 기초 기술 → 범용 플랫폼 → 도메인 특화 순서로 확장
> **원칙**: 각 아이템은 독립적으로 계획(Plan) → 구현 → 검증 가능한 작업 단위

---

## 진행 현황 요약

| Phase | 상태 | 진행률 | 설명 |
|-------|------|--------|------|
| [Phase 0](#phase-0-기반-정비) | **완료** | 100% | 보안, 코드 품질 |
| [Phase 1](#phase-1-gis-범용-기반) | **진행 중** | 2/6 | PostGIS 인프라 + GEOMETRY 지원 + 지도 + 공간 쿼리 |
| [Phase 2](#phase-2-ai-text-to-sql) | 대기 | 0/2 | 자연어 → SQL → 차트 추천 |
| [Phase 3](#phase-3-대시보드-실시간-갱신) | 대기 | 0/2 | 자동 갱신 + SSE 알림 |
| [Phase 4](#phase-4-데이터-내보내기) | 대기 | 0/2 | CSV/Excel/GeoJSON 다운로드 |
| [Phase 5](#phase-5-소방-도메인-특화) | 대기 | 0/5 | 소방 CRUD, 대시보드, 지도, AI, 공공데이터 |

---

## Phase 0: 기반 정비 ✅

> **완료** — 보안, 코드 품질

| # | 작업 | 상태 | 검증 |
|---|------|------|------|
| 0-1 | 보안 강화 (JWT 환경변수, CORS, brute-force, Security 헤더, Refresh token rotation) | ✅ | 기존 테스트 통과 |
| 0-2 | 코드 품질 (P1~P3 코드 리뷰, ErrorResponse 수정) | ✅ | 기존 테스트 통과 |

---

## Phase 1: GIS 범용 기반

> 데이터 플랫폼에 공간 데이터(GEOMETRY) 지원을 추가한다.
> **의존**: Phase 0 완료
>
> **실행 순서**: 1-0과 1-3은 병렬 시작. 1-4와 1-5는 1-2 완료 후 병렬 실행.
> ```
> Backend:   1-0 → 1-1 → 1-2 ──┬── 1-4 (Backend + Frontend)
> Frontend:  1-3 ───────────────┘    │
> AI Agent:                          └── 1-5 (병렬)
> ```

| # | 작업 | 상태 | 범위 | 의존 | 검증 기준 |
|---|------|------|------|------|----------|
| 1-0 | PostGIS 인프라 (Docker, Flyway, GEOMETRY CRUD) | ✅ | Backend | 없음 | PostGIS 확장 활성화 + GEOMETRY(Geometry,4326) 컬럼 CRUD + GeoJSON 입출력 + GiST 인덱스. 통합 테스트 20개 통과. |
| 1-1 | DataTableService GEOMETRY 타입 CRUD | ✅ | Backend | 1-0 | GEOMETRY 컬럼이 있는 테이블 생성/조회/삽입/수정이 동작한다. GeoJSON 입력 → DB 저장 → GeoJSON 출력 왕복. (1-0에서 함께 구현) |
| 1-2 | 공간 쿼리 API (nearby, bbox, geojson) | ⬜ | Backend | 1-1 | 좌표+반경 검색, 바운딩박스 검색, GeoJSON FeatureCollection 응답 API 3개 동작. |
| 1-3 | MapLibre 지도 컴포넌트 | ⬜ | Frontend | 없음 | OSM 배경지도 위에 GeoJSON 데이터를 마커/폴리곤으로 렌더링. 마커 클릭 팝업. |
| 1-4 | MAP 차트 타입 | ⬜ | Backend + Frontend | 1-2, 1-3 | 대시보드에서 MAP 차트 위젯을 생성하고 GEOMETRY 데이터를 지도에 표시할 수 있다. |
| 1-5 | 공간 쿼리 MCP 도구 | ⬜ | AI Agent | 1-2 | AI 채팅에서 "강남역 500m 이내 데이터 찾아줘" 같은 공간 쿼리 실행 가능. |

---

## Phase 2: AI Text-to-SQL

> 비개발자가 자연어로 데이터를 조회/분석할 수 있다.
> **의존**: 없음 (Phase 1과 병렬 가능)

| # | 작업 | 상태 | 범위 | 검증 기준 |
|---|------|------|------|----------|
| 2-1 | Text-to-SQL MCP 도구 (스키마 조회 + SQL 생성/실행) | ⬜ | AI Agent | "매출 상위 10개 보여줘" → SQL 자동 생성 → 실행 → 결과 반환. DDL/DML 거부. |
| 2-2 | 차트 자동 추천 | ⬜ | AI Agent + Frontend | SQL 결과 기반 차트 타입+설정 자동 추천. GEOMETRY 포함 시 MAP 추천. |

---

## Phase 3: 대시보드 실시간 갱신

> 운영 모니터링이 가능한 라이브 대시보드.
> **의존**: Phase 1 (지도 위젯)

| # | 작업 | 상태 | 범위 | 검증 기준 |
|---|------|------|------|----------|
| 3-1 | 대시보드 위젯 자동 갱신 | ⬜ | Frontend | 위젯별 갱신 주기 설정(10초~5분). 갱신 중 기존 데이터 유지 + 로딩 인디케이터. |
| 3-2 | SSE 이벤트 기반 알림 + 자동 갱신 | ⬜ | Backend + Frontend | 파이프라인 완료/실패 시 토스트 알림. 데이터셋 변경 → 관련 차트 자동 갱신. |

---

## Phase 4: 데이터 내보내기

> 분석 결과를 외부로 가져갈 수 있다.
> **의존**: 없음 (독립적)

| # | 작업 | 상태 | 범위 | 검증 기준 |
|---|------|------|------|----------|
| 4-1 | 내보내기 API (CSV/Excel/GeoJSON) | ⬜ | Backend | 데이터셋/쿼리 결과를 CSV(스트리밍), Excel, GeoJSON으로 다운로드. 대용량 지원. |
| 4-2 | 내보내기 UI | ⬜ | Frontend | 데이터셋/쿼리 결과에 "내보내기" 버튼 + 포맷 선택. 대용량은 백그라운드 작업. |

---

## Phase 5: 소방 도메인 특화

> Phase 1~4의 범용 플랫폼 위에 소방 전문 기능을 올린다.
> **의존**: Phase 1 (GIS), Phase 2 (Text-to-SQL)

| # | 작업 | 상태 | 범위 | 검증 기준 |
|---|------|------|------|----------|
| 5-1 | 소방 도메인 CRUD API | ⬜ | Backend | 조직/사건/소방용수/출동 REST API + 권한. 시드 데이터 생성. TC 20개+. |
| 5-2 | 소방 KPI 대시보드 | ⬜ | Frontend | 응답시간, 출동 건수, 사건 유형 분포, 소방서별 성과 비교 위젯. |
| 5-3 | 소방 전용 지도 | ⬜ | Frontend | V-World 배경지도 + 소방서/소화전/사건 레이어 + 관할구역 경계 + 히트맵. |
| 5-4 | AI 소방 분석 도구 | ⬜ | AI Agent | 소방 MCP 도구 + 소방 특화 프롬프트 + fire 스키마 Text-to-SQL. |
| 5-5 | 공공데이터 ETL 연동 | ⬜ | Backend | 소방용수/소방서 좌표(data.go.kr), 행정경계(V-World), 지오코딩(Kakao). |

---

## 백로그 (Backlog)

> 우선순위 미정. 아이디어 수집 및 향후 Phase 편입 검토.

### 플랫폼

| # | 아이디어 | 우선순위 | 메모 |
|---|---------|---------|------|
| BL-01 | 알림 시스템 (인앱 + Webhook + Slack) | P:높음 | LISTEN/NOTIFY + SSE |
| BL-02 | 데이터 품질 규칙 엔진 | P:보통 | NOT_NULL/RANGE/REGEX/CUSTOM_SQL |
| BL-03 | 데이터 리니지 시각화 | P:보통 | 파이프라인 input/output 관계 자동 기록 |
| BL-04 | 파이프라인 조건부 분기 (IF/ELSE) | P:보통 | 이전 스텝 결과 기반 분기 |
| BL-05 | 멀티테넌시 (RLS) | P:낮음 | 조직별 데이터 격리 |
| BL-06 | 데이터셋 버전 관리 | P:낮음 | SNAPSHOT/APPEND 기반 |
| BL-07 | 협업 기능 (코멘트, @mention) | P:낮음 | |
| BL-08 | 프론트엔드 테스트 (Vitest) | P:보통 | firehub-web 테스트 프레임워크 도입 |
| BL-09 | CI/CD 파이프라인 (GitHub Actions) | P:보통 | 빌드/테스트/배포 자동화 |
| BL-10 | 모바일 반응형 + PWA | P:낮음 | 현장 소방관용 |

### AI/분석

| # | 아이디어 | 우선순위 | 메모 |
|---|---------|---------|------|
| BL-11 | AI 이상 탐지 알림 | P:높음 | 데이터 패턴 이상 → 자동 알림 |
| BL-12 | AI 차트/대시보드 자동 생성 | P:보통 | "대시보드 만들어줘" → 위젯 자동 구성 |
| BL-13 | AI 소방용수 최적 배치 제안 | P:보통 | 커버리지 갭 + Voronoi 분석 |
| BL-14 | AI 화재 위험도 분석 | P:보통 | 건물 노후도 + 인구밀도 → H3 위험 점수 |
| BL-15 | What-if 소방서 배치 시뮬레이션 | P:낮음 | 이소크론 분석 |

### 소방 도메인 확장

| # | 아이디어 | 우선순위 | 메모 |
|---|---------|---------|------|
| BL-16 | 장비/차량 관리 | P:보통 | CRUD + 정비 이력 |
| BL-17 | 인력/교육 관리 | P:보통 | 자격증 + 교육 이력 |
| BL-18 | 건물 검사/예방 관리 | P:낮음 | 검사 일정 + 위반사항 |
| BL-19 | 실시간 유닛 추적 (GPS) | P:낮음 | WebSocket + 실시간 위치 |
| BL-20 | 정기 보고서 자동화 (PDF) | P:보통 | 월간/분기 보고서 크론 |
| BL-21 | 시민 공개 대시보드 | P:낮음 | 화재 통계 공개 (개인정보 제거) |

---

## 기술 스택

### 현재

| 영역 | 기술 |
|------|------|
| Backend | Spring Boot 3.4 + Java 21, jOOQ, Flyway, Spring Security + JWT |
| Frontend | Vite + React 19 + TypeScript, TanStack Query, React Router v7, shadcn/ui, Tailwind CSS v4 |
| AI Agent | Node.js + TypeScript, Express 4, Claude Agent SDK, MCP 도구 36종 |
| Database | PostgreSQL 16, public/data 2스키마 |
| Monorepo | pnpm workspaces + Turborepo |

### Phase별 추가 예정

| 기술 | Phase | 용도 |
|------|-------|------|
| PostGIS 3.5 (SQL 함수 기반, JTS 미사용) | 1-0 ✅ | 공간 데이터 저장/쿼리 |
| MapLibre GL JS | 1-3, 1-4 | 프론트엔드 지도 렌더링 |
| deck.gl | 5-3 | 대규모 데이터 시각화 (히트맵) |
| V-World WMTS | 5-3 | 한국 배경지도 |

---

## 참고 문서

| 문서 | 역할 |
|------|------|
| `docs/research/gis-deep-analysis.md` | GIS 심층 분석 (경쟁사, 도입 사례, 유저스토리, 공공데이터) |
| `docs/research/gis-spatial-research.md` | GIS 기술 스택 비교 |

---

## 변경 이력

| 날짜 | 변경 내용 |
|------|---------|
| 2026-03-02 | Phase 1-0, 1-1 완료. PostGIS 인프라 + GEOMETRY CRUD 구현 (PostGIS SQL 함수 기반, JTS 미사용). |
| 2026-03-01 | Phase 0-3(PostGIS+fire) 롤백. PostGIS 인프라를 Phase 1-0으로 이동. fire 스키마는 Phase 5로 이동. |
| 2026-03-01 | 작업 단위 레벨로 재구성. Phase별 아이템을 독립 계획/검증 가능한 단위로 조정. 백로그 21건으로 정리. |
| 2026-03-01 | 초안 작성. Phase 0~5 + 백로그 정리. |
