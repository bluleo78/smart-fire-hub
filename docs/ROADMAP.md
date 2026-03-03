# Smart Fire Hub — ROADMAP

> **최종 수정**: 2026-03-03
> **비전**: AI-First 소방 전문 데이터 플랫폼
> **전략**: 기초 기술 → 범용 플랫폼 → 도메인 특화 순서로 확장
> **원칙**: 각 아이템은 독립적으로 계획(Plan) → 구현 → 검증 가능한 작업 단위

---

## 진행 현황 요약

| Phase | 상태 | 진행률 | 설명 |
|-------|------|--------|------|
| [Phase 0](#phase-0-기반-정비) | **완료** | 100% | 보안, 코드 품질 |
| [Phase 1](#phase-1-gis-범용-기반) | **완료** | 6/6 | PostGIS 인프라 + GEOMETRY 지원 + 지도 + 공간 쿼리 + MAP 차트 |
| [Phase 2](#phase-2-디자인-시스템) | **완료** | 11/11 | 디자인 가이드라인 문서 수립 + 코드 적용 |
| [Phase 3](#phase-3-ai-text-to-sql) | **완료** | 2/2 | 자연어 → SQL → 차트 추천 |
| [Phase 4](#phase-4-대시보드-전체-개선) | 대기 | 0/6 | 홈 대시보드 리디자인 + 분석 대시보드 갱신 수정 + SSE 실시간 알림 |
| [Phase 5](#phase-5-데이터-내보내기) | 대기 | 0/2 | CSV/Excel/GeoJSON 다운로드 |
| [Phase 6](#phase-6-소방-도메인-특화) | 대기 | 0/5 | 소방 CRUD, 대시보드, 지도, AI, 공공데이터 |

---

## Phase 0: 기반 정비 ✅

> **완료** — 보안, 코드 품질

| # | 작업 | 상태 | 검증 |
|---|------|------|------|
| 0-1 | 보안 강화 (JWT 환경변수, CORS, brute-force, Security 헤더, Refresh token rotation) | ✅ | 기존 테스트 통과 |
| 0-2 | 코드 품질 (P1~P3 코드 리뷰, ErrorResponse 수정) | ✅ | 기존 테스트 통과 |

---

## Phase 1: GIS 범용 기반 ✅

> **완료** — 데이터 플랫폼에 공간 데이터(GEOMETRY) 지원을 추가한다.
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
| 1-2 | 공간 쿼리 API (nearby, bbox) | ✅ | Backend | 1-1 | 기존 GET /data에 spatialColumn/nearby/bbox 파라미터 통합. SpatialFilter sealed interface. SpatialQueryTest 14개 통과. |
| 1-3 | MapLibre 지도 컴포넌트 | ✅ | Frontend | 없음 | OpenFreeMap Liberty 타일 + MapView/GeoJsonLayer/FeaturePopup 컴포넌트. 데이터셋 상세 "지도" 탭. 마커 클릭 팝업. E2E 검증 완료. |
| 1-4 | MAP 차트 타입 | ✅ | Backend + Frontend | 1-2, 1-3 | V30 마이그레이션. ChartService MAP 검증. MapChartView 컴포넌트. 차트 빌더 자동추천. AnalyticsQueryExecutionService GEOMETRY→GeoJSON 자동변환. 대시보드 MAP 위젯 (12x6). E2E 검증 완료. |
| 1-5 | 공간 쿼리 AI 가이드 | ✅ | AI Agent | 1-2 | 시스템 프롬프트에 PostGIS SQL 가이드 추가 (ST_DWithin, ST_Intersects, ST_Distance 등). AI가 execute_sql_query로 공간 쿼리 실행 가능. analytics-tools에 MAP 차트 생성 지원. |

---

## Phase 2: 디자인 시스템 ✅

> UI 일관성을 위한 디자인 시스템 가이드라인 수립 + 코드 적용.
> **의존**: Phase 1 완료
> **참조**: shadcn/ui 공식 + Vercel Geist Design System
> **산출물**: `docs/design-system/` 디렉토리 (14개 마크다운 파일) + 코드 적용
>
> **실행 순서**: 2-1~2-7 (가이드라인 문서) 완료 후 2-8~2-11 (코드 적용) 순차 진행.
> ```
> 가이드라인:  2-1 ~ 2-7 (문서만, 코드 변경 없음) ✅
> 코드 적용:   2-8 → 2-9 → 2-10 → 2-11
> ```

| # | 작업 | 상태 | 범위 | 검증 기준 |
|---|------|------|------|----------|
| 2-1 | Design Tokens (색상/반경/그림자/Z-Index) | ✅ | 문서 | index.css 30+ 토큰 전수 문서화. 하드코딩 색상 43건 감사. 시맨틱 Status 토큰 제안. |
| 2-2 | Typography Scale | ✅ | 문서 | 13단계 시맨틱 스케일 정의. As-Is/To-Be 매핑. font-mono 규칙. |
| 2-3 | Spacing & Layout | ✅ | 문서 | 7단계 스페이싱 스케일. AppLayout 골격. 그리드 패턴 5종. |
| 2-4 | Components + Page Patterns | ✅ | 문서 | 24 shadcn + 6 커스텀 가이드. 5개 페이지 템플릿 (TSX 스켈레톤). |
| 2-5 | UX Patterns (Feedback/Icon/Animation/Form) | ✅ | 문서 | Loading/Empty/Error/Toast + 아이콘 규칙 + 모션 가이드 + 폼 패턴. |
| 2-6 | Accessibility + Dark Mode + Responsive | ✅ | 문서 | WCAG 2.2 AA 기준. 다크모드 갭 분석. 반응형 현황. |
| 2-7 | Index + Migration Backlog | ✅ | 문서 | Quick Reference Card. P0~P3 마이그레이션 작업 목록. |
| 2-8 | 시맨틱 Status 토큰 + Badge variant 확장 | ✅ | Frontend | success/warning/info CSS 변수 9개 (Light+Dark). Badge variant 3종 추가. 빌드 + 타입체크 통과. |
| 2-9 | 하드코딩 색상 마이그레이션 | ✅ | Frontend | 46건 시맨틱 토큰 교체 (16개 파일). 다크모드 정상. Playwright Light/Dark 스크린샷 검증. |
| 2-10 | Typography 통일 | ✅ | Frontend | 페이지 타이틀 18개 + 섹션 헤더 7개 리팩터링. 빌드 + 스크린샷 검증. |
| 2-11 | 접근성 + 다크모드 개선 | ✅ | Frontend | icon-only 버튼 aria-label 12건 + Table aria-label 8건. 다크모드 잔여 dark: 정리 확인. |

---

## Phase 3: AI Text-to-SQL ✅

> **완료** — 비개발자가 자연어로 데이터를 조회/분석할 수 있다.
> **의존**: 없음 (Phase 1과 병렬 가능)

| # | 작업 | 상태 | 범위 | 검증 기준 |
|---|------|------|------|----------|
| 3-1 | Text-to-SQL MCP 도구 (스키마 조회 + SQL 생성/실행) | ✅ | AI Agent | get_data_schema + execute_analytics_query + show_chart MCP 도구 11종 추가. 시스템 프롬프트에 Text-to-SQL 가이드 포함. DDL/DML 거부 규칙. TC 31개 통과. |
| 3-2 | 차트 자동 추천 + 인라인 렌더링 | ✅ | AI Agent + Frontend | show_chart 도구로 SQL 결과 기반 chartType/config 자동 추천. InlineChartWidget으로 채팅 내 차트 인라인 렌더링. SQL 보기 + 차트 저장 다이얼로그. 세션 히스토리 재로드 시 차트 복원. 사이드패널/플로팅/전체화면 3모드 검증. |

---

## Phase 4: 대시보드 전체 개선

> 홈 대시보드 리디자인 (actionable metrics) + 분석 대시보드 실시간 갱신 + SSE 알림 인프라.
> **의존**: Phase 1 (지도 위젯), Phase 3 (AI Text-to-SQL)
> **리서치**: `docs/research/phase4-dashboard-research.md`
> **계획**: `.omc/plans/phase-4-dashboard.md`
> **목업**: `snapshots/home-dashboard-mockup.html`
>
> **실행 순서**:
> ```
> Layer 1 (병렬):  4-1 ──┬── 4-5
>                  4-2 ──┤
>                  4-3 ──┼── 4-6
>                        └── 4-4
> ```

| # | 작업 | 상태 | 범위 | 의존 | 검증 기준 |
|---|------|------|------|------|----------|
| 4-1 | 분석 대시보드 자동 갱신 수정 + 배치 최적화 | ⬜ | Frontend | 없음 | autoRefresh 시 차트 데이터 실갱신. 배치 엔드포인트 활용으로 N×2→1건 축소. 빌드 통과. |
| 4-2 | 홈 대시보드 API — 건강 상태 + 활동 피드 | ⬜ | Backend | 없음 | /health, /attention, /activity 3개 엔드포인트. 심각도 정렬. 필터+페이지네이션. 통합 테스트 통과. |
| 4-3 | SSE 이벤트 브로드캐스트 인프라 | ⬜ | Backend | 없음 | /notifications/stream SSE 엔드포인트. user-scoped SseEmitter. 파이프라인/임포트 이벤트 브로드캐스트. TC 통과. |
| 4-4 | 홈 대시보드 UI 리디자인 | ⬜ | Frontend | 4-2 | 5-Zone: 건강 상태바 + 주의 필요 + 퀵 액션 + 최근 사용 + 활동 피드. 다크모드. 반응형. 빌드 통과. |
| 4-5 | 데이터 신선도 UX + 위젯 성능 최적화 | ⬜ | Frontend | 4-1 | 위젯별 Fresh/Stale/Refreshing 인디케이터. Jitter ±10%. Intersection Observer off-screen 일시정지. 빌드 통과. |
| 4-6 | SSE 실시간 연동 + 알림 UI | ⬜ | Frontend | 4-3, 4-4, 4-5 | SSE 구독 + 캐시 invalidation + 토스트 (P1 CRITICAL/P2 WARNING만, P3 INFO는 피드만). 빌드 통과. |

---

## Phase 5: 데이터 내보내기

> 분석 결과를 외부로 가져갈 수 있다.
> **의존**: 없음 (독립적)

| # | 작업 | 상태 | 범위 | 검증 기준 |
|---|------|------|------|----------|
| 5-1 | 내보내기 API (CSV/Excel/GeoJSON) | ⬜ | Backend | 데이터셋/쿼리 결과를 CSV(스트리밍), Excel, GeoJSON으로 다운로드. 대용량 지원. |
| 5-2 | 내보내기 UI | ⬜ | Frontend | 데이터셋/쿼리 결과에 "내보내기" 버튼 + 포맷 선택. 대용량은 백그라운드 작업. |

---

## Phase 6: 소방 도메인 특화

> Phase 1~5의 범용 플랫폼 위에 소방 전문 기능을 올린다.
> **의존**: Phase 1 (GIS), Phase 3 (Text-to-SQL)

| # | 작업 | 상태 | 범위 | 검증 기준 |
|---|------|------|------|----------|
| 6-1 | 소방 도메인 CRUD API | ⬜ | Backend | 조직/사건/소방용수/출동 REST API + 권한. 시드 데이터 생성. TC 20개+. |
| 6-2 | 소방 KPI 대시보드 | ⬜ | Frontend | 응답시간, 출동 건수, 사건 유형 분포, 소방서별 성과 비교 위젯. |
| 6-3 | 소방 전용 지도 | ⬜ | Frontend | V-World 배경지도 + 소방서/소화전/사건 레이어 + 관할구역 경계 + 히트맵. |
| 6-4 | AI 소방 분석 도구 | ⬜ | AI Agent | 소방 MCP 도구 + 소방 특화 프롬프트 + fire 스키마 Text-to-SQL. |
| 6-5 | 공공데이터 ETL 연동 | ⬜ | Backend | 소방용수/소방서 좌표(data.go.kr), 행정경계(V-World), 지오코딩(Kakao). |

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
| deck.gl | 6-3 | 대규모 데이터 시각화 (히트맵) |
| V-World WMTS | 6-3 | 한국 배경지도 |

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
| 2026-03-03 | Phase 4 재설계. 기존 2작업 → 6작업으로 확장 (홈 대시보드 리디자인 + 분석 대시보드 갱신 수정 + SSE 인프라 + 알림 UI). 리서치: 10개 데이터 플랫폼 분석, 알림 UX 3단계 우선순위 모델, SSE vs WebSocket 비교. |
| 2026-03-03 | Phase 3 완료 (3-1, 3-2). AI Text-to-SQL MCP 도구 11종 + 시스템 프롬프트 + InlineChartWidget 인라인 차트 렌더링 + 세션 히스토리 차트 복원 + 차트 저장. TC 103개 통과. |
| 2026-03-02 | Phase 2 코드 적용 완료 (2-8~2-11). 시맨틱 Status 토큰 + 색상 마이그레이션 46건 + Typography 통일 25건 + 접근성 aria-label 20건. 41개 파일 변경. Phase 2 전체 완료. |
| 2026-03-02 | Phase D-1/D-2를 Phase 2 (디자인 시스템)로 통합. Phase 번호 재부여 (기존 2→3, 3→4, 4→5, 5→6). |
| 2026-03-02 | Phase D-1 (디자인 시스템 가이드라인), D-2 (코드 적용) 추가. Phase 1과 Phase 2 사이에 삽입. |
| 2026-03-02 | Phase 1-4, 1-5 완료. MAP 차트 타입 (V30 마이그레이션, MapChartView, 자동추천, GEOMETRY→GeoJSON 자동변환). 공간 쿼리 AI 가이드. Phase 1 전체 완료. |
| 2026-03-02 | Phase 1-0, 1-1 완료. PostGIS 인프라 + GEOMETRY CRUD 구현 (PostGIS SQL 함수 기반, JTS 미사용). |
| 2026-03-01 | Phase 0-3(PostGIS+fire) 롤백. PostGIS 인프라를 Phase 1-0으로 이동. fire 스키마는 Phase 5로 이동. |
| 2026-03-01 | 작업 단위 레벨로 재구성. Phase별 아이템을 독립 계획/검증 가능한 단위로 조정. 백로그 21건으로 정리. |
| 2026-03-01 | 초안 작성. Phase 0~5 + 백로그 정리. |
