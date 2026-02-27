# Smart Fire Hub — 리딩 데이터 플랫폼 비교 분석 및 로드맵

## 1. 현재 상태 요약

### 구현 완료

| 영역 | 현황 | 완성도 |
|------|------|--------|
| **데이터셋 관리** | CRUD, 컬럼 관리, 태그, 즐겨찾기, 상태(CERTIFIED/DEPRECATED), 클론, 통계 | 95% |
| **데이터 임포트/익스포트** | CSV/XLSX 업로드, 미리보기, 검증, 비동기 처리, CSV 내보내기 | 90% |
| **파이프라인/ETL** | 3종 스텝(SQL/Python/API_CALL), DAG 실행(Kahn 알고리즘), 비주얼 에디터(@xyflow) | 95% |
| **트리거 시스템** | SCHEDULE(cron), API(token), WEBHOOK(HMAC), PIPELINE_CHAIN, DATASET_CHANGE | 95% |
| **AI 에이전트** | 36개 MCP 도구, Claude Agent SDK, SSE 스트리밍, 세션 관리/컴팩션 | 100% |
| **AI 채팅 UI** | 사이드 패널/플로팅/풀스크린, Cmd+K, 마크다운 렌더링, 세션 관리 | 100% |
| **인증/인가** | JWT(access 30m + refresh 7d), RBAC, 리소스 기반 퍼미션 | 90% |
| **감사 로그** | 사용자 행위 추적, JSONB 메타데이터, 필터링/페이지네이션 | 85% |
| **API 연결 관리** | CRUD, AES-256-GCM 암호화 (API_KEY/BEARER/OAUTH2) | 90% |
| **대시보드** | 4개 요약 카드, 최근 활동 위젯 (임포트 + 파이프라인 실행) | 60% |
| **관리자 패널** | 사용자/역할/감사/AI설정/API연결 관리 | 85% |

### 미구현 영역 (Gap)

| 영역 | 현재 | 리딩 플랫폼 기준 |
|------|------|------------------|
| **데이터 리니지** | 없음 | Palantir: 자동 리니지 + 사용자 귀속, Databricks: Unity Catalog 리니지 |
| **데이터 카탈로그** | 카테고리/태그만 | OpenMetadata: 통합 메타데이터 그래프, Snowflake: Marketplace |
| **데이터 품질** | 컬럼 통계만 | Ataccama: AI 기반 프로파일링/룰/이상탐지 |
| **데이터 옵저버빌리티** | 없음 | Palantir: Workflow Lineage 트레이싱, 실시간 로그 |
| **고급 대시보드** | 기본 카드만 | Databricks Apps: 차트/시각화 대시보드 |
| **데이터셋 버전 관리** | 없음 | Palantir: Git for Data (SNAPSHOT/APPEND/UPDATE/DELETE) |
| **알림/노티피케이션** | 없음 | 모든 플랫폼: 파이프라인 실패 알림, Slack/이메일 연동 |
| **협업** | RBAC만 | 데이터셋 공유, 코멘트, @mention, 워크스페이스 |
| **데이터 마스킹** | 없음 | Snowflake: 동적 데이터 마스킹, 행 수준 보안 |

---

## 2. 리딩 플랫폼 비교 매트릭스

```
                     Smart Fire Hub   Databricks   Snowflake   Palantir Foundry
──────────────────────────────────────────────────────────────────────────────────
데이터셋 CRUD           ●               ●            ●            ●
임포트/익스포트         ●               ●            ●            ●
파이프라인/ETL          ●               ●            ○            ●
비주얼 파이프라인       ●               ●            ○            ●
트리거/스케줄링         ●               ●            ●            ●
AI 에이전트            ●               ◐            ◐            ●
SQL 쿼리 에디터        ●               ●            ●            ●
RBAC                   ●               ●            ●            ●
감사 로그              ●               ●            ●            ●
──────────────────────────────────────────────────────────────────────────────────
데이터 리니지           ○               ●            ●            ●
데이터 카탈로그         ◐               ●            ●            ●
데이터 품질 규칙        ○               ●            ◐            ●
옵저버빌리티           ○               ●            ◐            ●
고급 대시보드/차트      ○               ●            ●            ◐
데이터셋 버전관리       ○               ●            ●            ●
알림 시스템            ○               ●            ●            ●
협업 기능              ○               ●            ◐            ●
Text-to-SQL           ◐               ●            ●            ●
데이터 마스킹          ○               ●            ●            ●

● = 완전 구현  ◐ = 부분 구현  ○ = 미구현
```

---

## 3. Smart Fire Hub 차별화 포인트

**AI-First 아키텍처가 최대 강점이다.** 36종 MCP 도구 + Claude Agent SDK 기반의 AI 에이전트가 이미 플랫폼의 핵심으로 동작하고 있다. Databricks/Snowflake가 AI 기능을 뒤늦게 추가하는 반면, Smart Fire Hub는 설계 단계부터 AI-First로 구축되었다. 이 강점을 더 확장하면서 부족한 거버넌스/리니지를 채우는 것이 전략적으로 올바르다.

---

## 4. 우선순위별 로드맵

### Phase 0: 보안/안정성 기반 강화 (P0 — 즉시)

| # | 작업 | 복잡도 |
|---|------|--------|
| 0-1 | JWT 시크릿 키 환경변수화 (application-local.yml 하드코딩 제거) | S |
| 0-2 | CORS 설정 추가 (SecurityConfig) | S |
| 0-3 | 로그인 brute-force 방어 (5회 실패 → 15분 차단) | M |
| 0-4 | Refresh token rotation + 재사용 탐지 | M |
| 0-5 | GlobalExceptionHandler catch-all 추가 | S |
| 0-6 | Security 헤더 추가 (X-Content-Type-Options, HSTS 등) | S |

### Phase 1: 데이터 리니지 & 카탈로그 (P1 — 1차 핵심)

> 리딩 플랫폼 대비 가장 큰 갭. 모든 거버넌스 기능의 기반이 됨.

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 1-1 | **데이터 리니지 모델 설계** | L | `dataset_lineage` 테이블: 파이프라인 실행 시 input/output 데이터셋 관계 자동 기록 |
| 1-2 | **파이프라인 실행 리니지 자동 수집** | L | PipelineExecutionService에서 step별 input→output 관계 추출/저장 |
| 1-3 | **리니지 그래프 API** | M | `GET /api/v1/lineage/dataset/{id}` — upstream/downstream 그래프 반환 |
| 1-4 | **리니지 시각화 UI** | L | @xyflow/react 기반 인터랙티브 리니지 그래프 (파이프라인 에디터 라이브러리 재활용) |
| 1-5 | **영향도 분석 (Impact Analysis)** | M | 데이터셋 변경/삭제 시 영향받는 하위 파이프라인/데이터셋 표시 |
| 1-6 | **데이터 카탈로그 검색 강화** | M | 전체 텍스트 검색 (이름+설명+태그+컬럼명), 필터 조합, 정렬 옵션 |
| 1-7 | **AI 에이전트 리니지 도구 추가** | M | `get_dataset_lineage`, `get_impact_analysis` MCP 도구 |

**재활용 가능 자산**: `@xyflow/react` + `dagre` (파이프라인 에디터), `pipeline_execution` 테이블, `pipeline_step.output_dataset_id`

### Phase 2: 알림 & 옵저버빌리티 (P1 — 1차 핵심)

> 파이프라인 실행 결과를 실시간으로 파악할 수 없는 것은 운영 플랫폼으로서 치명적

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 2-1 | **알림 모델 설계** | M | `notification` 테이블: 타입, 읽음 상태, 사용자별 |
| 2-2 | **인앱 알림 API + 실시간 전달** | M | SSE 또는 폴링 기반 실시간 알림 |
| 2-3 | **알림 UI (벨 아이콘 + 드롭다운)** | M | 헤더에 알림 아이콘, unread 카운트, 알림 목록 패널 |
| 2-4 | **파이프라인 실행 완료/실패 시 알림 생성** | S | PipelineExecutionService 후처리에 알림 생성 |
| 2-5 | **외부 알림 연동 (Webhook/이메일)** | L | Slack webhook, 이메일 (SMTP) |
| 2-6 | **파이프라인 모니터링 대시보드** | L | 실행 이력 타임라인, 성공/실패 비율 차트, 평균 실행 시간 |

### Phase 3: 데이터 품질 프레임워크 (P1)

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 3-1 | **데이터 품질 규칙 모델** | M | NOT_NULL, UNIQUE, RANGE, REGEX, CUSTOM_SQL 타입 |
| 3-2 | **품질 규칙 실행 엔진** | L | 규칙 일괄 실행, 결과 저장 |
| 3-3 | **품질 규칙 CRUD API + UI** | M | 규칙 생성/편집/삭제, 결과 히스토리 뷰 |
| 3-4 | **파이프라인 연동** | M | 스텝 후 자동 품질 검사, 실패 시 파이프라인 중단 옵션 |
| 3-5 | **품질 대시보드** | M | 데이터셋별 품질 점수, 규칙 통과율, 추이 차트 |
| 3-6 | **AI 에이전트 품질 도구** | M | `create_quality_rule`, `run_quality_check` MCP 도구 |

### Phase 4: 고급 대시보드 & 시각화 (P2)

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 4-1 | **차트 라이브러리 도입** | S | Recharts 또는 Apache ECharts |
| 4-2 | **대시보드 위젯 확장** | L | 파이프라인 실행 추이, 데이터셋 성장, 사용자 활동 히트맵 |
| 4-3 | **데이터셋 프로파일링 뷰** | M | 컬럼별 분포 히스토그램, 이상치 표시 |
| 4-4 | **사용자 정의 대시보드** | XL | 위젯 드래그&드롭 배치, 필터, 저장/공유 (장기) |

### Phase 5: AI 에이전트 강화 — Text-to-SQL & 대화형 분석 (P2)

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 5-1 | **스키마 인식 Text-to-SQL** | M | 데이터셋 스키마/샘플 데이터를 AI 컨텍스트로 제공 |
| 5-2 | **대화형 데이터 분석** | L | AI가 차트 생성 제안, 쿼리 결과 요약, 인사이트 도출 |
| 5-3 | **AI 파이프라인 생성 어시스턴트** | L | 자연어 → 파이프라인 자동 생성 |
| 5-4 | **데이터 품질 AI 제안** | M | 데이터 프로파일 분석 → 품질 규칙 자동 제안 |
| 5-5 | **AI 리니지 탐색** | S | "이 데이터 어디서 왔어?" → 리니지 + 설명 |

### Phase 6: 협업 & 거버넌스 강화 (P2-P3)

| # | 작업 | 복잡도 | 상세 |
|---|------|--------|------|
| 6-1 | **데이터셋 코멘트/주석** | M | 데이터셋/컬럼에 코멘트, @mention |
| 6-2 | **데이터셋 공유/접근 제어** | L | 데이터셋별 소유자/접근 권한 |
| 6-3 | **비즈니스 용어집** | M | 용어 정의 → 데이터셋/컬럼 매핑 |
| 6-4 | **데이터 마스킹** | L | 민감 컬럼 마스킹 정책 |
| 6-5 | **데이터셋 버전 관리** | XL | 트랜잭션 기반 버전 관리 (SNAPSHOT/APPEND) |

### Phase 7: 장기 진화 (P3)

| # | 작업 | 복잡도 |
|---|------|--------|
| 7-1 | 실시간 데이터 처리 (Streaming/CDC) | XL |
| 7-2 | 외부 데이터 마켓플레이스 | XL |
| 7-3 | ML 모델 통합 (파이프라인 내 추론 스텝) | XL |
| 7-4 | 멀티테넌시 (조직/워크스페이스별 격리) | XL |

---

## 5. 추천 실행 순서

```
즉시(Phase 0)     → 보안 기반 강화 (1-2일)
1차(Phase 1+2)    → 데이터 리니지 + 알림 시스템 (2-3주)
                     ← 가장 큰 갭 해소, 운영 플랫폼 필수
2차(Phase 3)      → 데이터 품질 프레임워크 (2주)
                     ← 거버넌스 완성의 핵심 퍼즐
3차(Phase 4+5)    → 고급 대시보드 + AI 강화 (2-3주)
                     ← 차별화 포인트 극대화
4차(Phase 6+7)    → 협업/거버넌스 + 장기 진화 (ongoing)
```

**1차 구현 추천: Phase 1 (데이터 리니지) + Phase 2 (알림)**
- 리딩 플랫폼 대비 가장 큰 격차 해소
- 기존 인프라(@xyflow, pipeline_execution 테이블) 재활용으로 효율적 구현
- 후속 Phase 3-6의 기반 역할

---

## References

- [Palantir Foundry](https://www.palantir.com/platforms/foundry/)
- [Palantir Data Lineage](https://www.palantir.com/docs/foundry/data-lineage/build-datasets)
- [Palantir Pipeline Builder](https://www.palantir.com/docs/foundry/pipeline-builder/overview)
- [OpenMetadata](https://open-metadata.org/)
- [Best Data Governance Platforms 2025](https://www.getcollate.io/learning-center/data-governance-platforms)
- [Top Data Lineage Tools 2025](https://www.ataccama.com/blog/top-data-lineage-tools-in-2025)
- [Top Data Catalog Tools 2025](https://lakefs.io/blog/top-data-catalog-tools/)
