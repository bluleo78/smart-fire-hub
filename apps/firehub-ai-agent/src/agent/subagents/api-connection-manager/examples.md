# api-connection-manager — 대화 예시

## 예시 1: API_KEY 방식 연결 생성

**사용자**: 공공데이터포털 API 연결 등록해줘. API 키 방식이야.

**에이전트**:

[Phase 1 — IDENTIFY] 생성 흐름을 시작한다.

[Phase 2 — DESIGN] 연결 설계 대화

연결 이름을 알려주세요. (예: `"공공데이터포털 소방용수"`)

**사용자**: "공공데이터포털 소방용수"로 해줘. 헤더명은 `Authorization`이고 키는 `abc123xyz`야.

**에이전트**:

[Phase 3 — EXECUTE] create_api_connection 호출

```json
{
  "name": "공공데이터포털 소방용수",
  "authType": "API_KEY",
  "authConfig": {
    "apiKey": "abc123xyz",
    "headerName": "Authorization"
  }
}
```

[Phase 4 — CONFIRM] 결과 요약

'공공데이터포털 소방용수' 연결이 등록되었습니다 (ID: 3, 인증방식: API_KEY).
파이프라인 API_CALL 스텝에서 이 연결을 선택하면 API 호출 시 `Authorization` 헤더에 인증 키가 자동으로 추가됩니다 (키 값은 마스킹되어 표시됩니다).

> **보안**: 인증 키는 AES-256-GCM으로 암호화되어 저장되며, 조회 시 마스킹 처리됩니다.

---

## 예시 2: BEARER 방식 연결 생성

**사용자**: Kakao Mobility API Bearer 토큰 연결 만들어줘

**에이전트**:

[Phase 2 — DESIGN] 설계 대화

BEARER 방식으로 연결하겠습니다. `Authorization: Bearer {토큰}` 헤더가 자동으로 추가됩니다.

Bearer 토큰 값을 알려주세요.

**사용자**: `eyJhbGciOiJIUzI1NiJ9.abc123`

**에이전트**:

[Phase 3 — EXECUTE] create_api_connection 호출 (토큰 값은 응답에 출력하지 않음)

[Phase 4 — CONFIRM] 결과 요약

'Kakao Mobility API' 연결이 등록되었습니다 (ID: 4, 인증방식: BEARER).

---

## 예시 3: 인증 키 갱신 (Update)

**사용자**: 공공데이터포털 소방용수 API 키 바뀌었어. 업데이트해줘.

**에이전트**:

[Phase 1 — IDENTIFY] list_api_connections()를 호출해 "공공데이터포털 소방용수" 연결을 찾는다.

기존 연결 확인: ID 3, "공공데이터포털 소방용수" (API_KEY)

새 API 키 값을 알려주세요. 헤더명은 기존과 동일한 `Authorization`을 유지할까요?

**사용자**: 새 키는 `newkey456abc`이고 헤더명 동일하게 유지해줘.

**에이전트**:

[Phase 3 — EXECUTE] update_api_connection(3, authConfig: { apiKey: "newkey456abc", headerName: "Authorization" }) 호출

[Phase 4 — CONFIRM] 결과 요약

'공공데이터포털 소방용수' 연결의 인증 키가 업데이트되었습니다.

---

## 예시 4: 연결 삭제 + 확인 흐름

**사용자**: Kakao Mobility API 연결 삭제해줘

**에이전트**:

[Phase 1 — IDENTIFY] get_api_connection(4) 호출

'Kakao Mobility API' 연결(ID: 4, BEARER)을 삭제하면 이 연결을 사용하는 파이프라인 API_CALL 스텝이 동작하지 않습니다. 계속할까요?

**사용자**: 네, 삭제해줘

**에이전트**:

[Phase 3 — EXECUTE] delete_api_connection(4) 호출

[Phase 4 — CONFIRM] 결과 요약

'Kakao Mobility API' 연결이 삭제되었습니다.
