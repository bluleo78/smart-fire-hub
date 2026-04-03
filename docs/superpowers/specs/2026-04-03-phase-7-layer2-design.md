# Phase 7 Layer 2: AI 리포트 고도화 — 설계 문서

> **작성일**: 2026-04-03
> **범위**: 7-4 (이상 탐지 + 자동 알림), 7-5 (비주얼 리포트 빌더 + 양식 구조 개선), 7-6 (목표 기반 리포트 생성)
> **의존**: Phase 7 Layer 1 완료 (7-1 PDF, 7-3 내러티브)

---

## 1. 실행 순서

```
양식 구조 개선 (7-5 선행 파트)
  ↓
7-4 (BE+AI) + 7-5 빌더 UI (FE) 병렬
  ↓
7-6 (FE+AI, 7-4+7-5 의존)
```

---

## 2. 리포트 양식 구조 개선 (7-5 선행)

### 2.1 현재 문제점

1. **용어 혼재**: AI 지시가 `style`(템플릿), `prompt`(작업), `description`(섹션) 3곳에 분산. 역할 불명확.
2. **평면 구조**: 섹션이 1단 배열. 챕터/그룹 개념 없음.
3. **섹션별 AI 지시 누락**: `description`이 UI 가이드용으로만 쓰이고, AI 프롬프트에 미전달.
4. **정적 콘텐츠 미지원**: 모든 섹션을 AI가 채움. 고정 텍스트(면책조항 등) 불가.

### 2.2 섹션 스키마 확장

**현재 스키마:**
```json
{
  "key": "summary",
  "label": "요약",
  "type": "text",
  "required": true,
  "description": "..."
}
```

**개선 스키마:**
```json
{
  "key": "chapter_operations",
  "label": "운영 현황",
  "type": "group",
  "instruction": "이 챕터에서는 전체 시스템 운영 상태를 분석하세요.",
  "children": [
    {
      "key": "kpi_cards",
      "label": "핵심 지표",
      "type": "cards",
      "required": true,
      "instruction": "파이프라인 성공률, 데이터셋 건수, 활성 사용자 수를 카드로 표시하세요. 전일 대비 변화율을 description에 포함할 것."
    },
    {
      "key": "trend_analysis",
      "label": "트렌드 분석",
      "type": "text",
      "instruction": "전주 대비 변화 추이를 서술하세요."
    }
  ]
}
```

**새 필드:**

| 필드 | 타입 | 설명 |
|------|------|------|
| `instruction` | string | AI에게 전달되는 섹션별 분석 지시 |
| `children` | array | 하위 섹션 배열 (`group` 타입만 사용) |
| `static` | boolean | `true`이면 AI가 채우지 않는 정적 콘텐츠 |
| `content` | string | 정적 섹션의 고정 텍스트 (변수 치환 지원) |

**기존 필드 역할 정리:**

| 필드 | 역할 | AI 프롬프트 전달 |
|------|------|-----------------|
| `description` | UI 편집 화면에서 보이는 도움말 | X (UI 전용) |
| `instruction` | AI에게 전달되는 섹션별 분석 지시 | O (신규) |

### 2.3 계층 구조

- 최대 **3단계** 중첩: 챕터(group) → 섹션 → 서브섹션
- `group` 타입만 `children`을 가질 수 있음
- `group` 안의 `group`은 2단계까지만 허용 (3단계 group 중첩 불가)
- 검증: 프론트엔드 + 백엔드 양쪽에서 깊이 제한 검증

### 2.4 정적 콘텐츠

**정적 섹션 예시:**
```json
{
  "key": "disclaimer",
  "label": "면책조항",
  "type": "text",
  "static": true,
  "content": "본 리포트는 {{date}} 기준 자동 생성되었으며, 참고용입니다."
}
```

**지원 변수:**

| 변수 | 값 |
|------|-----|
| `{{date}}` | 실행 일시 (yyyy-MM-dd HH:mm) |
| `{{jobName}}` | 스마트 작업 이름 |
| `{{author}}` | 작업 생성자 이름 |
| `{{templateName}}` | 템플릿 이름 |
| `{{period}}` | 분석 기간 (config에 설정된 경우) |

**변수 치환 시점**: 백엔드에서 리포트 렌더링 시 (`ReportRenderUtils`)

### 2.5 섹션 타입 확장

기존 9개 + 신규 2개:

| 타입 | 용도 | AI 생성 |
|------|------|---------|
| `group` | 챕터/그룹 컨테이너 | - (자식만 생성) |
| `text` | 마크다운 서술 | O |
| `cards` | KPI 카드 + 수치 | O |
| `list` | 순위/목록 | O |
| `table` | 표 형식 | O |
| `comparison` | 기간 비교 | O |
| `alert` | 경고/알림 | O |
| `timeline` | 시간순 이벤트 | O |
| `chart` | 차트 해석 서술 | O |
| `recommendation` | 권고사항 | O |
| `divider` | 구분선 (신규) | X (정적) |

**정적 콘텐츠 표현**: 별도 `static-text` 타입 대신 기존 타입에 `static: true` 플래그를 사용.
예: `{ "type": "text", "static": true, "content": "고정 텍스트..." }`. `divider`는 암묵적 정적.

### 2.6 지시 체계 (Instruction Hierarchy)

AI 프롬프트에 3단계 계층적 지시가 전달됨:

```
1. 템플릿 레벨: style — 전체 톤/스타일
   "간결한 경영진 보고 스타일. 핵심 변화를 먼저 서술."

2. 섹션 레벨: instruction — 각 섹션에서 분석할 내용
   "파이프라인 성공률, 데이터셋 건수를 카드로 표시하세요."

3. 작업 레벨: prompt — 이번 실행에서 집중할 포인트
   "이번 주 특히 파이프라인 실패가 많았으니 원인 분석에 집중해줘."
```

**프롬프트 빌드 순서** (`buildProactiveSystemPrompt` 개선):
```
시스템 프롬프트
  + 분석 원칙 (기존)
  + 템플릿 style (톤/스타일)
  + 섹션 구조 (## 헤더 + type별 가이드 + instruction)
  + 정적 섹션 표시 ("이 섹션은 정적이므로 생성하지 마세요")

사용자 메시지
  + 작업 prompt (분석 지시)
  + context (수집된 데이터)
```

### 2.7 백엔드 변경

**DB 마이그레이션**: 불필요 — `sections`가 이미 JSONB이므로 스키마 변경 없이 JSON 구조만 확장.

**AI Agent (`proactive.ts`) 변경:**
- `buildProactiveSystemPrompt()`: 계층 구조 순회, `instruction` 포함, 정적 섹션 스킵 표시
- `parseSections()`: 계층 구조 파싱 (## / ### / #### 헤더 매핑)
- `getSectionTypeGuide()`: `group`, `divider` 타입 추가, `static` 플래그 처리

**백엔드 (`ReportRenderUtils`) 변경:**
- 계층 구조 렌더링 (그룹 → 하위 섹션)
- 정적 섹션 변수 치환
- `divider` 섹션 렌더링 (HTML `<hr>`)
- PDF/이메일 템플릿에 계층 구조 반영

**빌트인 템플릿 마이그레이션:**
- 기존 3개 빌트인 템플릿에 `instruction` 필드 추가 (DB seed update)
- 기존 `description`은 유지 (하위 호환)

### 2.8 프론트엔드 변경

**타입 정의 (`proactive.ts`) 변경:**
```typescript
export interface TemplateSection {
  key: string;
  type: SectionType | 'group' | 'divider';
  label: string;
  description?: string;     // UI 가이드용 (기존)
  instruction?: string;     // AI 지시 (신규)
  required?: boolean;
  static?: boolean;         // 정적 콘텐츠 여부 (신규)
  content?: string;         // 정적 텍스트 + 변수 (신규)
  children?: TemplateSection[];  // 하위 섹션 (신규)
}
```

**`template-section-types.ts` 변경:**
- `group`, `divider` 타입 정의 추가

**섹션 깊이 검증 유틸:**
```typescript
function validateSectionDepth(sections: TemplateSection[], maxDepth = 3): boolean
```

---

## 3. 비주얼 리포트 빌더 UI (7-5)

### 3.1 레이아웃

```
┌─────────────────────────────────────────────────────┐
│ breadcrumb / 템플릿 이름                [취소][미리보기][저장] │
├──────────────────────────┬──────────────────────────┤
│ 템플릿 메타               │  [빌더] [JSON] ← 탭     │
│ (이름, 스타일 지시)       │                          │
├──────────────────────────┤  선택된 섹션 속성 편집    │
│ 섹션 구조        [8개]   │  - Label, Key, Type      │
│                          │  - Required, Static      │
│ 📁 운영 현황 (group)     │  - AI 지시 (instruction) │
│   📊 핵심 지표 (cards)*  │  - UI 설명 (description) │
│   📝 트렌드 (text)       │  - 타입 가이드           │
│ 📁 상세 분석 (group)     │  - [정적 시] 콘텐츠 편집 │
│   ⚠️ 주의 항목 (alert)   │    + 변수 칩             │
│   📈 차트 해석 (chart)   │                          │
│   💡 권고사항 (recom.)   │                          │
│ ➖ 구분선 [정적]         │                          │
│ 📄 면책조항 [정적]       │                          │
│                          │                          │
│ [+ 섹션 추가] [+ 그룹]   │                          │
└──────────────────────────┴──────────────────────────┘
```

참고 목업: `snapshots/visual-report-builder-mockup.png`

### 3.2 드래그앤드롭 동작

- **순서 변경**: 같은 레벨 내 드래그로 이동
- **계층 이동**: 그룹 위로 드래그하면 자식으로 들어감 (최대 3단계 제한, 초과 시 거부)
- **그룹 밖으로**: 그룹 바깥으로 드래그하면 상위 레벨로 이동
- **라이브러리**: `@dnd-kit/core` + `@dnd-kit/sortable` (React용, 트리 지원)

### 3.3 섹션 추가

- 하단 "[+ 섹션 추가]" 클릭 → 타입 선택 드롭다운/팔레트
- "[+ 그룹 추가]" → group 타입 섹션 생성 (기본 이름: "새 그룹")
- 자동 key 생성: `{type}_{counter}` (예: `text_1`, `cards_2`)
- 그룹 선택 상태에서 섹션 추가 시 해당 그룹의 자식으로 생성

### 3.4 빌더 ↔ JSON 동기화

- **빌더 → JSON**: 빌더에서 편집하면 즉시 JSON 반영
- **JSON → 빌더**: JSON 탭에서 직접 편집 후 빌더 탭으로 전환하면 파싱하여 반영
- **JSON 파싱 실패**: 빌더 탭에서 에러 배너 표시, 마지막 유효 상태 유지
- **JSON 에디터**: 기존 CodeMirror 재활용 (TemplateJsonEditor)

### 3.5 속성 편집 패널

선택된 섹션에 따라 표시 내용이 달라짐:

| 섹션 종류 | 표시 필드 |
|-----------|----------|
| 일반 (AI 생성) | Label, Key, Type, Required, Instruction, Description, 타입 가이드 |
| 정적 (static) | Label, Key, Type, Content(텍스트+변수칩) |
| 그룹 (group) | Label, Key, Instruction |
| 구분선 (divider) | (속성 없음 또는 최소) |

### 3.6 미리보기

기존 `SectionPreview` 확장:
- 계층 구조 시각화 (들여쓰기 + 트리라인)
- 정적 콘텐츠 변수 치환 미리보기 (예: `{{date}}` → `2026-04-03 09:00`)
- 섹션 타입별 아이콘 + 색상 코드

---

## 4. 이상 탐지 + 자동 알림 (7-4)

### 4.1 스마트 작업 트리거 확장

현재 스마트 작업은 Cron 스케줄로만 트리거됨. 이상 감지 트리거를 추가.

**DB 마이그레이션:**
```sql
-- proactive_job 테이블 확장
ALTER TABLE proactive_job ADD COLUMN trigger_type VARCHAR(20) DEFAULT 'SCHEDULE';
-- 값: 'SCHEDULE' (기존 Cron), 'ANOMALY' (이상 감지만), 'BOTH' (둘 다)

-- 메트릭 히스토리 테이블
CREATE TABLE metric_snapshot (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT REFERENCES proactive_job(id) ON DELETE CASCADE,
    metric_id VARCHAR(100) NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    collected_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_metric_snapshot_job_metric
    ON metric_snapshot(job_id, metric_id, collected_at DESC);
```

### 4.2 작업 config JSONB 확장

```json
{
  "channels": [...],
  "anomaly": {
    "enabled": true,
    "metrics": [
      {
        "id": "m1",
        "name": "일별 매출 합계",
        "source": "dataset",
        "datasetId": 5,
        "query": "SELECT SUM(amount) as value FROM sales WHERE date = CURRENT_DATE",
        "pollingInterval": 300
      },
      {
        "id": "m2",
        "name": "파이프라인 실패율",
        "source": "system",
        "metricKey": "pipeline_failure_rate",
        "pollingInterval": 60
      }
    ],
    "sensitivity": "medium",
    "cooldownMinutes": 60
  }
}
```

### 4.3 메트릭 소스

**시스템 메트릭** (`source: "system"`):

| metricKey | 설명 | 수집 방법 |
|-----------|------|----------|
| `pipeline_failure_rate` | 파이프라인 실패율 (최근 24시간) | 내부 통계 쿼리 |
| `pipeline_execution_count` | 파이프라인 실행 건수 | 내부 통계 쿼리 |
| `dataset_total_count` | 전체 데이터셋 수 | 내부 통계 쿼리 |
| `dataset_row_changes` | 데이터셋 row 변화량 | pg_stat_user_tables |
| `active_user_count` | 활성 사용자 수 (최근 24시간) | 로그인 기록 |

**데이터셋 메트릭** (`source: "dataset"`):
- 사용자가 SQL 쿼리를 등록 → 단일 숫자값 반환 필수
- 쿼리 실행: executor 서비스에 위임 (기존 인프라 활용)

### 4.4 이상 감지 흐름

```
1. MetricPollerService (@Scheduled, fixedDelay=30s)
   ├─ 활성화된 스마트 작업 중 anomaly.enabled=true인 것 조회
   ├─ 각 메트릭의 pollingInterval 경과 여부 확인
   ├─ 경과된 메트릭만 수집:
   │   ├─ system: 내부 통계 API 호출
   │   └─ dataset: executor에 쿼리 위임
   └─ 결과를 metric_snapshot에 저장

2. AnomalyDetector (메트릭 수집 직후 실행)
   ├─ 최근 N일(기본 14일) 히스토리 조회
   ├─ 이동평균 + 표준편차 계산
   ├─ sensitivity별 임계값:
   │   ├─ low: 3σ 이탈
   │   ├─ medium: 2σ 이탈 (기본)
   │   └─ high: 1.5σ 이탈
   ├─ 히스토리 부족 시 (최소 7개 미만) → 감지 보류
   └─ 이상 감지 시 → AnomalyEvent 발행

3. ProactiveJobService (AnomalyEvent 수신)
   ├─ cooldown 체크 (마지막 알림 이후 cooldownMinutes 경과?)
   ├─ 경과 안 됨 → 스킵
   ├─ 경과됨 → executeJob() 호출
   │   컨텍스트에 이상 정보 추가:
   │   {
   │     "anomaly": {
   │       "metricName": "일별 매출 합계",
   │       "currentValue": 150000,
   │       "expectedRange": { "mean": 250000, "stddev": 30000 },
   │       "deviation": -3.33,
   │       "history": [...]
   │     }
   │   }
   └─ AI가 원인 분석 + 리포트 생성 + 채널 전달
```

### 4.5 cooldown + 중복 방지

- **cooldownMinutes**: 이상 감지 후 알림 발송하면, 이 시간 동안 같은 작업에 대해 재알림 안 함
- **감도(sensitivity)**: 사용자가 low/medium/high 중 선택
- **trigger_type 공존**: `'BOTH'`이면 Cron 정기 리포트 + 이상 시 즉시 리포트 모두 발동
- **히스토리 관리**: `metric_snapshot`은 90일 이후 자동 정리 (배치)

### 4.6 스마트 작업 편집 UI (모니터링 탭)

스마트 작업 생성/편집 화면에 "모니터링" 탭 추가:

```
┌─────────────────────────────────────────┐
│ [기본] [스케줄] [모니터링] [채널]         │
├─────────────────────────────────────────┤
│ 이상 감지 활성화  [토글]                 │
│                                         │
│ 감도: [low ▼] [medium ▼] [high ▼]      │
│ 재알림 방지: [60] 분                     │
│                                         │
│ 모니터링 메트릭                          │
│ ┌───────────────────────────────────┐   │
│ │ 📊 파이프라인 실패율  [system]  [✕] │   │
│ │ 📊 일별 매출 합계     [dataset] [✕] │   │
│ └───────────────────────────────────┘   │
│                                         │
│ [+ 시스템 메트릭 추가] [+ 데이터셋 메트릭] │
└─────────────────────────────────────────┘
```

**시스템 메트릭 추가**: 드롭다운에서 선택 + 폴링 간격 설정
**데이터셋 메트릭 추가**: 데이터셋 선택 → SQL 쿼리 입력 → 폴링 간격 설정

### 4.7 백엔드 서비스 구조

| 클래스 | 역할 |
|--------|------|
| `MetricPollerService` | @Scheduled 메트릭 수집. 폴링 간격 관리. |
| `AnomalyDetector` | 통계 기반 이상 판단 (이동평균, 표준편차) |
| `MetricSnapshotRepository` | metric_snapshot CRUD |
| `ProactiveJobService` (확장) | AnomalyEvent 수신 → 작업 실행 |
| `ProactiveContextCollector` (확장) | anomaly 컨텍스트 추가 |

---

## 5. 목표 기반 리포트 생성 (7-6)

### 5.1 개요

현재: 사용자가 프롬프트 + 템플릿을 수동 설정하여 리포트 생성.
개선: "매출이 왜 떨어졌는지 분석해줘" 같은 비즈니스 질문에서 출발하여 AI가 분석 계획 → 데이터 탐색 → 리포트 생성.

### 5.2 진입 경로 A: AI 챗

```
사용자: "매출이 왜 떨어졌는지 분석해줘"
  → AI가 분석 계획 수립 (어떤 데이터셋/메트릭을 볼지)
  → MCP 도구로 데이터 탐색/쿼리 (list_datasets, query_dataset_data 등)
  → 분석 결과를 리포트 섹션으로 구조화
  → 챗에 인라인 리포트 위젯으로 표시
  → "이 분석을 스마트 작업으로 저장할까요?" 제안
  → 사용자 승인 시 → 스마트 작업 + 템플릿 자동 생성
```

**MCP 도구 추가:**

| 도구 | 설명 |
|------|------|
| `generate_report(question, datasetIds?)` | 비즈니스 질문 → 리포트 섹션 생성. datasetIds 생략 시 AI가 자동 탐색. |
| `save_as_smart_job(name, templateStructure, prompt, cronExpression?)` | 챗에서 생성한 분석을 스마트 작업 + 템플릿으로 저장 |

### 5.3 진입 경로 B: 스마트 작업 생성

```
스마트 작업 생성 화면
  → "목표 기반" 모드 선택 (기존 "수동" 모드와 탭 전환)
  → 비즈니스 질문 입력: "주간 매출 추이와 이상 원인을 분석"
  → [템플릿 생성] 버튼
  → AI가 질문 분석 → 적합한 템플릿 구조 + 프롬프트 자동 제안
  → 사용자가 비주얼 빌더에서 검토/수정
  → 저장 → Cron/이상감지 트리거로 반복 실행
```

### 5.4 AI 챗 인라인 리포트 위젯

Phase 6에서 구축한 Generative UI 인프라 활용:
- `generate_report` 도구 결과를 인라인 위젯(카드, 리스트, 차트 해석 등)으로 렌더링
- 위젯 하단에 "스마트 작업으로 저장" 버튼
- PDF 다운로드 버튼

### 5.5 의존성

- **7-4 (이상 탐지)**: 이상 감지 결과를 목표 기반 분석의 입력으로 활용 가능 ("왜 이상이 발생했는지 분석해줘")
- **7-5 (빌더)**: 자동 생성된 템플릿을 비주얼 빌더에서 편집
- **Phase 6 (Generative UI)**: 챗 인라인 위젯 인프라

---

## 6. 검증 기준

### 6.1 양식 구조 개선 (7-5 선행)

- [ ] 3단계 중첩 구조가 AI 프롬프트에 정상 전달되는지 확인
- [ ] `instruction` 필드가 AI 응답 품질에 영향을 주는지 before/after 비교
- [ ] 정적 섹션(`static: true`)이 AI 생성에서 제외되는지 확인
- [ ] 변수 치환이 렌더링(HTML, PDF, 이메일)에서 정상 동작하는지 확인
- [ ] 기존 빌트인 템플릿이 하위 호환으로 정상 동작하는지 확인
- [ ] 백엔드 타입체크 + 테스트 통과
- [ ] AI Agent 테스트 통과

### 6.2 비주얼 리포트 빌더 (7-5)

- [ ] 드래그앤드롭으로 섹션 순서 변경 가능
- [ ] 그룹 안으로 드래그하여 계층 이동 가능 (3단계 제한 검증)
- [ ] 빌더 ↔ JSON 탭 동기화 정상 동작
- [ ] 정적 섹션 선택 시 콘텐츠 편집 + 변수 칩 표시
- [ ] 프론트엔드 빌드 + 타입체크 통과
- [ ] Playwright 스크린샷 검증

### 6.3 이상 탐지 (7-4)

- [ ] 시스템 메트릭 폴링 + 히스토리 저장 동작
- [ ] 데이터셋 메트릭 쿼리 실행 + 히스토리 저장 동작
- [ ] 이상 감지 시 스마트 작업 자동 실행 확인
- [ ] cooldown 중복 방지 동작
- [ ] sensitivity 변경에 따른 감지 임계값 변화 확인
- [ ] 히스토리 부족 시 감지 보류 확인
- [ ] 모니터링 탭 UI 동작 (메트릭 추가/삭제)
- [ ] 백엔드 통합 테스트 통과

### 6.4 목표 기반 리포트 (7-6)

- [ ] AI 챗에서 비즈니스 질문 → 리포트 생성 동작
- [ ] 챗에서 생성한 리포트를 스마트 작업으로 저장 가능
- [ ] 스마트 작업 "목표 기반" 모드에서 AI 템플릿 자동 생성 동작
- [ ] 자동 생성된 템플릿을 빌더에서 편집 가능

---

## 7. 영향 범위

### 7.1 Backend (firehub-api)

| 파일 | 변경 내용 |
|------|----------|
| DB 마이그레이션 (신규) | `proactive_job.trigger_type` 컬럼, `metric_snapshot` 테이블 |
| `ProactiveJobService` | AnomalyEvent 수신 처리, trigger_type 분기 |
| `ProactiveContextCollector` | anomaly 컨텍스트 추가 |
| `ReportRenderUtils` | 계층 구조 렌더링, 정적 섹션 변수 치환, divider 렌더링 |
| `proactive-report.html` | 계층 구조 + 정적 섹션 Thymeleaf 반영 |
| `proactive-report-pdf.html` | 동일 |
| `MetricPollerService` (신규) | 메트릭 수집 스케줄러 |
| `AnomalyDetector` (신규) | 통계 기반 이상 판단 |
| `MetricSnapshotRepository` (신규) | metric_snapshot CRUD |
| 빌트인 템플릿 seed | instruction 필드 추가 |

### 7.2 AI Agent (firehub-ai-agent)

| 파일 | 변경 내용 |
|------|----------|
| `routes/proactive.ts` | 프롬프트 빌드 개선 (계층+instruction), parseSections 계층 지원 |
| `mcp/tools/proactive-tools.ts` | generate_report, save_as_smart_job 도구 추가 |
| `agent/system-prompt.ts` | 목표 기반 리포트 도구 안내 |

### 7.3 Frontend (firehub-web)

| 파일 | 변경 내용 |
|------|----------|
| `api/proactive.ts` | TemplateSection 타입 확장, 새 API 추가 |
| `lib/template-section-types.ts` | group, divider 타입 추가 |
| `pages/ai-insights/ReportTemplateDetailPage.tsx` | 빌더 UI로 전면 교체 |
| `pages/ai-insights/components/SectionTreeBuilder.tsx` (신규) | 드래그앤드롭 트리 빌더 |
| `pages/ai-insights/components/SectionPropertyEditor.tsx` (신규) | 속성 편집 패널 |
| `pages/ai-insights/components/SectionPreview.tsx` | 계층 구조 미리보기 |
| `pages/ai-insights/components/TemplateSidePanel.tsx` | 제거 또는 통합 |
| 스마트 작업 편집 페이지 | 모니터링 탭 추가, 목표 기반 모드 추가 |

---

## 8. UI/UX 가이드라인 (디자이너 리뷰 반영)

### 8.1 디자인 시스템 일관성

| 영역 | 지침 |
|------|------|
| **페이지 헤더** | 별도 top bar를 추가하지 않음. 기존 `AppLayout` + `ReportTemplateDetailPage` 헤더 패턴 유지 |
| **버튼 계층** | 저장=`variant="default"`, 미리보기=`variant="outline"`, 취소=`variant="ghost"` |
| **필드 라벨** | `text-sm font-medium` (Label 컴포넌트), 힌트는 `text-xs text-muted-foreground` |
| **토글** | shadcn/ui `Switch` 컴포넌트 그대로 사용. 커스텀 토글 금지 |
| **섹션 아이콘** | `template-section-types.ts`의 기존 `icon` 문자열 사용. 새 아이콘셋 도입 금지 |
| **속성 패널** | `Card` + `CardContent pt-6`로 감싸서 기존 사이드 패널 패턴과 일치 |

### 8.2 드래그앤드롭 UX

- 드래그 핸들: `GripVertical` (Lucide), hover 시에만 표시 (`opacity-0 group-hover:opacity-100`)
- 삽입 인디케이터: 2px 수평선 (`bg-primary`), 전체 행 하이라이트가 아님
- 깊이별 들여쓰기: `pl-4` (16px) per level
- 그룹 접기/펼치기: `Collapsible` + `ChevronRight`/`ChevronDown` 토글
- 부모를 자신의 자식으로 드래그: `cursor-not-allowed` + 삽입 인디케이터 미표시

### 8.3 섹션 선택 + 속성 패널

- 선택 상태: `bg-accent border-border` + `border-l-primary` (액센트 스트라이프 → primary 색상)
- 선택 시 속성 패널 전환 애니메이션: `transition-colors duration-150`
- 속성 패널 상단에 선택된 섹션 key 표시: `font-mono text-xs text-muted-foreground`
- AI Instruction `Textarea`: `rows={4}` 최소, 우하단에 글자 수 표시
- Key 필드: `snake_case` 실시간 검증, 에러 시 `text-sm text-destructive`
- 타입 가이드: `bg-muted/40 rounded-md p-3 text-xs text-muted-foreground` (읽기 전용)

### 8.4 정적 섹션 시각 구분

- 트리에서 정적 섹션: `border-l-muted-foreground` + `text-muted-foreground` 라벨
- 속성 패널: `Badge variant="secondary"` "정적" 표시, AI Instruction 필드 숨김
- `divider` 선택 시 속성 최소화 (라벨만)

### 8.5 모니터링 탭 (7-4)

- `ProactiveJobDetailPage`에 `TabsTrigger` 추가, `Activity` 아이콘 (`h-3.5 w-3.5 mr-1.5`)
- `Card + CardContent` 패턴, 카테고리별 `Separator` + 섹션 헤더
- 메트릭 행: `flex items-center justify-between` + `Switch` 우측
- 상세 설정(폴링 간격 등): 토글 ON 시에만 표시 (`animate-in fade-in duration-150`)
- 인라인 확장 방식, Dialog 사용 안 함

### 8.6 목표 기반 모드 (7-6)

- 모드 전환: `RadioGroup` ("직접 설정" / "목표 기반")
- 목표 기반 선택 시: 기존 필드 숨기고 `Textarea` ("비즈니스 질문") + `Button variant="outline" size="sm"` "템플릿 자동 생성" (`Sparkles` 아이콘)
- 생성 중: `Skeleton` 플레이스홀더 (기존 패턴)
- 생성 결과: `Collapsible`로 `SectionPreview` 표시
- 페이지 이동 없이 인라인 처리

### 8.7 AI 챗 위젯 (7-6)

- `WidgetShell` 컨테이너 사용 (기존 Generative UI 인프라)
- `show_report_builder` 도구 → `WidgetRegistry`에 `ReportBuilderWidget` 매핑
- 위젯 내부: `SectionPreview` (읽기 전용) + "편집하기" 버튼 (`variant="outline" size="sm"`) → 빌더 페이지 딥링크
- 높이 제한: `side`/`floating` 모드 `max-h-[250px]` + `ScrollArea`, `fullscreen` `max-h-[450px]`
- 전체 빌더를 챗 안에 넣지 않음 — 미리보기/확인 단계만

### 8.8 접근성 (a11y)

| 항목 | 대응 |
|------|------|
| 키보드 드래그 | `@dnd-kit` 기본 키보드 지원 + `aria-roledescription="sortable item"` |
| 섹션 추가 시 포커스 | 새 섹션의 label input으로 자동 포커스 (`useEffect` + `ref.focus()`) |
| 속성 패널 변경 알림 | `aria-live="polite"` 영역 |
| Required 토글 | `aria-label="필수 항목"` |
| 드래그 핸들 | `aria-label="섹션 순서 변경"` + `role="button"` |
| 색상 단독 구분 방지 | 타입 이름 텍스트 항상 표시 (색상 스트라이프 + 텍스트 병행) |
| 대비율 | 중요 라벨은 `text-foreground` 사용 (`muted-foreground`는 WCAG AA 경계) |

### 8.9 shadcn/ui 컴포넌트 매핑

| UI 요소 | 컴포넌트 |
|---------|---------|
| 트리 컨테이너 | `ScrollArea` |
| 트리 아이템 | 커스텀 `div` + `@dnd-kit/sortable` |
| 그룹 접기 | `Collapsible` + `CollapsibleTrigger` |
| 섹션 추가 메뉴 | `DropdownMenu` + `DropdownMenuItem` (타입별 아이콘) |
| 빌더/JSON 탭 | `Tabs` + `TabsList` (grid-cols-2) |
| 필수 토글 | `Switch` |
| 정적 뱃지 | `Badge variant="secondary"` |
| JSON 에디터 | `TemplateJsonEditor` (기존) |
| 모드 전환 | `RadioGroup` |
| 생성 미리보기 | `SectionPreview` (기존) + `Collapsible` |
| 챗 위젯 | `WidgetShell` (기존) |

---

## 9. 기술 선택

| 영역 | 기술 | 사유 |
|------|------|------|
| 드래그앤드롭 | `@dnd-kit/core` + `@dnd-kit/sortable` | React 생태계 표준, 트리 구조 지원, 접근성 우수 |
| 이상 감지 통계 | Java 직접 구현 (이동평균 + Z-score) | 외부 의존성 없이 충분, 경량 |
| 메트릭 스케줄링 | Spring `@Scheduled` (기존 패턴) | ProactiveJobSchedulerService와 일관성 |
| 변수 치환 | 단순 문자열 치환 (`{{var}}` → value) | Thymeleaf 분리, 복잡도 최소화 |
