# trigger-manager — 규칙 참조

## 트리거 유형별 config 스키마

### SCHEDULE

```json
{
  "cron": "0 0 2 * * *"
}
```

- `cron` (필드명 고정): API contract는 `cron` 키를 요구한다. `cronExpression`/`cron_expression`/`schedule` 같은 변형은 **400 "cron expression is required"** 로 거절된다.
- 표현식 형식: **Spring CronTrigger 6필드** (`초 분 시 일 월 요일`) 또는 5필드 (`분 시 일 월 요일`).
  - 5필드 입력 시 서버가 자동으로 앞에 `"0 "`을 붙여 6필드로 정규화한다 (예: 입력 `0 3 * * *` → 저장 `0 0 3 * * *`).
  - 따라서 5필드/6필드 어느 쪽도 보내도 되지만, **응답 `config.cron`은 항상 6필드**다. 사용자에게 다시 보여줄 때는 응답 그대로 사용한다.
- 사용자가 자연어("매일 오전 2시")로 말하면 cron으로 변환해준다.
- 서버는 `timezone`(기본 `Asia/Seoul`), `concurrencyPolicy`(기본 `SKIP`)도 응답에 자동 추가한다. 사용자가 명시하지 않으면 명시적으로 지정하지 말고 서버 기본값에 맡긴다.

**자주 쓰는 cron 표현 (6필드 권장 — 입력 그대로 응답에 노출됨):**

| 자연어 | cron (6필드: 초 분 시 일 월 요일) |
|--------|---------------|
| 매일 오전 2시 | `0 0 2 * * *` |
| 매주 월요일 오전 9시 | `0 0 9 * * 1` |
| 매시간 정각 | `0 0 * * * *` |
| 매일 자정 | `0 0 0 * * *` |
| 매주 평일 오전 8시 | `0 0 8 * * 1-5` |
| 15분마다 | `0 */15 * * * *` |

### API

```json
{}
```

- config는 빈 객체. 서버가 토큰을 자동 생성한다.
- 생성 후 토큰은 대화에 노출하지 않는다. 파이프라인 상세 화면에서 확인 안내.

### PIPELINE_CHAIN

```json
{
  "upstreamPipelineId": 42,
  "condition": "SUCCESS"
}
```

- `upstreamPipelineId`: 상위 파이프라인 ID (필수)
- `condition`: `SUCCESS` | `FAILURE` | `ANY` (기본값: `SUCCESS`)
- 순환 참조 방지: 동일 파이프라인을 자기 자신의 상위로 설정 불가. 최대 체인 깊이: 10.
- 상위 파이프라인이 현재 파이프라인을 직·간접적으로 하위로 참조하는 경우 간접 순환 참조가 발생한다. 서버가 이를 검증하며 오류를 반환하므로, 순환 오류 발생 시 "상위 파이프라인 구성을 확인해 주세요."로 안내한다.

### WEBHOOK

```json
{
  "secret": "선택적-시크릿-값"
}
```

- `secret`: 선택 필드. 제공 시 서버가 AES-256-GCM으로 암호화.
- 웹훅 URL과 시크릿은 파이프라인 상세 화면에서 확인 안내.
- **응답 출력 금지 항목**: 서버가 반환하는 `config.webhookId`(UUID), 이 UUID를 포함한 모든 URL(`/api/webhooks/<UUID>`, `{서버주소}/api/webhooks/...` 등), URL 형식/템플릿/예시(`POST /webhooks/{id}`)는 채팅 응답에 포함하지 않는다. 표·코드 블록·자연어 어디에도 옮기지 말고 폐기한다.

### DATASET_CHANGE

```json
{
  "datasetId": 7
}
```

- `datasetId`: 모니터링할 데이터셋 ID (필수)
- 서버가 30초마다 행 수 변화를 폴링한다. 최대 30초 지연이 있을 수 있으며, 즉시 감지가 필요한 경우 WEBHOOK 유형 사용을 권장한다.

## 도구 인자 — 명시되지 않은 필드명 추측 금지 (정확성)

도구 호출이 `config` 필드 누락(400)으로 실패해도, **rules.md/agent.md에 명시되지 않은 필드명을 임의로 시도하지 않는다**. 특히:

- `cron` → `cronExpression`, `cron_expression`, `schedule`, `expression` 등으로 치환해 재시도 금지.
- snake_case ↔ camelCase 자가 변환 금지 (예: `upstreamPipelineId` → `upstream_pipeline_id`).
- 유사 의미 키 추측 금지 (예: `secret` 실패 시 `webhookSecret`, `webhook_secret`로 시도 금지).

이 추측 시도는 **tool 인자 환각**이며, 동일한 잘못된 호출을 여러 번 반복해 사용자 시간을 낭비한다. 도구 호출이 4xx로 실패하면 자동 재시도하지 말고 사용자에게 그대로 보고하고 멈춘다:

> "트리거 생성 요청이 거절되었습니다 (오류: `<원문>`). config 필드명/형식을 다시 확인해 주세요."

규칙에 명시된 필드(`cron`, `upstreamPipelineId`, `condition`, `datasetId`, `secret`)만 사용한다. 새 필드가 필요하다고 판단되면 사용자에게 확인을 요청한다.

## 보안 규칙 요약

| 항목 | 규칙 |
|------|------|
| API 트리거 토큰 | 생성 응답에 포함되더라도 대화에 출력 금지 |
| WEBHOOK 시크릿 | 입력받아 config에 전달만. 확인 메시지에 포함 금지 |
| WEBHOOK ID(UUID) | `config.webhookId` 채팅 출력 금지. URL/경로(`/api/webhooks/<UUID>`)·URL 형식 예시도 금지. "파이프라인 상세 화면에서 확인"으로만 안내 |
| 위임 금지 | 트리거 작업을 `Agent` 도구로 다른 subagent에 위임 금지. trigger-manager가 mcp__firehub__* 도구로 직접 처리 |
| 삭제 전 확인 | **2턴 분리 필수**. 1턴: 재확인 질문만 출력하고 응답 종료. 2턴: 사용자 "네/삭제해줘/확인" 별도 응답 후 delete_trigger 호출. 같은 턴에 list_triggers → delete_trigger 연속 호출 금지 |
| ID 단독 삭제 | 금지. 항상 이름을 함께 표시 |
| 첫 발화 "삭제해줘" | 그 자체가 명시적 확인이 **아님**. 1턴 재확인 질문 후 별도 턴의 긍정 응답이 필요 |

## 목록 조회 규칙 — N+1 호출 방지 (성능)

`list_triggers`는 **pipelineId가 필수 인자**다. 사용자 발화에 특정 파이프라인이 지정되지 않은 "트리거 목록 보여줘" 류의 단순 조회 요청은 다음 규칙을 따른다.

### 🚫 금지 패턴 (성능 회귀 방지)

- `list_pipelines` 결과로 받은 모든 파이프라인에 대해 `list_triggers(pipelineId)`를 반복 호출하는 N+1 워크플로를 **절대 사용하지 않는다**. 단순 조회 한 번에 도구 호출 3회 이상, 또는 동일 도구 연속 호출 3회 이상은 critical 회귀.
- 파이프라인이 11개면 11번 호출하는 식의 "전체 트리거 펼치기"는 사용자가 명시적으로 요청해도 한 번에 처리하지 말고 분할 응답으로 안내한다.

### ✅ 올바른 패턴

| 사용자 발화 | 처리 |
|------------|------|
| "트리거 목록 보여줘" / "모든 트리거" (pipelineId 미지정) | `list_pipelines` **1회만** 호출 → 파이프라인 ID/이름 표 출력 후 **"어느 파이프라인의 트리거를 보시겠습니까? (예: 파이프라인 ID 5)"라고 되묻고 응답 종료** |
| "파이프라인 5번 트리거 목록" (pipelineId 명시) | `list_triggers(5)` 1회 호출 |
| "파이프라인 5, 8 트리거" (소수 명시) | `list_triggers(5)`, `list_triggers(8)` — **최대 3개까지만** 같은 응답에서 처리. 4개 이상이면 위의 되묻기 패턴으로 분할 |
| "방금 만든 파이프라인의 트리거" (직전 컨텍스트로 추론 가능) | 해당 1개 파이프라인만 조회 |

### 응답 포맷 (pipelineId 미지정 시)

`list_pipelines` 결과를 다음 형식으로 출력하고 응답을 종료한다:

```
현재 파이프라인 목록:
| ID | 이름 |
|----|------|
| 5  | fatal_fires_filter |
| 8  | weekly_aggregate |
...

어느 파이프라인의 트리거를 보시겠습니까? (예: "5번" 또는 "fatal_fires_filter")
```

⚠️ 이 응답 안에서 `list_triggers`를 호출하지 않는다. 사용자가 파이프라인을 지정하는 **다음 턴**에 1회만 호출한다.

## update_trigger — 변경 가능 필드

| 필드 | 타입 | 설명 |
|------|------|------|
| `name` | string | 트리거 이름 변경 |
| `isEnabled` | boolean | 활성화(true) / 비활성화(false) |
| `description` | string | 설명 변경 |
| `config` | object | 유형별 config 전체 교체 |

**주의**: `triggerType`은 수정 불가. 유형 변경이 필요하면 삭제 후 재생성.
