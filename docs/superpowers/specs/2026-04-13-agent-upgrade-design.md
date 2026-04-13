# 전체 AI 에이전트 고도화 설계

**날짜**: 2026-04-13
**범위**: firehub-ai-agent 서브에이전트 11개 전체

---

## 배경 및 목표

초기에 구현된 4개 에이전트(pipeline-builder, report-writer, smart-job-manager, template-builder)와
최근 구현된 7개 에이전트 간 품질 격차가 있다.
이번 고도화에서 전체 11개를 동일한 품질 기준으로 통일하고,
역할별로 필요한 도구를 추가한다.

---

## 표준 프롬프트 구조 (11개 공통)

모든 에이전트의 agent.md는 다음 섹션 순서를 따른다:

```
# [에이전트명] — [한 줄 역할]

## 역할           (2~3줄 요약)
## 담당 / 비담당  (역할 경계 명확화)
## [N]단계 워크플로
## 보안 원칙
## 응답 포맷 원칙
## 규칙
```

**추가 규칙:**
- frontmatter `tools`는 와일드카드(`*`) 없이 사용하는 도구를 명시 열거
- `maxTurns`는 역할 복잡도에 맞게 재검토

---

## 에이전트 그룹 분류

### 그룹 1 — 빌더형 (코드·설계 생성)

| 에이전트 | 특징 |
|----------|------|
| pipeline-builder | Python/SQL 코드 작성, 로컬 테스트 실행 |

**도구 추가:**
- `Bash` — Python 스크립트 로컬 실행 검증
- `Write` — /tmp 테스트 파일 작성
- `WebSearch` — 라이브러리·SQL 문법 조회
- `Read`, `Grep`, `Glob` — 기존 유지
- `mcp__firehub__*` 와일드카드 → 명시적 도구 목록으로 교체

**명시적 MCP 도구 목록 (pipeline-builder):**
(구현 시 `firehub-api`의 실제 등록 도구명 확인 후 확정)
- mcp__firehub__get_data_schema
- mcp__firehub__get_dataset
- mcp__firehub__list_datasets
- mcp__firehub__create_pipeline
- mcp__firehub__update_pipeline
- mcp__firehub__delete_pipeline
- mcp__firehub__list_pipelines
- mcp__firehub__get_pipeline
- mcp__firehub__execute_pipeline
- mcp__firehub__get_execution_status
- mcp__firehub__list_executions

### 그룹 2 — 분석형 (데이터·로그 조회)

| 에이전트 | 도구 추가 |
|----------|-----------|
| data-analyst | `WebSearch` |
| audit-analyst | `WebSearch` |

### 그룹 3 — 관리형 (API 호출만)

도구 변경 없음. 프롬프트 개선만.

| 에이전트 |
|----------|
| dataset-manager |
| api-connection-manager |
| trigger-manager |
| dashboard-builder |
| admin-manager |
| smart-job-manager |
| template-builder |
| report-writer |

---

## 담당 / 비담당 표 (신규 추가 대상)

data-analyst·audit-analyst는 기존에 이미 있으므로 제외.

| 에이전트 | 담당 핵심 | 비담당 → 위임 대상 |
|----------|-----------|-------------------|
| pipeline-builder | 파이프라인 설계·생성·수정, Python/SQL 코드 작성·로컬 테스트, 실행·검증 | 데이터셋 생성 → dataset-manager, 트리거 → trigger-manager, 차트/리포트 → data-analyst |
| report-writer | HTML/마크다운/요약 파일 생성 | 데이터 수집·분석 → data-analyst, 양식 설계 → template-builder, 스마트 작업 등록 → smart-job-manager |
| smart-job-manager | 스마트 작업 생성·수정·삭제, 실행 이력 분석, 문제 진단 | 리포트 양식 설계 → template-builder, 데이터 분석 → data-analyst, 파이프라인 관리 → pipeline-builder |
| template-builder | 리포트 양식 설계·생성·수정 | 리포트 생성 → report-writer, 스마트 작업 등록 → smart-job-manager |
| dataset-manager | 데이터셋 CRUD, CSV 임포트, GIS 자동 감지 | 데이터 분석 → data-analyst, 파이프라인 실행 → pipeline-builder |
| api-connection-manager | API 연결 생성·수정·삭제 | API_CALL 스텝 실행 → pipeline-builder, 트리거 설정 → trigger-manager |
| trigger-manager | 트리거 설계·생성·수정·삭제 | 파이프라인 생성 → pipeline-builder, 스케줄 작업 → smart-job-manager |
| dashboard-builder | 대시보드·위젯 생성·레이아웃 설정 | 쿼리·차트 데이터 분석 → data-analyst |
| admin-manager | 사용자 조회·역할 교체·계정 관리 | 감사 로그 분석 → audit-analyst |

---

## 보안 원칙 (그룹별)

### 빌더형 (pipeline-builder)
1. **Python 코드 안전성**: `eval()`·`exec()` 금지. import는 pandas·numpy·datetime·json·re만 허용
2. **로컬 파일 범위**: Bash·Write 도구는 `/tmp` 디렉토리만 사용
3. **SQL 안전성**: 사용자 입력값 직접 삽입 금지. 컬럼명·테이블명은 스키마에서 확인된 것만 사용
4. **파괴적 작업**: 파이프라인 생성·수정·삭제 전 사용자 확인 필수
5. **WebSearch**: 기술 참조(라이브러리·문법) 목적만. 내부 데이터를 외부에 전달 금지

### 분석형 (data-analyst, audit-analyst)
**data-analyst:**
1. **읽기 전용**: `execute_analytics_query`만 사용. `execute_sql_query` 금지
2. **WebSearch**: SQL 패턴·통계 기법 참조 목적만. 쿼리 결과 데이터를 외부 전달 금지

**audit-analyst:**
1. **권한 부족 시 명확히 안내**: "이 작업은 audit:read 권한이 필요합니다. 관리자에게 문의하세요."
2. **민감 정보 표시 주의**: ipAddress·userAgent는 개인정보 포함 가능. 필요한 경우에만 표시
3. **읽기 전용 에이전트**: 감사 로그 조회만. 어떤 데이터도 수정하지 않음
4. **WebSearch**: 보안 패턴·CVE 조회 목적만. 감사 데이터를 외부 전달 금지

### 관리형 (9개 공통 패턴)
1. **파괴적 작업**: 삭제·비활성화 전 반드시 사용자 확인 후 실행
2. **민감 정보**: 비밀번호·토큰·개인정보를 응답에 직접 노출 금지
3. **권한 부족 시**: "이 작업은 [권한명] 권한이 필요합니다" 명확히 안내

---

## 워크플로 변경 사항

### pipeline-builder — 6단계 → 7단계

**기존:**
DISCOVER → DESIGN → CREATE → EXECUTE → VERIFY → REPORT

**변경 (LOCAL_TEST 추가):**
DISCOVER → DESIGN → LOCAL_TEST → CREATE → EXECUTE → VERIFY → REPORT

**LOCAL_TEST 단계 상세:**
1. Write로 `/tmp/test_step_{N}.py` 작성
2. Bash로 실행: `python3 /tmp/test_step_{N}.py`
3. stdout이 JSON 배열 형식인지 확인
4. 오류 시 코드 수정 후 재실행 (최대 3회)
5. 모든 Python 스텝 통과 후 다음 단계로
6. SQL 스텝은 스키마 확인으로 대체 (로컬 실행 불필요)

### report-writer — 3단계 → 4단계

**기존:**
확인 → 작성 → 저장

**변경 (VERIFY 추가):**
확인 → 작성 → 저장 → VERIFY

**VERIFY 단계 상세:**
1. 컨텍스트에서 생성한 HTML 내 `<section>` 수와 양식 섹션 수 일치 여부 확인
2. summary.md가 3~5줄 범위인지 확인
3. 누락 섹션 발견 시 해당 파일을 Write로 재생성
- (report-writer는 Write 전용이므로 파일 읽기 없이 컨텍스트 기반 검토)

나머지 9개 에이전트는 워크플로 구조 유지. 보안·에러처리 섹션 추가만.

---

## 구현 계획 (레이어별 병렬 처리)

### Layer 1 (병렬)
- `pipeline-builder` — 도구 교체 + 7단계 워크플로 + 보안 (변경 최대)
- `report-writer` — 4단계 워크플로 + 담당/비담당 + 보안

### Layer 2 (병렬)
- `data-analyst` — WebSearch 추가 + 보완
- `audit-analyst` — WebSearch 추가 + 보완
- `smart-job-manager` — 담당/비담당 + 보안
- `template-builder` — 담당/비담당 + 보안

### Layer 3 (병렬)
- `dataset-manager`, `api-connection-manager`, `trigger-manager`, `dashboard-builder`, `admin-manager`
  — 담당/비담당 + 보안 (패턴 통일)

---

## 검증 기준

각 에이전트 수정 후 체크리스트:
- [ ] frontmatter `tools` 목록이 명시적 (와일드카드 없음)
- [ ] `담당/비담당` 표 존재
- [ ] `보안 원칙` 섹션 존재
- [ ] 워크플로 단계가 논리적으로 완결됨
- [ ] 기존 동작하던 로직이 제거되지 않음

---

## 산출물

- `agent.md` 11개 수정
- 커밋 1개 (feat(ai-agent): 전체 서브에이전트 고도화)
