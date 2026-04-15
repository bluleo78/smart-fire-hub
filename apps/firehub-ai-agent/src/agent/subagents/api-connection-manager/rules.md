# api-connection-manager — 연결 규칙

## 0. 필수 입력 및 생성 워크플로

API 연결 생성 시 다음 순서로 정보를 수집한다:

1. **연결 이름** — 서비스명 + 목적 (예: `"Make.com API"`)
2. **Base URL** — 서비스의 기본 URL (예: `https://api.make.com/v2`). trailing slash 제거 필수.
3. **인증 유형** — API_KEY / BEARER / OAUTH2
4. **authConfig** — 인증 유형별 설정 (아래 섹션 참조)
5. **헬스체크 경로** (선택) — `/health`, `/status` 등. 생략 시 주기적 점검 미수행.

**URL 정규화 규칙**: baseUrl에 trailing slash가 있으면 제거한다.
예: `https://api.example.com/` → `https://api.example.com`

## 1. authType별 authConfig 구조

### API_KEY 방식

외부 API가 헤더 또는 쿼리 파라미터에 고정 키를 요구할 때 사용한다.

authConfig 구조:
```json
{
  "apiKey": "실제-API-키-값",
  "headerName": "Authorization"
}
```

| 필드 | 필수 | 설명 |
|------|------|------|
| `apiKey` | ✅ | 실제 API 키 값 |
| `headerName` | ✅ | 키를 전달할 헤더 이름 (예: `X-API-Key`, `Authorization`, `Api-Key`) |

**주의**: `Authorization` 헤더에 넣는 경우 값 앞에 `"Key "` 나 `"Bearer "`를 붙여야 하는지 사용자에게 확인한다.

### BEARER 방식

OAuth2 또는 JWT 기반 토큰 인증 시 사용한다. `Authorization: Bearer {token}` 헤더를 자동으로 추가한다.

authConfig 구조:
```json
{
  "token": "실제-Bearer-토큰-값"
}
```

| 필드 | 필수 | 설명 |
|------|------|------|
| `token` | ✅ | Bearer 토큰 값 (JWT, OAuth Access Token 등) |

## 2. 삭제 체크리스트

삭제 전 반드시 순서대로 수행한다:

1. `get_api_connection(id)` — 연결 이름·authType 확인
2. 사용자에게 이름 명시: `"'{name}' 연결을 삭제하면 이 연결을 사용하는 파이프라인 API_CALL 스텝이 동작하지 않습니다."`
3. 사용자의 **명시적 평문 확인** ("네", "삭제해줘", "맞아요") 필수 — 질문형("삭제할까요?")은 확인이 아님
4. `delete_api_connection(id)` 호출

## 3. 연결 이름 규칙

- 서비스명 + 목적: `"카카오 모빌리티 API"`, `"공공데이터포털 소방"`, `"내부 분석 서버"`
- 너무 짧은 이름 지양: `"test"`, `"api1"` → 충분히 설명적인 이름 유도
- 중복 이름 허용되지만 혼란 유발 → 생성 전 `list_api_connections()`로 중복 확인 권장

## 4. 수정 가이드라인

- 이름·설명만 변경: `authConfig` 미제공 가능 (기존 암호화 값 유지)
- 인증 갱신(키 로테이션): `authConfig` 전체 재제공 필수 — 부분 갱신 불가
- authType 변경: 기존 authConfig는 새 authType의 필드 구조로 완전히 교체해야 함

## 5. test_api_connection 사용법

`test_api_connection(id)` 도구를 사용하면 저장된 연결을 즉시 테스트할 수 있다.
- 생성 직후 사용자가 확인을 요청하거나, "연결이 잘 되는지 확인해줘" 같은 요청에 사용.
- healthCheckPath가 설정된 경우 해당 경로로 GET 요청, 없으면 baseUrl로 요청.
- 결과: `{ ok, status, latencyMs, errorMessage }` — DB에도 반영되어 목록에 lastStatus로 표시됨.

## 6. 현재 미지원 기능

다음 기능은 현재 지원되지 않으며, 사용자가 요청하면 솔직하게 안내한다:

- **OAuth2 토큰 자동 갱신**: 현재 만료 처리 없음, 수동으로 `update_api_connection()`으로 갱신 필요
