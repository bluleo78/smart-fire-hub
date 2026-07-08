# Agent SDK 모드 OAuth 인증 지원 설계

- 작성일: 2026-07-08
- 대상 앱: `apps/firehub-ai-agent` (+ `apps/firehub-web` 관리자 설정 연동)
- 관련: [2026-06-23 OpenCode Provider 설계](2026-06-23-opencode-provider-design.md)

## 배경

firehub-ai-agent는 요청별로 AI 에이전트 실행 방식을 선택한다. `AgentType` enum + 팩토리(전략 패턴)로 4개 값이 3개 provider에 매핑된다.

| agentType | Provider | 실행 | 인증 (현재) |
|---|---|---|---|
| `sdk` | `ClaudeSdkChatProvider` → `agent-sdk.ts` | Agent SDK `query()` (인-프로세스) | **`ANTHROPIC_API_KEY` 전용** |
| `cli` | `ClaudeCliChatProvider` → `agent-cli.ts` | `claude -p` 서브프로세스 | **OAuth** (`CLAUDE_CODE_OAUTH_TOKEN`) |
| `cli-api` | `ClaudeCliChatProvider` → `agent-cli.ts` | `claude -p` 서브프로세스 | `ANTHROPIC_API_KEY` |
| `opencode` | `OpenCodeChatProvider` → `agent-opencode.ts` | `opencode run` 서브프로세스 | 배포 전역 `opencode auth` |

`cli` 모드는 이미 구독 OAuth 토큰(`claude setup-token` 산출물)을 `CLAUDE_CODE_OAUTH_TOKEN` 환경변수로 주입해 인증한다. 그러나 `sdk` 모드는 API 키만 지원한다 (`provider-factory.ts`에서 `config.apiKey` 하드 요구, `agent-sdk.ts:155-160`).

## 목표

`sdk` 모드가 OAuth 토큰으로도 인증할 수 있게 한다. 요청에 OAuth 토큰이 있으면 구독 인증을 우선 사용하고, 없으면 기존처럼 API 키로 폴백한다. 기존 `sdk` + API 키 사용처는 변경 없이 계속 동작해야 한다.

## 비목표

- 새 `agentType`(예: `sdk-oauth`) 신설 — 하지 않는다. 단일 `sdk` 모드가 두 인증을 모두 처리한다.
- 영속 DB 설정 **키** `ai.cli_oauth_token` 리네임 — 하지 않는다 (마이그레이션 필요, 이득 없음).
- OpenCode / cli / cli-api 인증 동작 변경 — 하지 않는다.

## 검증된 전제

Agent SDK `query()`가 `options.env`의 `CLAUDE_CODE_OAUTH_TOKEN`을 인식해 구독 인증하는 동작은 형제 프로덕션 앱 `smart-workplace`(`apps/workplace-ai-agent/src/agent/sdk-runner.ts:37-39`)에서 **실제로 검증됨**. 해당 앱은 OAuth 전용으로 다음 패턴을 사용한다:

```ts
const env: NodeJS.ProcessEnv = { ...process.env };
delete env.ANTHROPIC_API_KEY;
env.CLAUDE_CODE_OAUTH_TOKEN = i.token;
// ... query({ ..., env })
```

> 주의: Agent SDK 공식 문서는 OAuth/구독 인증을 문서화된 방식으로 지원하지 않는다(`ANTHROPIC_API_KEY` 및 클라우드 프로바이더만 명시). 따라서 (a) SDK 업데이트로 깨질 수 있고, (b) 서드파티 제품에 구독 로그인을 제공하는 것은 ToS상 제한된다. 본 변경은 **내부 팀 도구 + 자체 구독** 용도이며 이미 `cli` 모드에서 동일하게 사용 중이라, 기존 리스크를 SDK 경로로 확장하는 것이다.

## 설계

### 1. 인증 분기 (`agent-sdk.ts`)

`executeAgent()`의 `cleanEnv` 구성부(현재 155-160행)를 교체한다. `cleanEnv`는 이미 `{ ...process.env }` 기반(133행)이라 env 교체 gotcha는 없다. 우선순위는 **OAuth > API 키 > process.env 폴백**으로, `cli` 구독 모드(`agent-cli.ts:278-293`) 및 smart-workplace와 동일하다.

```ts
if (oauthToken) {
  // OAuth 우선: 메터드 API 키를 제거하고 구독 토큰만 남긴다.
  delete cleanEnv.ANTHROPIC_API_KEY;
  cleanEnv.CLAUDE_CODE_OAUTH_TOKEN = oauthToken;
} else if (apiKey) {
  cleanEnv.ANTHROPIC_API_KEY = apiKey;
} else if (!cleanEnv.ANTHROPIC_API_KEY) {
  yield { type: 'error' as const, message: 'No API key or OAuth token provided' };
  return;
}
```

`executeAgent()` 옵션 타입은 이미 `cliOauthToken?: string`(60행)을 선언하고 있으나 사용되지 않는다 → `oauthToken?: string`으로 리네임하고 위 로직에서 사용한다.

### 2. 필드 리네임 `cliOauthToken` → `oauthToken` (요청/코드 레벨)

`sdk`·`cli`가 공유하는 중립 이름으로 통합한다. 대상:

- `src/providers/types.ts:48` — `ProviderConfig.cliOauthToken` → `oauthToken`
- `src/providers/provider-factory.ts` — `cli` 케이스 `config.cliOauthToken` → `config.oauthToken`; **`sdk` 케이스**에서 `config.apiKey` 하드 요구(throw) 제거 → `apiKey`/`oauthToken` 중 하나라도 있으면 통과, `new ClaudeSdkChatProvider(config.apiKey, config.model || DEFAULT_MODEL, config.oauthToken)`
- `src/providers/claude-sdk-chat-provider.ts` — 생성자에 `oauthToken?` 추가, `executeAgent()` 호출 시 전달
- `src/providers/claude-cli-chat-provider.ts:11,30` — `cliOauthToken` → `oauthToken`
- `src/agent/agent-sdk.ts:60` — 옵션 필드 리네임
- `src/agent/agent-cli.ts:93-94,106,282` — 필드/주석 리네임 (`childEnv.CLAUDE_CODE_OAUTH_TOKEN = oauthToken`)
- `src/routes/chat.ts:34,85` — 요청 body 구조분해 및 전달
- `src/routes/proactive.ts:34,289` — 동일

`chat.ts:184-198`의 `POST /agent/cli-auth/verify`는 이미 `CLAUDE_CODE_OAUTH_TOKEN`을 `{ ...process.env, ... }`로 주입하므로 로직 변경 없음(필요 시 변수명만 정리).

### 3. 프론트엔드 (`apps/firehub-web`)

- `src/pages/admin/SettingsPage.tsx` — 관리자 설정의 OAuth 토큰(설정 키 `ai.cli_oauth_token`, **키는 유지**) 값을 채팅/proactive 요청 body에 `oauthToken`으로 전달한다. `sdk` 모드 선택 시에도 토큰이 있으면 함께 전송하도록 확장.
- DB 설정 키·라벨은 변경하지 않는다. 프론트는 `ai.cli_oauth_token` 값 → body `oauthToken` 매핑만 담당.

### 데이터 흐름 (sdk + OAuth)

```
관리자 설정(ai.cli_oauth_token) → 채팅 요청 body { agentType:'sdk', oauthToken }
  → chat.ts → provider-factory(sdk) → ClaudeSdkChatProvider(oauthToken)
  → executeAgent(oauthToken) → cleanEnv.CLAUDE_CODE_OAUTH_TOKEN 설정 + ANTHROPIC_API_KEY 삭제
  → query({ ..., env: cleanEnv }) → 구독 인증
```

## 에러 처리

- `sdk` 모드에서 `oauthToken`·`apiKey`·`process.env.ANTHROPIC_API_KEY` 모두 없음 → `error` SSE 이벤트("No API key or OAuth token provided") 후 종료 (기존 "API key not provided" 대체).
- 잘못된/만료 OAuth 토큰 → SDK 실행 중 인증 오류로 표면화(기존 SDK 에러 경로 재사용). 사전 검증은 본 스코프 아님(`cli-auth/verify`가 별도로 존재).

## 테스트

- `agent-sdk.test.ts`: OAuth 분기 3케이스 — (a) `oauthToken` 있음 → `cleanEnv.CLAUDE_CODE_OAUTH_TOKEN` 설정 + `ANTHROPIC_API_KEY` 삭제, (b) `oauthToken` 없고 `apiKey` 있음 → `ANTHROPIC_API_KEY` 설정, (c) 셋 다 없음 → error 이벤트. `query`는 모킹하고 전달된 `options.env` 검증.
- `provider-factory.test.ts`: `sdk` 케이스가 `apiKey`만/`oauthToken`만/둘 다로 provider를 생성하고, 셋 다 없으면 실패하는지.
- `firehub-web` E2E: 기존 설정 저장 흐름에 OAuth 토큰 저장 + sdk 모드 요청 전송 확장(기존 `embedding-settings.spec.ts` 패턴 참고).

## 위험 및 완화

| 위험 | 완화 |
|---|---|
| Agent SDK 공식 미지원 → 업데이트 시 OAuth 경로 파손 | smart-workplace에서 검증됨; 파손 시 `cli` 모드가 동일 인증의 대체 경로. SDK 버전 업 시 회귀 테스트로 조기 감지 |
| OAuth 우선 규칙이 기존 sdk+API키 사용처를 바꿈 | OAuth 토큰이 **있을 때만** 우선 적용; 토큰 미전송 시 동작 불변. 프론트는 sdk 모드에서 토큰이 설정돼 있을 때만 전송 |
| 필드 리네임 누락으로 런타임 undefined | `cliOauthToken` 전 참조를 `oauthToken`으로 일괄 교체 + typecheck로 검증 |
