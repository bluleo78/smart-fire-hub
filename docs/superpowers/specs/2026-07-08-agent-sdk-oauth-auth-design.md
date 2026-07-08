# Agent SDK 모드 OAuth 인증 지원 설계

- 작성일: 2026-07-08
- 대상 앱: `apps/firehub-api` (Spring Boot 프록시), `apps/firehub-web` (관리자 설정), `apps/firehub-ai-agent` (Agent SDK 실행)
- 관련: [2026-06-23 OpenCode Provider 설계](2026-06-23-opencode-provider-design.md)

## 배경

firehub-ai-agent는 요청별로 AI 에이전트 실행 방식을 선택한다(`AgentType` enum + 팩토리). 4개 enum 값이 3개 provider에 매핑된다.

| agentType | ai-agent Provider | 인증 (현재) |
|---|---|---|
| `sdk` | `ClaudeSdkChatProvider` → `agent-sdk.ts` (Agent SDK `query()`, 인-프로세스) | **`ANTHROPIC_API_KEY` 전용** |
| `cli` | `ClaudeCliChatProvider` → `agent-cli.ts` (`claude -p` 서브프로세스) | **OAuth** (`CLAUDE_CODE_OAUTH_TOKEN`) |
| `cli-api` | `ClaudeCliChatProvider` → `agent-cli.ts` | `ANTHROPIC_API_KEY` |
| `opencode` | `OpenCodeChatProvider` → `agent-opencode.ts` | 배포 전역 `opencode auth` |

### 요청 경로와 인증 주입 지점 (핵심)

실제 인증 자격은 프론트가 아니라 **firehub-api(Spring Boot) 프록시가 관리자 설정에서 읽어 에이전트 요청 body에 주입**한다.

```
Frontend(관리자 설정 저장) → firehub-api 프록시(AiAgentProxyService/ProactiveAiClient)
  → 설정에서 자격 조회·검증 후 body에 주입 → ai-agent(/agent/chat, /agent/proactive) → Provider
```

현재 프록시는 OAuth 토큰(`ai.cli_oauth_token`, AES 복호화)을 **`agentType=="cli"`일 때만** body에 `cliOauthToken`으로 주입한다:
- 채팅: `AiAgentProxyService.java:151-152`(조회), `:156-159`(검증), `:188-190`(주입)
- 프로액티브: `ProactiveJobAsyncRunner.java:120-133`, `ProactiveAiClient.java:49,59-60`
- 검증: `AiController.java:88-98`(`/auth-status` — cli는 `verifyCliToken`, 그 외 `verifyApiKey`)

sdk 모드는 프록시가 OAuth 토큰을 애초에 보내지 않으므로, **ai-agent만 고쳐서는 동작하지 않는다.** 프록시 계층이 진짜 관문이다.

## 목표

`sdk` 모드가 OAuth 토큰(`CLAUDE_CODE_OAUTH_TOKEN`, `claude setup-token` 산출물)으로도 인증할 수 있게 한다. sdk 요청에 OAuth 토큰이 있으면 구독 인증을 우선 사용하고, 없으면 기존처럼 API 키로 폴백한다. 기존 `sdk`+API 키 사용처는 변경 없이 동작해야 한다.

## 비목표

- 새 `agentType`(예: `sdk-oauth`) 신설 — 하지 않는다. 단일 `sdk` 모드가 두 인증을 모두 처리한다.
- 영속 DB 설정 **키** `ai.cli_oauth_token` 리네임 — 하지 않는다(마이그레이션 필요, 이득 없음). 프록시/프론트는 이 키를 그대로 읽고 body 전송 시에만 중립 키를 쓴다.
- OpenCode / cli / cli-api 인증 동작 변경 — 하지 않는다(단, 아래 body 키 리네임은 cli 경로에도 공통 적용).

## 검증된 전제

Agent SDK `query()`가 `options.env`의 `CLAUDE_CODE_OAUTH_TOKEN`으로 구독 인증하는 동작은 형제 프로덕션 앱 `smart-workplace`(`apps/workplace-ai-agent/src/agent/sdk-runner.ts:37-39`)에서 **실제 검증됨**:

```ts
const env: NodeJS.ProcessEnv = { ...process.env };
delete env.ANTHROPIC_API_KEY;
env.CLAUDE_CODE_OAUTH_TOKEN = i.token;   // query({ ..., env })
```

> 주의: Agent SDK 공식 문서는 OAuth/구독 인증을 지원 방식으로 명시하지 않는다(`ANTHROPIC_API_KEY`·클라우드 프로바이더만). 따라서 (a) SDK 업데이트로 깨질 수 있고, (b) 서드파티 제품에 구독 로그인을 제공하는 것은 ToS상 제한된다. 본 변경은 **내부 팀 도구 + 자체 구독** 용도이며 이미 `cli` 모드에서 동일하게 사용 중이라, 기존 리스크를 sdk 경로로 확장하는 것이다.

## 설계

### body 계약: `cliOauthToken` → `oauthToken` 리네임

프록시(firehub-api)가 보내고 ai-agent가 읽는 요청 body 키를 중립 이름 `oauthToken`으로 통일한다. sdk·cli 공통. firehub-api·ai-agent는 모노레포로 함께 배포되므로 양쪽을 한 번에 바꾼다. (DB 키 `ai.cli_oauth_token`, `getDecryptedCliOauthToken()` 메서드명은 유지.)

### ① firehub-api — 채팅 프록시 (`AiAgentProxyService.java`)

`streamChat`(대략 145-190행):
- OAuth 토큰 조회를 sdk에도 확장: `cliTokenOpt` 계산 조건 `"cli".equals(agentType)` → `"cli".equals(agentType) || "sdk".equals(agentType)`.
- 인증 누락 검사(`missingCredential`): sdk는 `apiKeyOpt` **또는** `cliTokenOpt` 중 하나라도 있으면 통과. (현재 sdk는 `apiKeyOpt.isEmpty()`만 검사 → `apiKeyOpt.isEmpty() && cliTokenOpt.isEmpty()`로.)
- 오류 메시지: sdk에서 둘 다 없을 때 "API 키 또는 OAuth 토큰이 설정되지 않았습니다" 안내.
- body 주입: `cli`뿐 아니라 `sdk`일 때도 `cliTokenOpt.ifPresent(token -> requestBody.put("oauthToken", token))`. `apiKey`는 기존대로 non-opencode에 주입(sdk에서 둘 다 있으면 ai-agent가 OAuth 우선 선택).
- 기존 `cli` 경로의 body 키 `"cliOauthToken"` → `"oauthToken"`.

### ② firehub-api — 인증 상태 검증 (`AiController.java` `/auth-status`)

`getAuthStatus`(88-99행): sdk에서 OAuth 토큰이 설정돼 있으면 `verifyCliToken()` 경로를 타도록 분기 조정. 즉 "cli 이거나, (sdk이고 OAuth 토큰 존재)"이면 토큰 검증, 그 외 `verifyApiKey()`. (검증 자체는 기존 `AiAgentProxyService.verifyCliToken()` 재사용 — ai-agent `/agent/cli-auth/verify` 호출.)

### ③ firehub-api — 프로액티브 경로

- `ProactiveJobAsyncRunner.java:119-133`: 토큰 조회 조건 `"cli".equals(agentType)` → sdk 포함으로 확장, 조회한 토큰을 `ProactiveAiClient.execute(...)`에 전달.
- `ProactiveAiClient.java:49,56-61`: body 키 `"cliOauthToken"` → `"oauthToken"`, `apiKey`/`oauthToken` 둘 다 있으면 그대로 전송(ai-agent가 우선순위 결정).

### ④ ai-agent — Agent SDK 인증 분기 (`agent-sdk.ts`)

`executeAgent()`의 `cleanEnv` 구성부(현재 155-160행)를 교체. `cleanEnv`는 이미 `{ ...process.env }` 기반(133행)이라 env 교체 gotcha 없음. 우선순위 **OAuth > API 키 > process.env 폴백** (cli 구독 모드·smart-workplace와 동일):

```ts
if (oauthToken) {
  delete cleanEnv.ANTHROPIC_API_KEY;              // OAuth 우선 → 메터드 키 제거
  cleanEnv.CLAUDE_CODE_OAUTH_TOKEN = oauthToken;
} else if (apiKey) {
  cleanEnv.ANTHROPIC_API_KEY = apiKey;
} else if (!cleanEnv.ANTHROPIC_API_KEY) {
  yield { type: 'error' as const, message: 'No API key or OAuth token provided' };
  return;
}
```

`AgentOptions`(60행)의 미사용 `cliOauthToken?: string` → `oauthToken?: string`으로 리네임하고 위 로직에서 구조분해.

### ⑤ ai-agent — 필드 리네임 `cliOauthToken` → `oauthToken`

- `src/routes/chat.ts:34,85` — body 구조분해 및 `ProviderConfig` 전달.
- `src/routes/proactive.ts:34,289` — 동일(`ProactiveRequest.cliOauthToken` → `oauthToken`).
- `src/providers/types.ts:48` — `ProviderConfig.cliOauthToken` → `oauthToken`.
- `src/providers/provider-factory.ts` — `sdk` 케이스에서 `config.apiKey` 하드 요구(throw) 제거 → `apiKey`/`oauthToken` 중 하나라도 있으면 통과, `new ClaudeSdkChatProvider(config.apiKey, config.model || DEFAULT_MODEL, config.oauthToken)`. `cli` 케이스 `config.cliOauthToken` → `config.oauthToken`.
- `src/providers/claude-sdk-chat-provider.ts` — 생성자에 `oauthToken?: string` 추가, `AgentOptions`에 전달.
- `src/providers/claude-cli-chat-provider.ts:11,30` + `src/agent/agent-cli.ts:93-94,106,282` — `cliOauthToken` → `oauthToken`(`childEnv.CLAUDE_CODE_OAUTH_TOKEN = oauthToken`).
- `chat.ts:184-198` `/agent/cli-auth/verify`는 `{ ...process.env, CLAUDE_CODE_OAUTH_TOKEN }` 주입 유지 — 로직 변경 없음(변수명만 정리 가능).

### ⑥ firehub-web — 관리자 설정 (`SettingsPage.tsx`)

OAuth 토큰 입력 필드가 현재 `agent_type === 'cli'`일 때만 노출된다(300행). → `'cli'` 또는 `'sdk'`일 때 노출하도록 조건 확장. sdk는 API 키·OAuth 토큰 둘 다 가능(OAuth 우선)하므로, sdk 선택 시 **API 키 필드 + OAuth 토큰 필드를 모두** 표시한다. 저장 로직(`ai.cli_oauth_token` 조건부 저장, 165-168행)은 그대로 유효. DB 키·라벨 불변.

### 데이터 흐름 (sdk + OAuth)

```
관리자 설정(ai.agent_type=sdk, ai.cli_oauth_token=…) 저장
  → firehub-api AiAgentProxyService: 토큰 복호화·검증 → body { agentType:'sdk', oauthToken, (apiKey?) }
  → ai-agent /agent/chat → chat.ts → ProviderFactory(sdk) → ClaudeSdkChatProvider(oauthToken)
  → executeAgent(oauthToken) → cleanEnv.CLAUDE_CODE_OAUTH_TOKEN 설정 + ANTHROPIC_API_KEY 삭제
  → query({ ..., env: cleanEnv }) → 구독 인증
```

## 에러 처리

- 프록시(firehub-api): sdk에서 apiKey·oauthToken 모두 미설정 → 기존 `missingCredential` 경로로 SSE `error` 전송("API 키 또는 OAuth 토큰이 설정되지 않았습니다"). ai-agent 도달 전 차단.
- ai-agent: 만약 프록시를 우회한 직접 요청 등으로 셋 다 없으면 `error` SSE("No API key or OAuth token provided") 후 종료.
- 만료/무효 OAuth 토큰: SDK 실행 중 인증 오류로 표면화(기존 에러 경로). 사전 검증은 `/auth-status`(위 ②)가 담당.

## 테스트

- **firehub-api** (`@SpringBootTest`, `AiAgentProxyServiceTest` 등): sdk+OAuth일 때 body에 `oauthToken` 주입 확인, sdk에서 apiKey·oauthToken 둘 다 없으면 `missingCredential` error, sdk+apiKey만이면 기존대로. `ProactiveAiClient`/`ProactiveJobAsyncRunner` sdk 토큰 전달. `/auth-status` sdk+OAuth 분기. (`getDecryptedCliOauthToken` 모킹.)
- **ai-agent** (`agent-sdk.test.ts`, `provider-factory.test.ts`): OAuth 분기 3케이스((a) oauthToken→`cleanEnv.CLAUDE_CODE_OAUTH_TOKEN` 설정+`ANTHROPIC_API_KEY` 삭제, (b) apiKey만→`ANTHROPIC_API_KEY`, (c) 셋 다 없음→error). `query` 모킹, 전달된 `options.env` 검증. factory `sdk` 케이스가 apiKey만/oauthToken만/둘 다로 생성, 셋 다 없으면 실패.
- **firehub-web** (Playwright E2E): agent_type=sdk 선택 시 OAuth 토큰 필드 노출, 토큰 저장 payload 검증(`ai.cli_oauth_token`). 기존 `embedding-settings.spec.ts`/admin fixture 패턴 확장.

## 위험 및 완화

| 위험 | 완화 |
|---|---|
| Agent SDK 공식 미지원 → 업데이트 시 OAuth 경로 파손 | smart-workplace에서 검증됨; 파손 시 `cli` 모드가 동일 인증의 대체 경로. 버전 업 시 회귀 테스트로 조기 감지 |
| OAuth 우선 규칙이 기존 sdk+API키 사용처를 바꿈 | OAuth 토큰이 **설정돼 있을 때만** 우선 적용; 미설정 시 동작 불변 |
| body 키 리네임이 firehub-api↔ai-agent 계약을 깸 | 두 앱을 동일 커밋/배포로 함께 변경. cli 경로도 함께 리네임해 혼재 없음. typecheck + 통합 테스트로 검증 |
| 프록시/프론트/에이전트 3계층 중 일부만 반영 시 무동작 | 계층별 태스크에 계약 키(`oauthToken`) 명시. 통합 스모크(관리자 설정 sdk+OAuth → 채팅 1회)로 종단 확인 |
