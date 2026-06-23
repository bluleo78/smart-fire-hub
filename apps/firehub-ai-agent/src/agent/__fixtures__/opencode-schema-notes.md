# OpenCode 실측 메모 (opencode --version: 1.17.9)

실측 환경: macOS, Bedrock OpenAI-호환 게이트웨이(`bedrock-mantle.us-east-1.api.aws/openai/v1`), 모델 `google.gemma-4-31b`.
프로바이더는 OpenCode 커스텀 OpenAI-compatible provider(`npm: @ai-sdk/openai-compatible`, baseURL+apiKey)로 구성. provider/model은 전역(OPENCODE_CONFIG), mcp/permission은 요청별 cwd opencode.json — 분리 검증 완료.

## `--format json` 이벤트 스키마 (확정)
각 라인: `{ "type": <string>, "timestamp": <ms>, "sessionID": "ses_...", "part": {...} }` (error 는 part 대신 error)

| 의미 | type | 데이터 위치 |
|---|---|---|
| 세션 id | (모든 이벤트 공통) | 최상위 `sessionID` = `ses_...` (OpenCode가 발급) |
| 턴/스텝 시작 | `step_start` | `part.type="step-start"` |
| 텍스트 | `text` | `part.text` (전체 텍스트; 델타 아님 — gemma 기준 1회) |
| 도구 호출+결과 | `tool_use` (그리고 `tool`) | `part.type="tool"`, `part.tool`=도구명, `part.callID`, `part.state.status`(running→completed), `part.state.input`(객체), `part.state.output`(문자열=결과) |
| 턴/스텝 종료 | `step_finish` | `part.reason`("stop"=최종), `part.tokens.{input,output,reasoning,total,cache}`, `part.cost` |
| 에러 | `error` | `error.name`, `error.data.message` |

- **토큰 사용량 제공됨**: `step_finish.part.tokens.input` / `.output`. (0 하드코딩 금지)
- **`session_finish`/`done` 이벤트 없음**: 완료 = `step_finish`(reason="stop") + 스트림 종료.
- **tool_use 이벤트가 input+output 모두 포함**: `state.status="completed"` 라인 하나에 `state.input`과 `state.output`이 함께 옴. 파서는 이 한 이벤트에서 `tool_use`와 `tool_result` 둘 다 emit하면 됨. (`type:"tool"` 라인은 중간 상태 업데이트)

## MCP 도구 네이밍 (확정)
- 서버명 `firehub`의 도구 `list_categories` → OpenCode 노출명 **`firehub_list_categories`** (= `<server>_<tool>`, snake 결합).
- permission/allow 패턴: **`firehub_*`** (와일드카드 동작 확인).

## 권한(permission) — #0 보안 잠금 (확정 동작)
- `opencode.json.permission` 키: `bash`, `edit`, `write`, `webfetch` 등(도구명 기준). 값 `"deny"|"ask"|"allow"`.
- MCP 도구는 도구명(`firehub_*`)으로 평가됨 — 로그: `evaluated permission=firehub_list_categories ... action=allow`.
- 빌트인 도구는 `tools` 맵으로도 끌 수 있음: `{"bash": false, ...}` (payload에서 제외).
- 권장 구성: `tools` 로 빌트인 전부 비활성(필요시 `task`만 유지) + `permission`으로 이중 안전망. firehub MCP만 노출.

## 모델/Provider (옵션 3, 전역 설정)
- 커스텀 provider 블록(전역):
  ```json
  "provider": { "bedrock-mantle": {
    "npm": "@ai-sdk/openai-compatible",
    "options": { "baseURL": "https://bedrock-mantle.us-east-1.api.aws/openai/v1", "apiKey": "<KEY>" },
    "models": { "google.gemma-4-31b": {} } } },
  "model": "bedrock-mantle/google.gemma-4-31b"
  ```
- 우리 요청별 opencode.json 은 `model`/`provider` 미포함(전역 상속) — 검증 완료.

## 세션 재개 (확정 — 설계 수정 필요)
- OpenCode가 세션 id를 **자체 발급**(`ses_...`). 외부에서 만든 `oc-<uuid>`를 쓰는 가정은 **틀림**.
- 따라서: 첫 이벤트의 `sessionID`를 캡처 → 트랜스크립트 키 + `--session <ses_...>` 재개에 사용. (Claude의 `claudeSessionId` 캡처 패턴과 동일)
- `init` SSE는 첫 이벤트에서 sessionID를 받은 뒤 emit하거나, firehub 세션 id ↔ opencode ses_ 매핑을 저장.

## ⚠ 중대한 end-to-end 블로커 (이 게이트웨이+모델 한정)
- 실제 firehub stdio-server(36개 도구)를 붙이면 게이트웨이가 **HTTP 400 "Generation failed"** 반환.
- 판별: trivial 도구 1개 → 200, trivial 도구 36개 → 200, **firehub 36개 → 400**. 즉 **카운트가 아니라 firehub 도구의 JSON 스키마 복잡도**를 이 OpenAI-호환 Bedrock 게이트웨이가 거부.
- Anthropic API(현 sdk/cli 프로바이더)는 동일 스키마를 수용하므로 firehub 자체 문제 아님 — 게이트웨이 스키마 호환성 문제.
- 해소 방향(택1, 구현/사용자 결정 필요):
  1. 게이트웨이가 수용하는 모델/엔드포인트 사용
  2. firehub 도구 스키마를 OpenCode 노출용으로 단순화/정제(문제 keyword 제거)
  3. 노출 도구를 부분집합으로 제한
- 구체적으로 어떤 스키마 keyword가 400을 유발하는지는 구현 단계 디버깅 대상.

## 비용/턴 상한
- `opencode run` 자체에 예산($)/턴 수 상한 플래그 없음(확인). 비용 가드는 `step_finish.tokens` 누적으로 앱에서 구현하거나 별도 정책 필요 — v1 리스크로 문서화.

## AGENTS.md / 위임
- (미확정) AGENTS.md 시스템 지시 적용 여부는 firehub 36도구 400 때문에 완전한 위임 시나리오 검증 못함. gemma는 `task`(subagent) 도구로 위임 시도함은 확인(subagent_type="general"). firehub 전용 subagent 위임은 스키마 400 해소 후 재검증 필요.
