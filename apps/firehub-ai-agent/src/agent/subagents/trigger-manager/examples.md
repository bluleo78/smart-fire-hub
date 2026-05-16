# trigger-manager — 대화 예시

## 예시 1: SCHEDULE 트리거 생성 (자연어 → cron 변환)

**사용자**: 파이프라인 5번에 매일 오전 3시에 실행되는 스케줄 트리거 만들어줘.

**에이전트**:

[Phase 1 — IDENTIFY] 생성 흐름을 시작한다.

[Phase 2 — DESIGN] 트리거 설계 대화

파이프라인 5의 현재 트리거가 없습니다. 새 스케줄 트리거를 등록하겠습니다.

- **이름**: 매일 오전 3시 집계 (원하시면 변경 가능)
- **유형**: SCHEDULE
- **cron**: `0 3 * * *` (매일 오전 3:00)

등록할까요?

**사용자**: 응, 이름은 "새벽 집계"로 해줘.

**에이전트**:

[Phase 3 — EXECUTE] create_trigger 호출

```json
{
  "pipelineId": 5,
  "name": "새벽 집계",
  "triggerType": "SCHEDULE",
  "config": { "cronExpression": "0 3 * * *" }
}
```

[Phase 4 — CONFIRM] 결과 요약

'새벽 집계' 트리거가 등록되었습니다 (ID: 12, 유형: SCHEDULE).
- 다음 실행 시간: 2026-04-13 03:00:00

---

## 예시 2: PIPELINE_CHAIN 트리거 생성

**사용자**: 파이프라인 8번이 성공하면 파이프라인 10번이 자동으로 돌게 해줘.

**에이전트**:

[Phase 1 — IDENTIFY] 생성 흐름을 시작한다.

[Phase 2 — DESIGN] 트리거 설계 대화

파이프라인 10번에 체인 트리거를 등록하겠습니다.

- **유형**: PIPELINE_CHAIN
- **상위 파이프라인**: 8번
- **실행 조건**: SUCCESS (성공 시에만)

트리거 이름을 지정해 주세요. (예: "파이프라인8 성공 후 연쇄")

**사용자**: "8→10 체인"으로 해줘.

**에이전트**:

[Phase 3 — EXECUTE] create_trigger 호출

```json
{
  "pipelineId": 10,
  "name": "8→10 체인",
  "triggerType": "PIPELINE_CHAIN",
  "config": { "upstreamPipelineId": 8, "condition": "SUCCESS" }
}
```

[Phase 4 — CONFIRM] 결과 요약

'8→10 체인' 트리거가 등록되었습니다 (ID: 15, 유형: PIPELINE_CHAIN).
파이프라인 8이 성공으로 완료되면 파이프라인 10이 자동 실행됩니다.

---

## 예시 3: 트리거 비활성화 (토글)

**사용자**: 파이프라인 5번 "새벽 집계" 트리거 잠깐 꺼줘.

**에이전트**:

[Phase 1 — IDENTIFY] 토글 흐름. list_triggers(pipelineId=5) 호출 → ID:12 "새벽 집계" 확인.

[Phase 3 — EXECUTE] 단순 isEnabled 토글이므로 Phase 2 생략. update_trigger 호출.

```json
{
  "pipelineId": 5,
  "triggerId": 12,
  "isEnabled": false
}
```

[Phase 4 — CONFIRM] 결과 요약

'새벽 집계' 트리거의 활성화 여부가 비활성화로 업데이트되었습니다.
다시 켜려면 "새벽 집계 트리거 켜줘"라고 하면 됩니다.

---

## 예시 4: 트리거 삭제

**사용자**: 파이프라인 5번 "새벽 집계" 트리거 삭제해줘.

**에이전트**:

[Phase 1 — IDENTIFY] 삭제 흐름. list_triggers(pipelineId=5) 호출 → ID:12 "새벽 집계" 확인.

[Phase 3 — EXECUTE] 삭제 전 사용자 확인

'새벽 집계' 트리거를 삭제합니다. 삭제 후 이 트리거로는 파이프라인이 실행되지 않습니다. 계속할까요?

**사용자**: 응, 삭제해줘.

**에이전트**:

delete_trigger(pipelineId=5, triggerId=12) 호출

[Phase 4 — CONFIRM] 결과 요약

'새벽 집계' 트리거가 삭제되었습니다.

---

## 예시 5: WEBHOOK 트리거 생성 — UUID/URL 비노출 응답 (보안)

**사용자**: 파이프라인 15에 웹훅 트리거 만들어줘.

**에이전트**:

[Phase 1 — IDENTIFY] 생성 흐름. WEBHOOK 유형.

[Phase 2 — DESIGN] 설계 대화

- **이름**: "외부 웹훅" (변경 가능)
- **유형**: WEBHOOK
- **secret**: 사용 안 함 (필요 시 알려주세요)

등록할까요?

**사용자**: 응.

**에이전트**:

[Phase 3 — EXECUTE] create_trigger 호출 — **trigger-manager가 직접 mcp__firehub__create_trigger를 호출한다. `Agent` 도구로 general-purpose 등 다른 subagent에 위임하지 않는다.**

```json
{
  "pipelineId": 15,
  "name": "외부 웹훅",
  "triggerType": "WEBHOOK",
  "config": {}
}
```

서버 응답에 `config.webhookId`(UUID)가 포함되지만, 이 값은 **응답에 옮기지 않고 폐기**한다.

[Phase 4 — CONFIRM] 결과 요약

'외부 웹훅' 트리거가 등록되었습니다 (ID: 23, 유형: WEBHOOK, 상태: 활성화).

웹훅 URL과 시크릿은 파이프라인 상세 화면에서 확인할 수 있습니다.

> ❌ **잘못된 응답 (절대 출력 금지)** — 비교용
>
> "웹훅 ID: `cabf8e3a-6bd0-4f69-bac6-c35e9089afc3`, URL: `{서버주소}/api/webhooks/cabf8e3a-...`"
> 표·코드 블록·자연어 어디에든 UUID나 `/api/webhooks/...` 형식을 포함하면 보안 규칙 위반이다.
