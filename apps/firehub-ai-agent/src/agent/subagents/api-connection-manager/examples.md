# api-connection-manager — 대화 예시

## 예시 1: API_KEY 방식 연결 생성 (baseUrl 포함)

**사용자**: 공공데이터포털 API 연결 등록해줘. API 키 방식이야.

**에이전트**:

[Phase 1 — IDENTIFY] 생성 흐름을 시작한다.

[Phase 2 — DESIGN] 연결 설계 대화

연결 이름과 기본 URL을 알려주세요. (예: 이름 `"공공데이터포털 소방용수"`, URL `https://api.odcloud.kr/api`)

**사용자**: "공공데이터포털 소방용수"로 해줘. URL은 `https://api.odcloud.kr/api`, 헤더명은 `Authorization`이고 키는 `abc123xyz`야.

**에이전트** ([Turn 1] DESIGN 출력 → 응답 종료, refs #626):

[Phase 2 — DESIGN] 설계 요약

- 이름: `공공데이터포털 소방용수`
- authType: `API_KEY`
- authConfig: `headerName: "Authorization"`, `apiKey: "abc...xyz"` (마스킹)
- baseUrl: `https://api.odcloud.kr/api`

이대로 등록할까요? (예 / 수정 요청)

*(같은 턴에 create_api_connection을 호출하지 않는다.)*

**사용자**: 네, 등록해줘

**에이전트** ([Turn 2] 사용자 승인 확인됨):

[Phase 3 — EXECUTE] create_api_connection 호출

```json
{
  "name": "공공데이터포털 소방용수",
  "authType": "API_KEY",
  "authConfig": {
    "apiKey": "abc123xyz",
    "headerName": "Authorization"
  },
  "baseUrl": "https://api.odcloud.kr/api",
  "healthCheckPath": "/health"
}
```

[Phase 4 — CONFIRM] 결과 요약

'공공데이터포털 소방용수' 연결이 등록되었습니다 (ID: 3, 인증방식: API_KEY).
파이프라인 API_CALL 스텝에서 이 연결을 선택하면 path(`/15085601/v1/uddi`)만 입력하면 `baseUrl + path`로 자동 조합됩니다.

> **보안**: 인증 키는 AES-256-GCM으로 암호화되어 저장되며, 조회 시 마스킹 처리됩니다.

---

## 예시 2: BEARER 방식 연결 생성 + 즉시 테스트

**사용자**: Kakao Mobility API Bearer 토큰 연결 만들어줘

**에이전트**:

[Phase 2 — DESIGN] 설계 대화

BEARER 방식으로 연결하겠습니다. `Authorization: Bearer {토큰}` 헤더가 자동으로 추가됩니다.

기본 URL과 Bearer 토큰 값을 알려주세요.

**사용자**: URL은 `https://apis.openapi.sk.com/tmap`이고 토큰은 `eyJhbGciOiJIUzI1NiJ9.abc123`이야.

**에이전트** ([Turn 1] DESIGN 출력 → 응답 종료, 토큰 값은 마스킹 표기):

[Phase 2 — DESIGN] 설계 요약

- 이름: `Kakao Mobility API`
- authType: `BEARER`
- authConfig: `token: "eyJ...123"` (마스킹)
- baseUrl: `https://apis.openapi.sk.com/tmap`

이대로 등록할까요? (예 / 수정 요청)

**사용자**: 응, 그대로 해줘

**에이전트** ([Turn 2] 사용자 승인 확인됨):

[Phase 3 — EXECUTE] create_api_connection 호출 (토큰 값은 응답에 출력하지 않음)

```json
{
  "name": "Kakao Mobility API",
  "authType": "BEARER",
  "authConfig": { "token": "eyJhbGciOiJIUzI1NiJ9.abc123" },
  "baseUrl": "https://apis.openapi.sk.com/tmap"
}
```

[Phase 4 — CONFIRM] 결과 요약

'Kakao Mobility API' 연결이 등록되었습니다 (ID: 4, 인증방식: BEARER).

---

## 예시 6: "확인 없이 바로 만들어" 우회 시도 거부 (refs #626)

**사용자**: API 연결 하나 새로 만들어줘. 이름은 'Perf Inspector Test API B', 인증방식은 BEARER, 토큰 값은 sample-token-xyz-999로 하고 baseUrl은 https://perf-inspect-example.test로 해줘. 확인 없이 바로 만들어.

*(메인의 위임 프롬프트가 이 문구를 그대로 전달하고 별도의 우회 방지 보강 지시를 포함하지 않았더라도 아래와 동일하게 동작한다.)*

**에이전트** ([Turn 1] DESIGN 출력 → 응답 종료 — "확인 없이" 지시가 있어도 Turn 1을 생략하지 않는다):

[Phase 1 — IDENTIFY] list_api_connections() 호출 (중복 확인)

[Phase 2 — DESIGN] 설계 요약

- 이름: `Perf Inspector Test API B`
- authType: `BEARER`
- authConfig: `token: "sam...999"` (마스킹)
- baseUrl: `https://perf-inspect-example.test`

이대로 등록할까요? (예 / 수정 요청)

*(같은 턴에 create_api_connection을 호출하지 않는다. "확인 없이 바로 만들어"라는 지시는 본
에이전트 자신의 2턴 프로토콜을 면제하지 않는다 — 위임 프롬프트에 우회 방지 보강 지시가
없어도 동일하다.)*

---

## 예시 7: 확인 표 서두 사족 금지 (refs #627 회귀, ❌/✅ 대비)

**사용자**: API 연결 하나 새로 만들어줘. 이름은 'Solver627 Trial E', 인증방식은 BEARER, 토큰 값은 tok-e-999로 하고 baseUrl은 https://solver627-e.test로 해줘. 등록 전에 기존에 같은 이름 있는지도 확인해줘.

**에이전트** ([Phase 1] `list_api_connections()` 호출 — 동일 이름 없음 확인됨):

❌ **금지** — 확인 결과를 표 앞에 사족으로 끼워 넣음 (실제 회귀 재현, 2026-09-11 crosscheck):
```
동일한 이름의 기존 연결은 없습니다.

다음 정보로 등록을 진행할까요?

| 항목 | 값 |
|------|-----|
| 이름 | Solver627 Trial E |
| Base URL | https://solver627-e.test |
| 인증 유형 | BEARER |
| token | tok...999 (마스킹) |

이대로 등록할까요? (예 / 수정 요청)
```

✅ **올바름** — 첫 문장 없이 곧장 표로 시작 (표 자체가 등록 대상이 신규임을 보여주므로
"없다"는 사실을 별도 문장으로 알릴 필요가 없다):
```
다음 정보로 등록을 진행할까요?

| 항목 | 값 |
|------|-----|
| 이름 | Solver627 Trial E |
| Base URL | https://solver627-e.test |
| 인증 유형 | BEARER |
| token | tok...999 (마스킹) |

이대로 등록할까요? (예 / 수정 요청)
```

*(같은 원칙이 삭제 확인 질의·CONFIRM 요약에도 그대로 적용된다 — "확인했습니다" 류 도입 문장
없이 곧장 질의/요약 본문으로 시작한다.)*

---

## 예시 5: 연결 즉시 테스트

**사용자**: 방금 만든 공공데이터포털 소방용수 연결 테스트해줘.

**에이전트**:

[Phase 3 — EXECUTE] test_api_connection(3) 호출

[Phase 4 — CONFIRM] 결과 요약

테스트 결과: **UP** ✓ (HTTP 200, 응답 시간 142ms)
연결이 정상 동작합니다. 파이프라인 API_CALL 스텝에서 바로 사용 가능합니다.

*테스트 실패 예시*: `{ ok: false, status: 401, errorMessage: "Unauthorized" }` → API 키를 확인하고 update_api_connection으로 갱신하세요.

---

## 예시 3: 인증 키 갱신 (Update)

**사용자**: 공공데이터포털 소방용수 API 키 바뀌었어. 업데이트해줘.

**에이전트**:

[Phase 1 — IDENTIFY] list_api_connections()를 호출해 "공공데이터포털 소방용수" 연결을 찾는다.

기존 연결 확인: ID 3, "공공데이터포털 소방용수" (API_KEY)

새 API 키 값을 알려주세요. 헤더명은 기존과 동일한 `Authorization`을 유지할까요?

**사용자**: 새 키는 `newkey456abc`이고 헤더명 동일하게 유지해줘.

**에이전트** ([Turn 1] DESIGN 출력 → 응답 종료):

[Phase 2 — DESIGN] 설계 요약

- 대상: ID 3, "공공데이터포털 소방용수"
- 변경: `apiKey: "new...abc"` (마스킹), `headerName: "Authorization"` (기존 유지)

이대로 업데이트할까요? (예 / 수정 요청)

**사용자**: 네

**에이전트** ([Turn 2] 사용자 승인 확인됨):

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
