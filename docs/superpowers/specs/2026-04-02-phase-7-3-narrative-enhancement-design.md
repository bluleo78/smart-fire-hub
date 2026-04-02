# Phase 7-3: 리포트 내러티브 강화 — 설계 문서

> **작성일**: 2026-04-02
> **상태**: 승인됨
> **의존**: Phase 7-0a (프로액티브 AI)
> **범위**: AI Agent (firehub-ai-agent) + Backend (firehub-api) + Frontend (firehub-web, 소규모)

---

## 1. 목표

프로액티브 AI 리포트의 품질을 "데이터 나열"에서 "인사이트 중심 내러티브"로 향상시킨다.

- 시스템 프롬프트를 강화하여 AI가 인사이트 중심으로 서술하도록 유도
- 이전 실행 결과(최근 3건)를 컨텍스트로 전달하여 비교 분석 가능하게 함
- 템플릿에 `style` 필드를 추가하여 템플릿별 작성 스타일 지정 가능
- 9가지 섹션 타입별 세분화된 작성 가이드 제공

---

## 2. 주요 변경 사항

### 2.1 템플릿 `style` 필드 추가

템플릿 JSON 구조에 `style` 최상위 필드를 추가한다. AI 시스템 프롬프트에 전달되어 리포트 전체의 톤/스타일을 결정한다.

**확장된 템플릿 JSON:**
```json
{
  "style": "경영진 보고서 스타일. 핵심 발견 먼저 서술하고, 근거 데이터는 뒤에 배치. 권고사항은 구체적 액션 중심.",
  "sections": [...]
}
```

- 자유 텍스트 형식 (사용자가 원하는 스타일을 자연어로 기술)
- 미지정 시 기본 내러티브 가이드 적용
- 프론트엔드 템플릿 편집 페이지에 "스타일" 입력란 추가

**빌트인 템플릿 기본 스타일:**
| 템플릿 | style |
|--------|-------|
| 일간 요약 | "간결한 경영진 보고 스타일. 핵심 변화를 먼저 서술하고, 수치는 맥락과 함께 제시. 이전 실행과 비교하여 변화 추이를 언급." |
| 실패 분석 | "기술 분석 스타일. 실패 현상 → 근본 원인 → 영향도 → 해결 방안 순서로 논리적 서술. 재발 방지 관점의 권고사항 포함." |
| 주간 트렌드 | "트렌드 분석 스타일. 이번 주와 지난주를 비교하여 변화율 중심 서술. 단기(1주) 변화와 중기(4주) 추세를 구분. 수치에는 반드시 변화율(%) 병기." |

### 2.2 이전 실행 결과 컨텍스트 (최근 3건)

`ProactiveContextCollector`에서 해당 작업의 최근 완료된 실행 결과 3건을 수집하여 컨텍스트에 포함한다.

**변경:** `collectContext(Map<String, Object> config)` → `collectContext(Map<String, Object> config, Long jobId)`

`jobId`를 받아 `executionRepository.findByJobId(jobId, 3, 0)`으로 최근 3건 조회. COMPLETED 상태만 필터링. 각 실행의 `completedAt` + 섹션 텍스트(content만, data 제외)를 `previousExecutions` 키로 컨텍스트에 추가.

```json
{
  "stats": {...},
  "systemHealth": {...},
  "previousExecutions": [
    {
      "completedAt": "2026-04-01T09:00:00",
      "sections": [
        {"key": "summary", "label": "요약", "content": "...마크다운 텍스트..."}
      ]
    }
  ]
}
```

**토큰 절약:** 각 실행의 섹션 content를 최대 2000자로 잘라서 전달.

### 2.3 시스템 프롬프트 강화

`buildProactiveSystemPrompt()`를 다음과 같이 개선한다.

**기본 내러티브 가이드 (항상 포함):**
```
분석 원칙:
- 데이터 나열이 아닌 인사이트 중심으로 서술하세요.
- "왜 이 수치가 변했는가"를 파악하고, 가능한 원인을 제시하세요.
- 이전 실행 결과(previousExecutions)가 있으면 비교하여 변화 추이를 언급하세요.
- 변화를 언급할 때는 절대값과 변화율(%)을 함께 제시하세요.
- 확신이 낮으면 "~로 보입니다", "확인이 필요합니다" 등으로 표현하세요.
- 권고사항은 "무엇을 해야 하는가"를 구체적으로 제시하세요.
```

**템플릿 style이 있으면 추가:**
```
작성 스타일: {template.style}
```

**섹션 타입별 가이드 (기존 cards 가이드를 9가지로 확장):**

| 타입 | 추가 가이드 |
|------|------------|
| `text` | 마크다운 서술. 핵심 발견(key finding)을 먼저 쓰고 근거를 뒤에 배치. |
| `cards` | (기존 JSON 코드블록 가이드 유지) + 가능하면 이전 값 대비 변화를 description에 포함. |
| `list` | 중요도/심각도 순으로 정렬. 각 항목에 맥락(왜 중요한지) 한 줄 추가. |
| `table` | 마크다운 테이블 형식. 비교 컬럼(전기 대비)이 있으면 변화율 컬럼 추가. |
| `comparison` | 기간 비교 형식. "이번 기간 vs 이전 기간: +N% (절대값)" 패턴 사용. |
| `alert` | 심각도 순(CRITICAL → WARNING → INFO). 각 알림에 권장 조치 포함. |
| `timeline` | 시간순 나열. 각 이벤트에 영향도 한 줄 설명 추가. |
| `chart` | 차트 해석. 추세, 이상값, 패턴을 자연어로 설명. |
| `recommendation` | 구체적 액션 + 기대 효과 + 우선순위. 실행 가능한 단계로 기술. |

### 2.4 AI Agent 엔드포인트 변경

`POST /agent/proactive` 요청 body에 `previousExecutions` 필드 추가. (현재는 `prompt`, `context`, `apiKey`, `config`만 전달)

**변경 방안:** `previousExecutions`를 별도 필드가 아닌, `context` JSON 안에 포함시킨다. 이렇게 하면 AI Agent 엔드포인트의 인터페이스 변경 없이 백엔드 `ProactiveContextCollector`만 수정하면 된다.

### 2.5 프론트엔드 — 템플릿 스타일 입력

`ReportTemplateDetailPage`의 편집 모드에서 이름/설명 아래에 "스타일" 텍스트 입력란 추가.

- JSON 내부의 `style` 필드를 직접 편집하는 것이 아니라, 별도 입력란으로 제공
- 저장 시 `structure.style`에 반영
- 읽기 모드에서는 스타일이 있으면 메타 정보 영역에 표시

---

## 3. 영향받는 파일

### Backend (firehub-api)
| 파일 | 변경 내용 |
|------|-----------|
| `ProactiveContextCollector.java` | `collectContext` 시그니처에 `jobId` 추가, 이전 실행 결과 3건 수집 |
| `ProactiveJobService.java` | `collectContext` 호출 시 `jobId` 전달 |
| DB 마이그레이션 (V31) | 빌트인 템플릿 structure에 `style` 필드 추가 (UPDATE) |

### AI Agent (firehub-ai-agent)
| 파일 | 변경 내용 |
|------|-----------|
| `routes/proactive.ts` | `buildProactiveSystemPrompt()` 강화 — 내러티브 가이드, style 반영, 섹션 타입별 가이드 |
| `routes/proactive.ts` | `Template` 인터페이스에 `style?` 필드 추가 |

### Frontend (firehub-web)
| 파일 | 변경 내용 |
|------|-----------|
| `ReportTemplateDetailPage.tsx` | 스타일 입력란 추가 (편집 모드), 스타일 표시 (읽기 모드) |

---

## 4. 설계 결정 기록

| 결정 | 선택 | 이유 |
|------|------|------|
| 이전 실행 결과 수 | 최근 3건 | 추세 파악 가능 ("3주 연속 증가"), 토큰 절약 (content 2000자 제한) |
| 작성 스타일 지정 | 템플릿 `style` 필드 | 템플릿마다 다른 톤 지정 가능, AI가 자동 판단하되 가이드 제공 |
| 컨텍스트 전달 방식 | context JSON에 포함 | AI Agent 인터페이스 변경 불필요 |
| 스타일 미지정 시 | 기본 내러티브 가이드 적용 | "인사이트 중심, 비교 분석, 원인 파악" 기본 원칙 |

---

## 5. 검증 기준

### 빌드/타입 검증
- [ ] `./gradlew build` 통과 (백엔드)
- [ ] `pnpm typecheck` 통과 (프론트엔드)
- [ ] `pnpm build` 통과 (프론트엔드)
- [ ] AI Agent 테스트 통과

### 기능 검증
- [ ] 시스템 프롬프트에 내러티브 가이드 포함 확인
- [ ] 템플릿 style 필드가 시스템 프롬프트에 반영 확인
- [ ] 이전 실행 결과 3건이 컨텍스트에 포함 확인
- [ ] 섹션 타입별 가이드가 시스템 프롬프트에 포함 확인
- [ ] 빌트인 템플릿에 기본 스타일 추가 확인
- [ ] 프론트엔드 템플릿 편집에서 스타일 입력/저장 동작

---

## 6. 범위 외 (Not In Scope)

- 새로운 MCP 도구 추가 (감사 로그, 이상 탐지 등) → Phase 7-4
- 기간별 비교 사전 계산 → AI가 쿼리 도구로 직접 수행
- 실행 스냅샷 테이블 → Phase 7-4
- 진단 쿼리 템플릿 → Phase 7-4
