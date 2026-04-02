# Smart Fire Hub — ROADMAP

> **최종 수정**: 2026-04-02
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
| [Phase 4](#phase-4-대시보드-전체-개선) | **완료** | 6/6 | 홈 대시보드 리디자인 + 분석 대시보드 갱신 수정 + SSE 실시간 알림 |
| [Phase 5](#phase-5-데이터-내보내기) | **완료** | 2/2 | CSV/Excel/GeoJSON 다운로드 |
| [Phase 5.7](#phase-57-firehub-executor-실행-엔진-분리) | **완료** | 7/7 | 사용자 코드 실행 서비스 분리 (Python/FastAPI + nsjail) |
| [Phase 5.5](#phase-55-운영-안정화--ai-에이전트-개선) | **완료** | 4/4 | 컨텍스트 표시, 컴팩션 알림, 파이프라인 SQL 래핑 |
| [Phase 5.6](#phase-56-uiux-일관성-강화--schemaexplorer-리디자인) | **완료** | 3/3 | UI 일관성 수정 + 컴포넌트 분리 + SchemaExplorer UX 리디자인 |
| [Phase 5.8](#phase-58-파이프라인-python-고도화--ai-에이전트-확장) | **완료** | 4/4 | Python 스텝 자동 적재 + 서브에이전트 시스템 + Claude Code CLI 에이전트 + AI 인증 관리 |
| [Phase 5.9](#phase-59-uiux-개선--멀티-ai-프로바이더) | **완료** | 4/4 | AI 상태 칩 + 멀티 테마 + 멀티 AI 프로바이더 + 운영 안정화 |
| [Phase 6](#phase-6-ai-chat-generative-ui) | **완료** | 3/3 | AI 챗 인라인 위젯, 딥링크 네비게이션, 프로액티브 AI, 화면 컨텍스트 |
| [Phase 7](#phase-7-ai-리포트-고도화) | 대기 | 0/11 | 사용성 개선 (수신자 지정, 실행 결과, 작업/템플릿 UX) + PDF, Slack/Webhook, 내러티브 강화, 이상 탐지, 비주얼 빌더, 목표 기반 생성, 역할별 개인화, KPI 팔로우 |
| [Phase 8](#phase-8-소방-도메인-특화) | 대기 | 0/5 | 소방 CRUD, 대시보드, 지도, AI, 공공데이터 |

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
| 4-1 | 분석 대시보드 자동 갱신 수정 + 배치 최적화 | ✅ | Frontend | 없음 | autoRefresh 시 차트 데이터 실갱신. 배치 엔드포인트 활용으로 N×2→1건 축소. useMemo 최적화. 빌드 통과. |
| 4-2 | 홈 대시보드 API — 건강 상태 + 활동 피드 | ✅ | Backend | 없음 | /health, /attention, /activity 3개 엔드포인트. 심각도 정렬. 필터+페이지네이션. SOURCE 데이터셋 필터. 입력값 검증. 통합 테스트 15개 통과. |
| 4-3 | SSE 이벤트 브로드캐스트 인프라 | ✅ | Backend | 없음 | /notifications/stream SSE 엔드포인트. user-scoped SseEmitter (유저당 3개 제한). Heartbeat 30초. @RequirePermission. TC 11개 통과. |
| 4-4 | 홈 대시보드 UI 리디자인 | ✅ | Frontend | 4-2 | 5-Zone: 건강 상태바 + 주의 필요(Card+스크롤) + 퀵 액션 + 최근 사용(총 개수+스크롤) + 활동 피드(고정 높이+필터). 다크모드. 빌드 통과. |
| 4-5 | 데이터 신선도 UX + 위젯 성능 최적화 | ✅ | Frontend | 4-1 | WidgetFreshnessBar (Fresh/Stale/Refreshing). Jitter ±10%. Intersection Observer off-screen 일시정지. auto-refresh 없을 때 interval 비활성화. 빌드 통과. |
| 4-6 | SSE 실시간 연동 + 알림 UI | ✅ | Frontend | 4-3, 4-4, 4-5 | useNotificationStream SSE 구독 + exponential backoff + 캐시 invalidation + 토스트 (CRITICAL/WARNING). JSON.parse 안전성. 빌드 통과. |

---

## Phase 5: 데이터 내보내기 ✅

> **완료** — 분석 결과를 외부로 가져갈 수 있다.
> **의존**: 없음 (독립적)
> **계획**: `.omc/plans/phase-5-data-export.md`

| # | 작업 | 상태 | 범위 | 검증 기준 |
|---|------|------|------|----------|
| 5-1 | 내보내기 API (CSV/Excel/GeoJSON) | ✅ | Backend | ExportWriter 3종 (CSV+BOM/Excel SXSSFWorkbook/GeoJSON FeatureCollection). Sync/Async 이원화 (50K row 기준). 컬럼 선택 + 검색 필터. Rate limiting (3건/분). 감사 로그. 24시간 파일 정리 스케줄러. 통합 테스트 13개 통과. |
| 5-2 | 내보내기 UI | ✅ | Frontend | ExportDialog (포맷 선택 + 컬럼 선택 + 예상 크기). 비동기 진행률 폴링 UI. 데이터셋 데이터 탭 + 쿼리 에디터 내보내기 통합. downloadBlob 유틸. 빌드 + 타입체크 통과. |

---

## Phase 5.5: 운영 안정화 + AI 에이전트 개선

> 프로덕션 운영 중 발견된 버그 수정 + AI 에이전트 세션/컴팩션 개선 + 파이프라인 실행 엔진 보강.
> **의존**: Phase 5 완료
>
> **실행 순서**:
> ```
> 5.5-1 (AI Agent, 완료) ──→ 5.5-4 (Frontend, 완료)
> 5.5-3 (Backend, 완료)      5.5-5 (Frontend, 완료)
> ```

| # | 작업 | 상태 | 범위 | 의존 | 검증 기준 |
|---|------|------|------|------|----------|
| 5.5-1 | AI 세션 컴팩션 버그 수정 | ✅ | AI Agent | 없음 | 컴팩션 임계값 50K→150K. error 이벤트에 sessionId+inputTokens 포함. max_turns_exceeded UX 메시지. Dockerfile .claude 사전 생성. 빌드 통과. |
| 5.5-2 | ~~AI 세션 컴팩션 품질 개선~~ | 삭제 | AI Agent | 없음 | SDK 내장 컴팩션 사용으로 불필요. |
| 5.5-3 | 파이프라인 SQL SELECT 자동 INSERT 래핑 | ✅ | Backend | 없음 | output dataset이 있고 SQL이 SELECT로 시작하면 INSERT INTO data."table" (SELECT ...) 자동 래핑. REPLACE/APPEND 모두 정상 동작. CTE(WITH) 지원. 임시 데이터셋 자동 생성. 컬럼 매칭 검증. 통합 테스트 4건 통과. |
| 5.5-4 | AI 채팅 컨텍스트 크기 표시 | ✅ | Frontend | 5.5-1 | TokenUsageChip 컴포넌트. "15K / 200K" 형태 토큰 사용량 표시. 프로그레스 바 (50%→경고, 75%→위험 색상). done/error 이벤트에서 토큰 수 수신. 3모드(사이드/플로팅/전체화면) 통합. 빌드+타입체크 통과. |
| 5.5-5 | AI 채팅 컴팩션 알림 UX | ✅ | Frontend | 없음 | 컴팩션 시스템 메시지 ("자동 요약됨"). TokenUsageChip "요약 중" 스피너 표시. SDK 내장 컴팩션 사용으로 접기/펼치기·컴팩션 지점 표시는 스코프 아웃. |

---

## Phase 5.6: UI/UX 일관성 강화 + SchemaExplorer 리디자인

> 프론트엔드 UI 일관성 점검에서 발견된 패턴 불일치 수정 + 쿼리 에디터 SchemaExplorer를 업계 표준 수준으로 개선.
> **의존**: 없음 (독립적)
> **리서치**: 10개 SQL 에디터 (Metabase, Redash, Superset, DBeaver, DataGrip, Retool, Mode, Looker, BigQuery, Grafana) 스키마 탐색기 UX 분석 완료.
>
> **실행 순서**:
> ```
> 5.6-1 (UI 일관성) ──┐
> 5.6-2 (컴포넌트 분리) ──┤
>                      └── 5.6-3 (SchemaExplorer 리디자인, 5.6-2 의존)
> ```

| # | 작업 | 상태 | 범위 | 의존 | 검증 기준 |
|---|------|------|------|------|----------|
| 5.6-1 | UI 일관성 수정 (뒤로가기 패턴 + 에디터 타이틀 폰트) | ✅ | Frontend | 없음 | 모든 상세/에디터 페이지에 아이콘 전용 뒤로가기 버튼. 에디터 페이지 타이틀 text-lg 통일. 빌드+타입체크 통과. (commit: `4bd693e`) |
| 5.6-2 | SchemaExplorer 별도 컴포넌트 분리 | ✅ | Frontend | 없음 | QueryEditorPage.tsx 인라인 코드 → 별도 파일 분리. 기존 동작 유지. 빌드+타입체크 통과. (commit: `7044289`) |
| 5.6-3 | SchemaExplorer UX 리디자인 | ✅ | Frontend | 5.6-2 | P1: 테이블/컬럼 검색 필터. P2: hover-reveal 액션 (삽입 버튼 → 호버 아이콘). P3: 컬럼 타입별 색상 뱃지. P4: 컨텍스트 메뉴 (SELECT * LIMIT 100 등). P5: 테이블별 컬럼 수 뱃지. 커서 위치 삽입. 디자인 시스템 준수. 빌드/타입체크 통과. Playwright 검증 완료. |

---

## Phase 5.7: firehub-executor 실행 엔진 분리

> 사용자가 작성한 코드/쿼리는 전부 격리된 실행 엔진(`firehub-executor`)에서 실행한다.
> Python/FastAPI 독립 서비스로 구현. nsjail로 Python 스크립트 샌드박싱.
> **의존**: 없음 (독립적)
> **계획**: `.omc/plans/pipeline-sandbox-phase2-service-separation.md`
> **선행 작업**: 파이프라인 샌드박스 Phase 1 완료 (pipeline_executor DB 역할, SqlValidator, PythonScriptExecutor env 격리)
>
> **2단계 점진적 마이그레이션**:
> - Phase 2a: Python 실행 + 분석 쿼리 (DDL 불필요, 가장 가치 높음)
> - Phase 2b: SQL 실행 + API_CALL 실행 (DDL 권한 문제 해결 후)
>
> **아키텍처**:
> ```
> firehub-api (오케스트레이터)  ──HTTP──→  firehub-executor (실행 엔진)
>   • 인증/인가                              • Python 실행 (nsjail)
>   • 파이프라인 DAG 관리                     • SQL 실행 (psycopg2)
>   • 실행 상태 관리                          • API_CALL 실행 (httpx)
>   • 트리거/체인                             • 분석 쿼리 실행
>                                            • DB: pipeline_executor (data DML만)
> ```
>
> **실행 순서**:
> ```
>> Phase 2a:
>   5.7-1 (완료) ──→ 5.7-2 (프로젝트 + Python) ──→ 5.7-3 (분석 쿼리) ──→ 5.7-4 (API 위임 + 운영 배포)
>
> Phase 2b:
>   5.7-5 (SQL 실행) ──→ 5.7-6 (API_CALL 실행) ──→ 5.7-7 (통합 테스트 + 정리)
> ```

| # | 작업 | 상태 | 범위 | 의존 | 검증 기준 |
|---|------|------|------|------|----------|
| 5.7-1 | 파이프라인 샌드박스 Phase 1 (DB 역할 + SQL/Python 격리) | ✅ | Backend | 없음 | `pipeline_executor` DB 역할 (data 스키마 DML만). SqlValidator 차단 키워드 검증. PythonScriptExecutor env.clear() + pipeline 자격증명만. PermissionChecker python_execute 권한 게이트. 511 테스트 통과. |
| 5.7-2 | firehub-executor 프로젝트 + Python 실행 엔드포인트 | ✅ | Executor | 5.7-1 | FastAPI 프로젝트 스캐폴딩. Internal Auth 미들웨어. psycopg2 커넥션 풀. `POST /execute/python` + nsjail 샌드박스. nsjail 비활성화 폴백. `GET /health`. pytest 통과. |
| 5.7-3 | 분석 쿼리 실행 엔드포인트 | ✅ | Executor | 5.7-2 | `POST /execute/query`. SQL 차단 키워드 검증. readOnly 모드. LIMIT 자동 추가. GEOMETRY→GeoJSON 변환. statement_timeout 30초. SAVEPOINT 복구. pytest 통과. |
| 5.7-4 | firehub-api executor 위임 + 운영 배포 (Phase 2a) | ✅ | Backend + Docker | 5.7-2, 5.7-3 | ExecutorClient (Python/Query). PipelineExecutionService Python 스텝 → executor 위임. AnalyticsQueryExecutionService → executor 위임. Dockerfile (nsjail 포함). docker-compose.prod.yml executor 서비스 추가. 기존 테스트 통과. 운영 배포 검증. |
| 5.7-5 | SQL 실행 엔드포인트 | ✅ | Executor | 5.7-4 | `POST /execute/sql`. SQL 차단 키워드 검증 (심층 방어). SELECT/DML 분기 처리. 커밋/롤백 관리. pytest 64개 통과. |
| 5.7-6 | API_CALL 실행 엔드포인트 | ✅ | Executor + Backend | 5.7-5 | `POST /execute/api-call`. SSRF 보호 (Java 1:1 포팅). 페이지네이션 (OFFSET). 필드 매핑 + 타입 변환 (6종). REPLACE DDL 오케스트레이션 (API측). 재시도 (지수 백오프). 인증 (API_KEY, BEARER). ExecutorClient `executeSql()`/`executeApiCall()` 추가. PipelineExecutionService SQL/API_CALL 위임. pytest 119개 통과. |
| 5.7-7 | 통합 테스트 + 기존 executor 코드 정리 | ✅ | 전체 | 5.7-6 | ExecutorClient WireMock 테스트 12개. SqlScriptExecutor/PythonScriptExecutor/ApiCallExecutor @Deprecated 마킹. 전체 Java 테스트 통과. |

---

## Phase 5.8: 파이프라인 Python 고도화 + AI 에이전트 확장

> 파이프라인 Python 스텝의 자동 적재 패턴 완성 + AI 에이전트 확장성(서브에이전트, CLI 에이전트) + 인증 관리 UX 개선.
> **의존**: Phase 5.7 (firehub-executor)
>
> **실행 순서**:
> ```
> 5.8-1 (Backend+Executor, 독립) ──→ 5.8-2 (Backend+Frontend)
> 5.8-3 (AI Agent, 독립) ──→ 5.8-4 (AI Agent+Frontend+Backend)
> ```

| # | 작업 | 상태 | 범위 | 의존 | 검증 기준 |
|---|------|------|------|------|----------|
| 5.8-1 | Python 스텝 자동 적재 패턴 완성 | ✅ | Backend + Executor | 없음 | Python 스텝 stdout JSON 자동 적재 (SQL/API_CALL과 동일 패턴). outputColumns 기반 자동 temp 데이터셋 생성. 스텝 입력 데이터셋 UX 개선. 파이프라인 실행 서비스 리팩터링. |
| 5.8-2 | 홈 대시보드 버그 수정 + AI UI 개선 | ✅ | Frontend | 5.8-1 | 완료된 파이프라인 실행이 미해결로 표시되는 버그 수정. AI 버튼을 사이드바 상단으로 이동 (FAB 제거). AI 채팅 멀티스텝 thinking 상태 표시 수정. SSE 연결 안정성 개선 (ping 이벤트 + 크래시 방어). |
| 5.8-3 | 서브에이전트 동적 로딩 + Claude Code CLI 에이전트 | ✅ | AI Agent | 없음 | 서브에이전트 동적 로딩 시스템 추가. Claude Code CLI 에이전트 유형 추가 (구독/API 키 모드 선택). 시스템 프롬프트 자동 컬럼 충돌 주의사항 추가. |
| 5.8-4 | AI 에이전트 인증 관리 UX | ✅ | AI Agent + Frontend + Backend | 5.8-3 | CLI OAuth 토큰 DB 암호화 저장. 토큰/API 키 유효성 실제 API 호출 검증. 인증 확인 버튼 + 자동 검증 제거. 설정 페이지 CLI 인증 UI 단순화 (토큰 입력 필드). 미저장 상태 인증 확인 비활성화. |

---

## Phase 5.9: UI/UX 개선 + 멀티 AI 프로바이더

> AI 상태 칩 UX, 멀티 테마 지원, 멀티 AI 프로바이더 리팩터링, 운영 안정화 버그 수정.
> **의존**: Phase 5.8 완료

| # | 작업 | 상태 | 범위 | 검증 기준 |
|---|------|------|------|----------|
| 5.9-1 | AI 상태 칩 (AIStatusChip) | ✅ | Frontend | 상태 표시 칩 (연결/대화중/오류). 호버 드롭다운 (세션 정보). 클릭 모드 전환 (사이드패널/플로팅/전체화면). 글로벌 CSS + 타입 안전성. 빌드+타입체크 통과. |
| 5.9-2 | 멀티 테마 지원 | ✅ | Frontend | Enhanced Theme 듀얼 테마 + 7가지 디자인 개선. Indigo/Ocean/Sunset 3종 추가 테마. 셀렉트 박스 UI로 테마 전환. 빌드+타입체크 통과. |
| 5.9-3 | 멀티 AI 프로바이더 리팩터링 | ✅ | AI Agent | Provider 패턴으로 공통 인터페이스 도입. 프로바이더별 구현 분리. 확장 가능한 멀티 AI 아키텍처. |
| 5.9-4 | 운영 안정화 + 버그 수정 | ✅ | 전체 | SSE 알림 스트림 타임아웃 해결. 파일 첨부 한글/공백 처리. 데이터셋 생성 datasetType null 방지. 파이프라인 다크 테마 적용 (7개 컴포넌트). AI_CLASSIFY 타입 캐스팅. 브라우저 탭 제목 수정. Dozzle 로그 뷰어 추가. |

---

## Phase 6: AI Chat Generative UI

> AI 챗을 고도화하여 인터랙티브 UI 위젯을 인라인 렌더링하고, 딥링크 네비게이션으로 메인 UI와 연결하며, Chat-First 경험을 달성한다.
> **의존**: Phase 3 (AI Text-to-SQL), Phase 5.6 (SchemaExplorer 리디자인)
> **계획**: `.omc/plans/ai-chat-generative-ui.md`
>
> **2단계 점진적 확장**:
> - Phase 6-1: Generative UI + 딥링크 (조회/탐색) — 인라인 위젯 7종 + 위젯 레지스트리
> - Phase 6-2: 프로액티브 AI (모니터링/알림) — 별도 아키텍처 필요
>
> **Phase 6-1 실행 순서**:
> ```
> Layer 0: SchemaExplorer → SchemaTree 분리 리팩터링
> Layer 1 (병렬): 위젯 인프라(FE) + MCP 도구 7종(BE)
> Layer 2 (병렬): 위젯 컴포넌트 6종(FE)
> Layer 3: 시스템 프롬프트 통합 + TOOL_LABELS 정리
> ```

| # | 작업 | 상태 | 범위 | 의존 | 검증 기준 |
|---|------|------|------|------|----------|
| 6-1 | Generative UI + 딥링크 (조회/탐색) | ✅ | Frontend + AI Agent | 5.6 | WidgetRegistry 패턴으로 4종 위젯 (show_dataset, show_table, navigate_to + 기존 show_chart 어댑터). Reference 패턴(FE fetch). 테이블 공통 서브 컴포넌트 (CellRenderer, ColumnFilterDropdown, ActiveFilterChips, Pagination, ExportDropdown). 데이터 타입별 렌더링 + 컬럼 드롭다운 필터 + 번호 페이지네이션 + CSV/JSON 내보내기. 딥링크: 메인 뷰 이동 + 사이드 패널 챗 유지. 쿼리 캐시 자동 갱신 (도구 실행 후 TanStack Query invalidation). 빌드+타입체크+AI Agent 테스트 189개 통과. |
| 6-2 | 프로액티브 AI (AI 인사이트) | ✅ | Backend + Frontend + AI Agent | 6-1 | 프롬프트 기반 스마트 작업 + cron 스케줄 자동 실행 + 리포트 템플릿 시스템 (빌트인 3종). 전달 채널 2종 (AI 챗 알림 + SMTP 이메일 리포트). MCP 도구 7종 (proactive_job CRUD + report_template). AI 인사이트 LNB 섹션 + 알림 패널. 이메일 마크다운→HTML + 차트 이미지 서버 렌더링. |
| 6-3 | AI 화면 컨텍스트 전달 | ✅ | Frontend + Backend + AI Agent | 6-1 | 매 메시지마다 현재 화면 정보(페이지명, 리소스 ID)를 AI에게 자동 전달. URL 패턴 매칭(24종) → 한국어 설명 생성. Spring Boot 프록시 screenContext 필드 추가. 시스템 프롬프트 화면 컨텍스트 활용 가이드. 빌드+테스트 통과. |

---

## Phase 7: AI 리포트 고도화

> Phase 6-2에서 구축한 프로액티브 AI 리포트 시스템을 고도화한다.
> 16개 서비스 벤치마킹 기반 (Tableau Pulse, Hex, Looker, Julius AI, Datadog Watchdog, Wordsmith 등).
> **의존**: Phase 6-2 (프로액티브 AI)
>
> **실행 순서**:
> ```
> Layer 0 (사용성 개선, 병렬):
>   7-0a(BE+FE) + 7-0b(FE) + 7-0c(FE) 병렬
>
> Layer 1 (Quick Wins, 병렬):
>   7-1(BE+FE) + 7-2(BE) + 7-3(AI) 병렬
>
> Layer 2 (핵심 차별화):
>   7-5(FE) + 7-4(BE+AI) 병렬 → 7-6(FE+AI, 7-4 의존)
>
> Layer 3 (고급):
>   7-7(BE+AI) → 7-8(Full Stack)
> ```

| # | 작업 | 상태 | 범위 | 의존 | 검증 기준 |
|---|------|------|------|------|----------|
| 7-0a | 이메일 수신자 지정 + 실행 결과 보기 | ⬜ | Backend + Frontend | 6-2 | 스마트 작업에 `config.emailRecipients` 필드 추가 — 다중 이메일 주소 입력/검증 UI. EmailDeliveryChannel이 지정된 수신자에게 발송. 실행 히스토리 모달 (기존 `/jobs/{id}/executions` API 연결). "결과 보기" 버튼 동작 구현 (실행 상태, 결과 요약, 에러 메시지, 토큰 사용량 표시). 상세 계획 별도 수립. |
| 7-0b | 스마트 작업 UX 개선 | ⬜ | Frontend | 6-2 | 작업 복제 버튼. Cron 프리셋 확대 (매 30분, 매일 오후, 매주 금요일, 매월 1일 등) + 타임존 셀렉터 표시 + 다음 실행 시간 표시. 상세 에러 메시지 (원인 정보 포함). 상세 계획 별도 수립. |
| 7-0c | 리포트 템플릿 UX 개선 | ⬜ | Frontend | 6-2 | 빌트인 템플릿 "복제하여 커스터마이징" 버튼. 섹션 타입별 가이드 (text/cards/list 예시 + 설명). JSON 실시간 검증 + 문법 강조. 간이 미리보기 (섹션 구조 시각화). 상세 계획 별도 수립. |
| 7-1 | PDF 리포트 내보내기 | ⬜ | Backend + Frontend | 7-0a | 실행 결과를 PDF로 다운로드. 차트 이미지 + 마크다운 → PDF 렌더링. 이메일에 PDF 첨부 옵션. 다운로드 버튼 UI. 상세 계획 별도 수립. |
| 7-2 | Slack/Webhook 전달 채널 | ⬜ | Backend | 7-0a | DeliveryChannel 확장. Slack Incoming Webhook + 범용 HTTP Webhook 2종 추가. 채널별 설정 UI (관리자). 전달 실패 시 재시도 + 로그. 상세 계획 별도 수립. |
| 7-3 | 리포트 내러티브 강화 | ⬜ | AI Agent | 7-0a | 단순 나열 → 인사이트 중심 자연어 서술. "왜 이 수치가 변했는가" 원인 분석 포함. 이전 실행 결과 비교 (전주/전일 대비 변화율). 시스템 프롬프트 + 컨텍스트 수집 개선. 상세 계획 별도 수립. |
| 7-4 | 이상 탐지 + 자동 알림 | ⬜ | Backend + AI Agent | 7-1~7-3 | 데이터셋 메트릭 모니터링 등록. 패턴 기반 이상 감지 (계절성/추세 고려, 고정 임계값 아님). 이탈 시 AI가 원인 분석 후 자동 알림. Datadog Watchdog 패턴 참조. 상세 계획 별도 수립. |
| 7-5 | 비주얼 리포트 빌더 | ⬜ | Frontend | 7-1~7-3 | JSON 수동 입력 → 드래그앤드롭 섹션 편집기. 빌트인 템플릿 복제 → 커스터마이징. 섹션 타입별 설정 (text/cards/list/chart). 실시간 미리보기. 상세 계획 별도 수립. |
| 7-6 | 목표 기반 리포트 생성 | ⬜ | Frontend + AI Agent | 7-4 | "매출이 왜 떨어졌는지 분석해줘" → AI가 분석 계획 수립 → 관련 데이터셋 자동 탐색 → 리포트 생성. 쿼리가 아닌 비즈니스 질문에서 출발. Akkio Generative Reports 패턴 참조. 상세 계획 별도 수립. |
| 7-7 | 역할별 리포트 개인화 | ⬜ | Backend + AI Agent | 7-4~7-6 | 같은 리포트 → 경영진(핵심 요약)/분석가(상세 데이터)/운영자(액션 아이템) 버전 자동 생성. 사용자 역할 기반 뷰 분기. Wordsmith NLG 패턴 참조. 상세 계획 별도 수립. |
| 7-8 | KPI 팔로우 + 프로액티브 푸시 | ⬜ | Full Stack | 7-7 | 사용자가 관심 지표 "팔로우" → AI가 주기적 모니터링 → 유의미한 변화 감지 시 개인화된 인사이트 푸시. Tableau Pulse 패턴 참조. 상세 계획 별도 수립. |

---

## Phase 8: 소방 도메인 특화

> Phase 1~5의 범용 플랫폼 위에 소방 전문 기능을 올린다.
> **의존**: Phase 1 (GIS), Phase 3 (Text-to-SQL)

| # | 작업 | 상태 | 범위 | 검증 기준 |
|---|------|------|------|----------|
| 8-1 | 소방 도메인 CRUD API | ⬜ | Backend | 조직/사건/소방용수/출동 REST API + 권한. 시드 데이터 생성. TC 20개+. |
| 8-2 | 소방 KPI 대시보드 | ⬜ | Frontend | 응답시간, 출동 건수, 사건 유형 분포, 소방서별 성과 비교 위젯. |
| 8-3 | 소방 전용 지도 | ⬜ | Frontend | V-World 배경지도 + 소방서/소화전/사건 레이어 + 관할구역 경계 + 히트맵. |
| 8-4 | AI 소방 분석 도구 | ⬜ | AI Agent | 소방 MCP 도구 + 소방 특화 프롬프트 + fire 스키마 Text-to-SQL. |
| 8-5 | 공공데이터 ETL 연동 | ⬜ | Backend | 소방용수/소방서 좌표(data.go.kr), 행정경계(V-World), 지오코딩(Kakao). |

---

## Phase 4 후속 과제

> Phase 4에서 식별된 기술 부채 및 개선 항목. 별도 PR로 진행.

| # | 작업 | 우선순위 | 범위 | 설명 |
|---|------|---------|------|------|
| 4-F1 | DashboardService 서비스 분리 | P:높음 | Backend | 730줄 단일 클래스 → SystemHealthService + AttentionItemService + ActivityFeedService 3개 분리 (SRP) |
| 4-F2 | Activity feed SQL UNION ALL 페이지네이션 | P:높음 | Backend | 현재 인메모리 정렬/페이지네이션 (최대 1000건) → DB 레벨 UNION ALL + ORDER BY + LIMIT/OFFSET |
| 4-F3 | SSE 티켓 기반 인증 | P:보통 | Backend + Frontend | JWT-in-URL → POST /notifications/ticket (30초 TTL 일회용 토큰) → 쿼리파라미터에 티켓 사용 |
| 4-F4 | 알림 브로드캐스트 스코핑 | P:보통 | Backend | broadcastAll → 데이터셋 소유자/접근 권한자에게만 스코핑 |
| 4-F5 | 시스템 건강 DB 쿼리 통합 | P:보통 | Backend | getSystemHealth() 9개 순차 쿼리 → FILTER 집계 3-4개로 통합 |
| 4-F6 | 홈 대시보드 컴포넌트 분리 | P:낮음 | Frontend | HomePage.tsx 560줄 → Zone별 5개 컴포넌트 추출 |
| 4-F7 | 홈 API 엔드포인트 통합 | P:낮음 | Backend + Frontend | /stats + /health + /attention → /dashboard/summary 단일 엔드포인트 |

---

## 백로그 (Backlog)

> 우선순위 미정. 아이디어 수집 및 향후 Phase 편입 검토.

### 플랫폼

| # | 아이디어 | 우선순위 | 메모 |
|---|---------|---------|------|
| BL-01 | 알림 시스템 (인앱 + Webhook + Slack) | P:높음 | SSE 인프라 구축 완료 (Phase 4-3). 인앱 알림 히스토리 + 외부 연동 추가 필요. |
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
| Python/FastAPI + nsjail | 5.7 | 사용자 코드 실행 엔진 (firehub-executor) |
| deck.gl | 8-3 | 대규모 데이터 시각화 (히트맵) |
| V-World WMTS | 8-3 | 한국 배경지도 |

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
| 2026-04-02 | Phase 7 "AI 리포트 고도화" 신설 (11개 작업). 기존 Phase 7 "소방 도메인 특화"를 Phase 8로 이동 (번호 재부여 8-1~8-5). 16개 서비스 벤치마킹 기반 (Tableau Pulse, Hex, Looker, Julius AI, Datadog Watchdog, Wordsmith 등). 4-Layer 실행 순서: Layer 0 사용성 개선 (이메일 수신자 지정, 실행 결과 보기, 작업/템플릿 UX) → Layer 1 Quick Wins (PDF/Slack/내러티브) → Layer 2 핵심 차별화 (이상탐지/비주얼빌더/목표기반) → Layer 3 고급 (역할별 개인화/KPI 팔로우). |
| 2026-03-28 | Phase 6-1 (Generative UI + 딥링크) 완료. WidgetRegistry 패턴 + 4종 위젯 (show_dataset, show_table, navigate_to, show_chart 어댑터). 테이블 UX 개선 (타입별 렌더링, 컬럼 드롭다운 필터, 번호 페이지네이션, CSV/JSON 내보내기). 쿼리 캐시 자동 갱신. MCP 도구 등록 공통화 (registerAllTools). 디자인 시스템 가이드라인 업데이트. |
| 2026-03-28 | Phase 6 ↔ Phase 7 교체. AI Chat Generative UI를 Phase 6으로, 소방 도메인 특화를 Phase 7로 순서 변경. 작업 번호 재부여 (6-1~6-3, 7-1~7-5). |
| 2026-03-28 | Phase 5.9 (UI/UX 개선 + 멀티 AI 프로바이더) 추가 및 완료. AI 상태 칩 + 멀티 테마 (Indigo/Ocean/Sunset) + Provider 패턴 멀티 AI 프로바이더 리팩터링 + 운영 안정화 버그 수정 다수 (SSE 타임아웃, 파일 첨부, 다크 테마 등). |
| 2026-03-14 | Phase 5.7 (firehub-executor 실행 엔진 분리) 추가. 사용자 코드 실행을 Python/FastAPI 독립 서비스로 분리. Phase 2a (Python+분석쿼리) → Phase 2b (SQL+API_CALL) 단계적 마이그레이션. nsjail 샌드박싱. Architect+Critic 합의 완료. 상세 계획: `.omc/plans/pipeline-sandbox-phase2-service-separation.md` |
| 2026-03-14 | Phase 7 (AI Chat Generative UI) 추가. AI 챗 인라인 위젯 7종 + 딥링크 네비게이션 + Chat-First 3단계 확장 계획. Tool-to-Component + Reference 패턴. Architect+Critic 합의 완료. 상세 계획: `.omc/plans/ai-chat-generative-ui.md` |
| 2026-03-07 | Phase 5 완료 (5-1, 5-2). 데이터 내보내기 CSV/Excel/GeoJSON 3포맷. Sync/Async 이원화 (50K row 기준). ExportDialog + 비동기 진행률 UI. 쿼리 에디터 내보내기 통합. Rate limiting + 감사 로그 + 파일 정리. 통합 테스트 13개 통과. Architect 검증 13/13 PASS. |
| 2026-03-03 | Phase 4 완료 (4-1~4-6). 홈 대시보드 5-Zone 리디자인 + SSE 실시간 알림 + 위젯 신선도 UX. 코드 리뷰(CRITICAL 1 + HIGH 3 + MEDIUM 5) 및 Simplify(9건) 수정 완료. 후속 과제 7건 식별. 전체 테스트 통과. |
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
