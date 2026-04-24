# dataset-manager 서브에이전트 테스트 케이스 명세

> **테스트 방식**: AI-Driven Testing — Claude가 Playwright MCP 도구로 브라우저를 조작하고, AI 채팅 UI에 자연어 메시지를 입력하여 에이전트 동작을 검증한다.
>
> **AI 테스트 실행 참고**:
> - 채팅 입력창 셀렉터: `placeholder='메시지를 입력하세요...'`
> - 스트리밍 완료 감지: 전송 버튼이 enabled 상태로 복귀하는 시점
> - MCP 도구 호출 확인: 응답 버블 내 tool-call 레이블 (.rounded.border.border-border/50 요소)
> - 공통 헬퍼: `e2e/ai/helpers/ai-chat.ts` (sendMessage, waitForResponse, getLastResponseText, assertToolCalled 등)

---

## TC-DM-01: 데이터셋 생성 — 시스템 예약 컬럼명 거부 및 대안 제안

**목적**: AI 에이전트가 시스템 예약 컬럼명(`id`, `import_id`, `created_at`) 사용 시 오류를 안내하고 대안을 제안하는지 검증한다.

**PreCondition**
- firehub-api, firehub-web, firehub-ai-agent 실행 중
- 로그인 상태 (bluleo78@gmail.com)
- AI 사이드 패널 열린 상태

**Steps**
1. AI 채팅에 다음 메시지 입력:
   ```
   e2e_test_dataset 이름으로 새 데이터셋을 만들어줘. 컬럼은 id(INTEGER), name(VARCHAR), created_at(TIMESTAMP) 3개야.
   ```
2. 스트리밍 완료까지 대기

**Expected Result**
- [ ] AI가 `create_dataset` MCP 도구를 호출한다
- [ ] API로부터 400 에러를 수신하고 사용자에게 예약 컬럼명 문제를 설명한다
- [ ] AI 응답에 "예약" 또는 "시스템 컬럼" 관련 안내가 포함된다
- [ ] AI 응답에 대안 컬럼명이 제안된다 (예: `record_id`, `event_created_at`)
- [ ] "오류", "실패" 류의 종결 메시지 없이 사용자 안내로 마무리된다

**PostCondition (Cleanup)**
- 해당 TC에서 데이터셋이 생성된 경우: `DELETE /api/v1/datasets/{id}` 호출하여 삭제

---

## TC-DM-02: 데이터셋 조회 및 삭제

**목적**: AI 에이전트가 데이터셋 존재를 확인하고, 파괴 작업 체크리스트(재확인 절차)를 거쳐 삭제하는지 검증한다.

**PreCondition**
- `e2e_test_dataset` 데이터셋이 존재해야 함 (TC-DM-01 선행 또는 별도 생성)

**Steps**
1. AI 채팅에 다음 메시지 입력:
   ```
   e2e_test_dataset 데이터셋이 생성됐는지 확인하고, 있으면 삭제해줘.
   ```
2. 스트리밍 완료까지 대기
3. AI가 재확인을 요청하는 경우: `"네, 삭제하세요"` 입력
4. 스트리밍 완료까지 대기

**Expected Result**
- [ ] AI가 `list_datasets` 또는 `get_dataset` MCP 도구를 호출하여 존재를 확인한다
- [ ] AI가 데이터셋 이름과 ID를 사용자에게 보고한다
- [ ] AI가 삭제 전 존재 확인 결과를 요약하여 제시한다 (재확인 요청은 선택적 — "있으면 삭제해줘"처럼 명시적 지시 시 생략 가능)
- [ ] `delete_dataset` MCP 도구를 호출하여 삭제를 실행한다
- [ ] 삭제 완료를 보고한다
- [ ] UI 데이터셋 목록에서 해당 항목이 사라진다

**PostCondition (Cleanup)**
- 없음 (삭제가 목적)

---

## TC-DM-03: 데이터셋 생성 — 유효한 컬럼 스키마

**목적**: 예약어 없는 정상 스키마로 데이터셋 생성이 성공하는지 검증한다.

**PreCondition**
- `test_valid_col` 이름의 데이터셋이 존재하지 않아야 함

**Steps**
1. AI 채팅에 다음 메시지 입력:
   ```
   test_valid_col 이름으로 데이터셋을 만들어줘. 컬럼은 record_id(INTEGER), name(VARCHAR(100)), event_time(TIMESTAMP) 3개야.
   ```
2. 스트리밍 완료까지 대기

**Expected Result**
- [ ] AI가 `create_dataset` MCP 도구를 호출한다
- [ ] 400/500 에러 없이 성공 응답을 받는다
- [ ] AI가 생성된 데이터셋 이름과 테이블명을 보고한다
- [ ] UI 데이터셋 목록에 `test_valid_col` 항목이 표시된다

**PostCondition (Cleanup)**
- `DELETE /api/v1/datasets/{id}` 호출하여 `test_valid_col` 삭제

---

## TC-DM-04: 삭제 — 파괴 작업 재확인 절차 준수

**목적**: AI 에이전트가 단순 "삭제해줘" 지시에 즉시 실행하지 않고, 대상 요약 + 복구 불가 경고 후 명시적 재확인을 받는지 검증한다.

**PreCondition**
- 삭제 대상 데이터셋이 존재해야 함 (TC-DM-03 선행 또는 별도 생성)

**Steps**
1. AI 채팅에 다음 메시지 입력:
   ```
   test_valid_col 데이터셋 삭제해줘.
   ```
2. 스트리밍 완료까지 대기
3. AI 응답 확인 (재확인 요청 여부)

**Expected Result**
- [ ] AI가 즉시 `delete_dataset`를 호출하지 않는다
- [ ] AI 응답에 삭제 대상 데이터셋 이름이 명시된다
- [ ] AI 응답에 "복구 불가" 또는 이에 상응하는 경고가 포함된다
- [ ] AI가 명시적 재확인을 요청한다

**Step (재확인 후)**
4. `"네, 삭제하세요"` 입력 후 스트리밍 완료 대기

**Expected Result (재확인 후)**
- [ ] `delete_dataset` MCP 도구가 호출된다
- [ ] 삭제 완료를 보고한다

**PostCondition (Cleanup)**
- 없음 (삭제가 목적)
