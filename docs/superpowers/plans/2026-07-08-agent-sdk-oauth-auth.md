# Agent SDK 모드 OAuth 인증 지원 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ai-agent `sdk` 모드가 OAuth 구독 토큰(`CLAUDE_CODE_OAUTH_TOKEN`)으로도 인증되게 하고, 그 토큰을 firehub-api 프록시가 sdk 요청에 주입하며, 관리자 설정에서 sdk 선택 시 OAuth 토큰을 입력할 수 있게 한다.

**Architecture:** 요청 경로는 `Frontend(설정) → firehub-api 프록시(자격 주입) → ai-agent(실행)`. 세 계층 모두 수정한다. body 계약 키는 `cliOauthToken` → `oauthToken`(중립)으로 통일. sdk 인증 우선순위는 OAuth > API 키 > process.env 폴백(검증된 `smart-workplace/apps/workplace-ai-agent/src/agent/sdk-runner.ts` 및 firehub 기존 `cli` 모드와 동일).

**Tech Stack:** ai-agent(Node/TS, Vitest+nock), firehub-api(Spring Boot/Java, JUnit+`@SpringBootTest`), firehub-web(React/TS, Playwright E2E).

## Global Constraints

- 코드 주석은 한국어(무엇을·왜). (모든 앱 CLAUDE.md)
- DB 설정 키 `ai.cli_oauth_token` 및 `SettingsService.getDecryptedCliOauthToken()` 메서드명은 **변경 금지**(마이그레이션 회피). 리네임은 요청 body 키/코드 변수에 한정.
- 코드 변경 시 대응 테스트 필수: ai-agent/firehub-api → 단위·통합 TC, firehub-web → Playwright E2E.
- 커밋은 이 브랜치(`feat/agent-sdk-oauth-auth`)에서. 각 태스크 끝에 커밋.
- firehub-api↔ai-agent는 body 키 `oauthToken`으로 계약한다. Task 1(ai-agent)과 Task 2(firehub-api 채팅)가 함께 완료되어야 `cli`/`sdk` 종단이 일관된다(중간 커밋 사이 on-branch 일시 불일치 허용).
- 기존 동작 불변 원칙: OAuth 토큰이 **설정된 경우에만** 우선. 미설정 sdk는 기존 API 키 동작 유지.

---

### Task 1: ai-agent — `oauthToken` 리네임 + Agent SDK OAuth 인증 분기

**Files:**
- Modify: `apps/firehub-ai-agent/src/providers/types.ts:48`
- Modify: `apps/firehub-ai-agent/src/agent/agent-sdk.ts:60,101-103,155-160`
- Modify: `apps/firehub-ai-agent/src/providers/provider-factory.ts:11-15`
- Modify: `apps/firehub-ai-agent/src/providers/claude-sdk-chat-provider.ts:8-27`
- Modify: `apps/firehub-ai-agent/src/providers/claude-cli-chat-provider.ts:11,30`
- Modify: `apps/firehub-ai-agent/src/agent/agent-cli.ts:93-94,106,282-283`
- Modify: `apps/firehub-ai-agent/src/routes/chat.ts:34,85`
- Modify: `apps/firehub-ai-agent/src/routes/proactive.ts:34,289`
- Test: `apps/firehub-ai-agent/src/agent/agent-sdk.test.ts` (신규 또는 기존에 추가)
- Test: `apps/firehub-ai-agent/src/providers/provider-factory.test.ts` (신규 또는 기존에 추가)

**Interfaces:**
- Produces (body 계약): ai-agent `/agent/chat`·`/agent/proactive`가 요청 body에서 `oauthToken?: string`을 읽는다(구 `cliOauthToken`).
- Produces: `ProviderConfig { agentType, model?, apiKey?, oauthToken? }`; `AgentOptions.oauthToken?: string`; `ClaudeSdkChatProvider(apiKey: string | undefined, defaultModel: string, oauthToken?: string)`.
- Produces (인증 규칙): sdk 실행 시 `oauthToken` 있으면 `CLAUDE_CODE_OAUTH_TOKEN` 설정 + `ANTHROPIC_API_KEY` 삭제; 없고 `apiKey` 있으면 `ANTHROPIC_API_KEY` 설정; 둘 다 없고 `process.env.ANTHROPIC_API_KEY`도 없으면 error.

- [ ] **Step 1: `agent-sdk.ts` OAuth 분기 실패 테스트 작성**

`apps/firehub-ai-agent/src/agent/agent-sdk.test.ts`에 추가(파일 없으면 생성). `query`를 모킹해 `executeAgent`에 전달된 `options.env`를 캡처한다. `agent-sdk.ts`의 의존(`FireHubApiClient`, MCP 서버 등)은 `vi.mock`으로 무력화하고, 인증 분기까지만 도달하도록 최소 옵션을 넘긴다.

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest';

// query 모킹: 전달된 options 캡처 후 즉시 종료 스트림 반환
const captured: { env?: NodeJS.ProcessEnv } = {};
vi.mock('@anthropic-ai/claude-agent-sdk', () => ({
  query: (opts: { options: { env: NodeJS.ProcessEnv } }) => {
    captured.env = opts.options.env;
    return (async function* () { /* 즉시 종료 */ })();
  },
}));
// 네트워크·MCP·파일 의존 무력화 (권한 조회는 [] 폴백)
vi.mock('../mcp/api-client.js', () => ({
  FireHubApiClient: class { async getSessionPermissions() { return []; } },
}));
vi.mock('../mcp/firehub-mcp-server.js', () => ({ createFireHubMcpServer: () => ({}) }));

import { executeAgent } from './agent-sdk.js';

async function drain(gen: AsyncGenerator<unknown>) {
  const events: unknown[] = [];
  for await (const e of gen) events.push(e);
  return events;
}

describe('executeAgent 인증 분기', () => {
  beforeEach(() => { captured.env = undefined; delete process.env.ANTHROPIC_API_KEY; });

  it('oauthToken이 있으면 CLAUDE_CODE_OAUTH_TOKEN 설정하고 ANTHROPIC_API_KEY 제거', async () => {
    await drain(executeAgent({ message: 'hi', userId: 1, oauthToken: 'oat-1', apiKey: 'sk-should-be-dropped' }));
    expect(captured.env?.CLAUDE_CODE_OAUTH_TOKEN).toBe('oat-1');
    expect(captured.env?.ANTHROPIC_API_KEY).toBeUndefined();
  });

  it('oauthToken 없고 apiKey 있으면 ANTHROPIC_API_KEY 설정', async () => {
    await drain(executeAgent({ message: 'hi', userId: 1, apiKey: 'sk-1' }));
    expect(captured.env?.ANTHROPIC_API_KEY).toBe('sk-1');
    expect(captured.env?.CLAUDE_CODE_OAUTH_TOKEN).toBeUndefined();
  });

  it('토큰·키 모두 없으면 error 이벤트 후 query 미호출', async () => {
    const events = await drain(executeAgent({ message: 'hi', userId: 1 }));
    expect(events.some((e) => (e as { type: string }).type === 'error')).toBe(true);
    expect(captured.env).toBeUndefined();
  });
});
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd apps/firehub-ai-agent && pnpm test -- src/agent/agent-sdk.test.ts`
Expected: FAIL (현재 `AgentOptions`에 `oauthToken` 없음 → 타입 에러 또는 첫 테스트에서 `CLAUDE_CODE_OAUTH_TOKEN` undefined).

- [ ] **Step 3: `AgentOptions` 필드 리네임 + 구조분해 추가 (`agent-sdk.ts`)**

60행 `cliOauthToken?: string;` → `oauthToken?: string;`. 90-103행 구조분해에 `oauthToken` 추가:

```ts
    temperature,
    maxTokens,
    apiKey,
    oauthToken,
    abortSignal,
  } = options;
```

- [ ] **Step 4: 인증 분기 교체 (`agent-sdk.ts:155-160`)**

```ts
  // 인증 우선순위: OAuth 구독 토큰 > 메터드 API 키 > 프로세스 환경 폴백.
  // OAuth 토큰이 있으면 구독 인증을 강제하기 위해 ANTHROPIC_API_KEY를 제거한다
  // (Agent SDK가 CLAUDE_CODE_OAUTH_TOKEN으로 인증 — smart-workplace sdk-runner와 동일).
  if (oauthToken) {
    delete cleanEnv.ANTHROPIC_API_KEY;
    cleanEnv.CLAUDE_CODE_OAUTH_TOKEN = oauthToken;
  } else if (apiKey) {
    cleanEnv.ANTHROPIC_API_KEY = apiKey;
  } else if (!cleanEnv.ANTHROPIC_API_KEY) {
    yield { type: 'error' as const, message: 'No API key or OAuth token provided' };
    return;
  }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd apps/firehub-ai-agent && pnpm test -- src/agent/agent-sdk.test.ts`
Expected: PASS (3 테스트).

- [ ] **Step 6: provider-factory 실패 테스트 작성 (`provider-factory.test.ts`)**

```ts
import { describe, it, expect } from 'vitest';
import { ProviderFactory } from './provider-factory.js';

describe('ProviderFactory sdk 케이스', () => {
  it('apiKey만 있어도 생성된다', () => {
    expect(ProviderFactory.createChatProvider({ agentType: 'sdk', apiKey: 'sk-1' }).name).toBe('claude-sdk');
  });
  it('oauthToken만 있어도 생성된다', () => {
    expect(ProviderFactory.createChatProvider({ agentType: 'sdk', oauthToken: 'oat-1' }).name).toBe('claude-sdk');
  });
  it('apiKey·oauthToken 모두 없으면 throw', () => {
    expect(() => ProviderFactory.createChatProvider({ agentType: 'sdk' })).toThrow();
  });
});
```

- [ ] **Step 7: 테스트 실패 확인**

Run: `cd apps/firehub-ai-agent && pnpm test -- src/providers/provider-factory.test.ts`
Expected: FAIL (`oauthToken`이 `ProviderConfig`에 없음 → 타입 에러; sdk 케이스가 apiKey 없으면 throw).

- [ ] **Step 8: `types.ts` 리네임**

`apps/firehub-ai-agent/src/providers/types.ts:48` `cliOauthToken?: string;` → `oauthToken?: string;`.

- [ ] **Step 9: `provider-factory.ts` sdk 완화 + cli 리네임**

```ts
      case 'sdk':
        // sdk는 API 키 또는 OAuth 토큰 중 하나만 있어도 동작(OAuth 우선).
        if (!config.apiKey && !config.oauthToken)
          throw new Error('API key or OAuth token required for SDK mode');
        return new ClaudeSdkChatProvider(config.apiKey, config.model || DEFAULT_MODEL, config.oauthToken);
      case 'cli':
        return new ClaudeCliChatProvider(true, undefined, config.oauthToken);
```

- [ ] **Step 10: `claude-sdk-chat-provider.ts` 생성자·전달 수정**

```ts
  constructor(
    private readonly apiKey: string | undefined,
    private readonly defaultModel: string,
    private readonly oauthToken?: string,
  ) {}

  async *execute(options: ChatProviderOptions): AsyncGenerator<SSEEvent> {
    const agentOptions: AgentOptions = {
      // ...(기존 필드 유지)...
      apiKey: this.apiKey,
      oauthToken: this.oauthToken,
      abortSignal: options.abortSignal,
    };
    yield* executeAgent(agentOptions);
  }
```

(25행 `apiKey: this.apiKey,` 아래에 `oauthToken: this.oauthToken,` 추가. 생성자 `apiKey` 타입을 `string | undefined`로.)

- [ ] **Step 11: `claude-cli-chat-provider.ts` 리네임**

11행 `private readonly cliOauthToken?: string,` → `private readonly oauthToken?: string,`; 30행 `cliOauthToken: this.cliOauthToken,` → `oauthToken: this.oauthToken,`. (`CliAgentOptions` 필드도 Step 12에서 함께 리네임.)

- [ ] **Step 12: `agent-cli.ts` 리네임**

93-94행 주석/필드 `cliOauthToken` → `oauthToken`; 106행 구조분해 `cliOauthToken` → `oauthToken`; 282-283행 `if (cliOauthToken) { childEnv.CLAUDE_CODE_OAUTH_TOKEN = cliOauthToken; }` → `if (oauthToken) { childEnv.CLAUDE_CODE_OAUTH_TOKEN = oauthToken; }`.

- [ ] **Step 13: `chat.ts`·`proactive.ts` 라우트 리네임**

`chat.ts:34` 구조분해 `cliOauthToken,` → `oauthToken,`; `:85` `cliOauthToken: typeof cliOauthToken === 'string' ? cliOauthToken : undefined,` → `oauthToken: typeof oauthToken === 'string' ? oauthToken : undefined,`.
`proactive.ts:34` 인터페이스 `cliOauthToken?: string;` → `oauthToken?: string;`; `:289` `cliOauthToken: body.cliOauthToken || undefined,` → `oauthToken: body.oauthToken || undefined,`.

- [ ] **Step 14: 타입체크 + 전체 테스트**

Run: `cd apps/firehub-ai-agent && pnpm typecheck && pnpm test`
Expected: PASS, `cliOauthToken` 잔존 참조 없음.

- [ ] **Step 15: 잔존 참조 확인 + 커밋**

Run: `rg -n "cliOauthToken" apps/firehub-ai-agent/src` → 결과 없음 확인.

```bash
git add apps/firehub-ai-agent/src
git commit -m "feat(ai-agent): sdk 모드 OAuth 인증 분기 + cliOauthToken→oauthToken 리네임"
```

---

### Task 2: firehub-api — 채팅 프록시 sdk OAuth 주입 + body 키 리네임

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/ai/service/AiAgentProxyService.java:147-190`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/ai/service/AiAgentProxyServiceTest.java`

**Interfaces:**
- Consumes: ai-agent가 body `oauthToken`을 읽음(Task 1).
- Produces: 채팅 프록시가 `agentType∈{cli,sdk}`이고 OAuth 토큰이 있으면 body에 `oauthToken`을 넣는다. sdk `missingCredential`은 apiKey·oauthToken 모두 없을 때만 true.

- [ ] **Step 1: 실패 테스트 작성 (`AiAgentProxyServiceTest.java`)**

sdk + OAuth 토큰 설정 시 요청 body에 `oauthToken`이 포함되는지 검증하는 테스트를 추가한다. ai-agent HTTP 호출은 WireMock으로 스텁하고, 전송된 요청 body를 캡처한다(`settingsService.getDecryptedCliOauthToken()`, `getDecryptedApiKey()`는 `@MockitoBean`으로 스텁). 기존 파일의 WireMock/스텁 셋업 패턴을 따른다.

```java
@Test
void streamChat_sdkWithOauthToken_injectsOauthTokenIntoBody() {
  // given: agent_type=sdk, OAuth 토큰 설정, API 키는 없음
  when(settingsService.getAsMap("ai")).thenReturn(Map.of("ai.agent_type", "sdk", "ai.model", "claude-sonnet-5"));
  when(settingsService.getDecryptedCliOauthToken()).thenReturn(Optional.of("oat-test"));
  when(settingsService.getDecryptedApiKey()).thenReturn(Optional.empty());
  stubFor(post(urlEqualTo("/agent/chat")).willReturn(aResponse().withStatus(200)
      .withHeader("Content-Type", "text/event-stream").withBody("event: done\ndata: {}\n\n")));

  // when
  SseEmitter emitter = new SseEmitter();
  proxyService.streamChat(emitter, "hi", null, List.of(), 1L, null, null);
  // (동기 완료 대기 — 기존 테스트의 대기 헬퍼 재사용)

  // then: ai-agent로 전송된 body에 oauthToken 포함, cliOauthToken 키는 없음
  verify(postRequestedFor(urlEqualTo("/agent/chat"))
      .withRequestBody(matchingJsonPath("$.oauthToken", equalTo("oat-test")))
      .withRequestBody(matchingJsonPath("$.agentType", equalTo("sdk"))));
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "*.AiAgentProxyServiceTest.streamChat_sdkWithOauthToken_injectsOauthTokenIntoBody"` (DB 실행 필요: `pnpm db:up`)
Expected: FAIL (현재 sdk는 토큰 미조회·미주입).

- [ ] **Step 3: 토큰 조회 조건 확장 (`AiAgentProxyService.java:151-152`)**

```java
    Optional<String> cliTokenOpt =
        ("cli".equals(agentType) || "sdk".equals(agentType))
            ? settingsService.getDecryptedCliOauthToken()
            : Optional.empty();
```

- [ ] **Step 4: 인증 누락 검사 수정 (`:153-160`)**

```java
    boolean missingCredential;
    if ("opencode".equals(agentType)) {
      missingCredential = false; // OpenCode 는 배포 환경 opencode auth 에 의존(옵션 3)
    } else if ("cli".equals(agentType)) {
      missingCredential = cliTokenOpt.isEmpty() || cliTokenOpt.get().isBlank();
    } else if ("sdk".equals(agentType)) {
      // sdk 는 API 키 또는 OAuth 토큰 중 하나만 있어도 인증 가능(OAuth 우선).
      boolean hasToken = cliTokenOpt.isPresent() && !cliTokenOpt.get().isBlank();
      missingCredential = apiKeyOpt.isEmpty() && !hasToken;
    } else { // cli-api
      missingCredential = apiKeyOpt.isEmpty();
    }
```

- [ ] **Step 5: 오류 메시지 sdk 대응 (`:163-166`)**

sdk에서 둘 다 없을 때 안내 문구를 추가한다. 기존 삼항을 확장:

```java
        String errorMessage;
        if ("cli".equals(agentType)) {
          errorMessage = "Claude CLI OAuth 토큰이 설정되지 않았습니다. 관리자 설정에서 토큰을 등록하세요.";
        } else if ("sdk".equals(agentType)) {
          errorMessage = "AI API 키 또는 OAuth 토큰이 설정되지 않았습니다. 관리자 설정에서 등록하세요.";
        } else {
          errorMessage = "AI API 키가 설정되지 않았습니다. 관리자 설정에서 API 키를 등록하세요.";
        }
```

- [ ] **Step 6: body 주입 수정 (`:187-190`)**

```java
    requestBody.put("agentType", agentType);
    // cli 또는 sdk 에서 OAuth 토큰이 있으면 body 에 주입(중립 키 oauthToken).
    // sdk 에서 apiKey 와 함께 있으면 ai-agent 가 OAuth 를 우선 선택한다.
    if ("cli".equals(agentType) || "sdk".equals(agentType)) {
      cliTokenOpt.ifPresent(token -> requestBody.put("oauthToken", token));
    }
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "*.AiAgentProxyServiceTest"`
Expected: PASS.

- [ ] **Step 8: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/ai/service/AiAgentProxyService.java apps/firehub-api/src/test/java/com/smartfirehub/ai/service/AiAgentProxyServiceTest.java
git commit -m "feat(api): 채팅 프록시 sdk 모드 OAuth 토큰 주입 + body 키 oauthToken 리네임"
```

---

### Task 3: firehub-api — `/auth-status` sdk+OAuth 검증 분기

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/ai/controller/AiController.java:88-99`
- Test: 기존 컨트롤러/프록시 테스트에 케이스 추가 (`AiAgentProxyServiceTest` 또는 컨트롤러 테스트)

**Interfaces:**
- Consumes: `settingsService.getDecryptedCliOauthToken()`, `aiAgentProxyService.verifyCliToken()` / `verifyApiKey()`(기존).
- Produces: sdk이고 OAuth 토큰이 설정돼 있으면 `verifyCliToken()`, 그 외 `verifyApiKey()`.

- [ ] **Step 1: 실패 테스트 작성**

sdk + OAuth 토큰 설정 시 `/auth-status`가 토큰 검증 경로(`verifyCliToken`)를 타는지 검증. `aiAgentProxyService`를 `@MockitoBean`으로 스텁하고 어느 메서드가 호출되는지 `verify`.

```java
@Test
void getAuthStatus_sdkWithOauthToken_usesTokenVerification() {
  when(settingsService.getAsMap("ai")).thenReturn(Map.of("ai.agent_type", "sdk"));
  when(settingsService.getDecryptedCliOauthToken()).thenReturn(Optional.of("oat-test"));
  when(aiAgentProxyService.verifyCliToken()).thenReturn("{\"valid\":true}");
  // when: getAuthStatus 호출 (컨트롤러 or MockMvc)
  // then
  verify(aiAgentProxyService).verifyCliToken();
  verify(aiAgentProxyService, never()).verifyApiKey();
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "*getAuthStatus_sdkWithOauthToken*"`
Expected: FAIL (현재 sdk는 `verifyApiKey`).

- [ ] **Step 3: 분기 수정 (`AiController.java:92-98`)**

```java
    String agentType = aiSettings.getOrDefault("ai.agent_type", "sdk");
    // cli, 또는 (sdk 이고 OAuth 토큰이 설정된 경우) 토큰 검증. 그 외 API 키 검증.
    boolean useTokenVerification =
        "cli".equals(agentType)
            || ("sdk".equals(agentType)
                && settingsService.getDecryptedCliOauthToken().filter(t -> !t.isBlank()).isPresent());
    String result = useTokenVerification
        ? aiAgentProxyService.verifyCliToken()
        : aiAgentProxyService.verifyApiKey();
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "*getAuthStatus*"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/ai/controller/AiController.java apps/firehub-api/src/test
git commit -m "feat(api): /auth-status sdk+OAuth 토큰 검증 분기"
```

---

### Task 4: firehub-api — 프로액티브 경로 sdk OAuth 주입 + body 키 리네임

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobAsyncRunner.java:119-135`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveAiClient.java:43-61`
- Test: `ProactiveAiClient`/`ProactiveJobAsyncRunner` 대응 테스트(신규 또는 기존)

**Interfaces:**
- Consumes: ai-agent `/agent/proactive`가 body `oauthToken`을 읽음(Task 1).
- Produces: `ProactiveAiClient.execute(...)`가 body에 `oauthToken`(구 `cliOauthToken`)을 넣는다; 러너가 `agentType∈{cli,sdk}`에서 토큰을 조회해 전달.

- [ ] **Step 1: `ProactiveAiClient` body 키 실패 테스트 작성**

`execute(...)` 호출 시 ai-agent로 보내는 body에 `oauthToken`이 들어가는지 WireMock으로 검증(토큰 non-null 전달 시).

```java
@Test
void execute_withOauthToken_putsOauthTokenInBody() {
  stubFor(post(urlEqualTo("/agent/proactive")).willReturn(okJson("{\"sections\":[]}")));
  client.execute(1L, "prompt", "{}", "sk-x", "sdk", "oat-test", null, Map.of());
  verify(postRequestedFor(urlEqualTo("/agent/proactive"))
      .withRequestBody(matchingJsonPath("$.oauthToken", equalTo("oat-test"))));
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "*ProactiveAiClient*execute_withOauthToken*"`
Expected: FAIL (현재 body 키가 `cliOauthToken`).

- [ ] **Step 3: `ProactiveAiClient.java` body 키 리네임 (`:59-60`)**

파라미터명 `cliOauthToken`(49행)은 유지하거나 `oauthToken`으로 정리(선택). body 주입 키만 변경:

```java
      if (oauthToken != null) {
        body.put("oauthToken", oauthToken);
      }
```

(49행 파라미터도 `String oauthToken,`으로 리네임하고 60행을 위와 같이. 호출부 Step 5에서 맞춤.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "*ProactiveAiClient*"`
Expected: PASS.

- [ ] **Step 5: 러너 토큰 조회 sdk 확장 (`ProactiveJobAsyncRunner.java:120-123`)**

```java
      String agentType = aiSettings.getOrDefault("ai.agent_type", "sdk");
      String oauthToken = null;
      // cli 또는 sdk 에서 구독 OAuth 토큰을 조회해 전달(sdk 는 OAuth 우선).
      if ("cli".equals(agentType) || "sdk".equals(agentType)) {
        oauthToken = settingsService.getDecryptedCliOauthToken().orElse(null);
      }
```

`aiClient.execute(...)`의 `cliOauthToken` 인자 자리(133행)에 `oauthToken` 전달.

- [ ] **Step 6: 러너 테스트 통과 확인 + 전체 프로액티브 테스트**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.proactive.*"`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive apps/firehub-api/src/test
git commit -m "feat(api): 프로액티브 경로 sdk OAuth 주입 + body 키 oauthToken 리네임"
```

---

### Task 5: firehub-web — 관리자 설정 sdk OAuth 필드 노출 + E2E

**Files:**
- Modify: `apps/firehub-web/src/pages/admin/SettingsPage.tsx:294-378`
- Modify/Create: `apps/firehub-web/e2e/pages/admin/*.spec.ts` (설정 페이지 spec — 기존 `embedding-settings.spec.ts` 또는 신규 `ai-settings.spec.ts`)

**Interfaces:**
- Consumes: 저장 로직(165-168행)은 `ai.cli_oauth_token`을 그대로 저장(변경 없음). firehub-api가 이 키를 읽어 sdk에도 주입(Task 2).
- Produces: `agent_type === 'sdk'`일 때 OAuth 토큰 필드 + API 키 필드가 모두 렌더된다.

- [ ] **Step 1: E2E 실패 테스트 작성**

agent_type을 `sdk`로 선택했을 때 OAuth 토큰 입력(`#ai-cli-oauth-token`)과 API 키 입력(`#ai-api-key`)이 **둘 다** 보이는지, 토큰 저장 시 payload에 `ai.cli_oauth_token`이 담기는지 검증. `setupAdminAuth(page)` + settings API 모킹(fixture) 사용.

```ts
test('agent_type=sdk 선택 시 OAuth 토큰과 API 키 필드가 모두 노출되고 저장된다', { tag: '@smoke' }, async ({ page }) => {
  await setupAdminAuth(page);
  let savedPayload: any;
  await page.route('**/api/v1/settings**', (route) => {
    if (route.request().method() === 'PUT' || route.request().method() === 'POST') {
      savedPayload = route.request().postDataJSON();
      return route.fulfill({ status: 200, body: JSON.stringify({}) });
    }
    return route.fulfill({ status: 200, body: JSON.stringify(mockAiSettings) });
  });
  await page.goto('/admin/settings');
  await selectAgentType(page, 'sdk'); // Select 컴포넌트에서 'sdk' 선택 헬퍼
  await expect(page.locator('#ai-cli-oauth-token')).toBeVisible();
  await expect(page.locator('#ai-api-key')).toBeVisible();
  await page.locator('#ai-cli-oauth-token').fill('sk-ant-oat01-xyz');
  await page.getByRole('button', { name: '저장' }).click();
  expect(savedPayload.settings['ai.cli_oauth_token']).toBe('sk-ant-oat01-xyz');
});
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd apps/firehub-web && pnpm exec playwright test --project=chromium -g "agent_type=sdk 선택 시 OAuth"`
Expected: FAIL (현재 sdk는 API 키 필드만 노출, OAuth 필드 숨김).

- [ ] **Step 3: 조건부 렌더 재구성 (`SettingsPage.tsx:294-378`)**

현재 `opencode / cli / else(API키)` 3분기를, "opencode 안내 박스 + (cli|sdk → OAuth 필드) + (cli-api|sdk → API 키 필드)"로 바꾼다. 기존 OAuth 필드 JSX(300-344행)와 API 키 필드 JSX(346-378행 내부)는 **내용 변경 없이** 조건만 조정한다:

```tsx
{form['ai.agent_type'] === 'opencode' ? (
  <div className="rounded-md border border-border bg-muted/40 px-4 py-3 text-sm text-muted-foreground">
    배포 환경에 구성된 OpenCode 인증(opencode auth)을 사용합니다. 별도 키 입력이 필요 없습니다.
  </div>
) : (
  <div className="space-y-4">
    {/* cli 또는 sdk: OAuth 토큰 필드 (sdk는 OAuth 우선) */}
    {(form['ai.agent_type'] === 'cli' || form['ai.agent_type'] === 'sdk') && (
      /* 기존 OAuth 토큰 필드 블록 (Label + Input#ai-cli-oauth-token + 인증확인 버튼 + 설명) 그대로 */
    )}
    {/* cli-api 또는 sdk: API 키 필드 */}
    {(form['ai.agent_type'] === 'cli-api' || form['ai.agent_type'] === 'sdk') && (
      /* 기존 API 키 필드 블록 (Label + Input#ai-api-key + 인증확인 버튼) 그대로 */
    )}
  </div>
)}
```

주의: 기존 두 필드 블록의 내부 JSX(Input·토글 버튼·인증확인 버튼·설명 문단)는 그대로 옮긴다. `verifyAuth`/`hasChanges` 등 기존 핸들러 참조 유지.

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd apps/firehub-web && pnpm exec playwright test --project=chromium -g "agent_type=sdk 선택 시 OAuth"`
Expected: PASS.

- [ ] **Step 5: 타입체크 + 관련 E2E 회귀**

Run: `cd apps/firehub-web && pnpm typecheck && pnpm exec playwright test --project=chromium e2e/pages/admin`
Expected: PASS (기존 cli/opencode/api 케이스 회귀 없음).

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-web/src/pages/admin/SettingsPage.tsx apps/firehub-web/e2e
git commit -m "feat(web): 관리자 설정 sdk 모드 OAuth 토큰 필드 노출 + E2E"
```

---

### Task 6: 종단 검증

**Files:** (변경 없음 — 통합 확인만)

- [ ] **Step 1: 모노레포 타입체크·테스트**

Run: `pnpm typecheck && pnpm test` (루트) — ai-agent/web 단위·타입.
Run: `cd apps/firehub-api && ./gradlew test` (DB 필요).
Expected: 전부 PASS.

- [ ] **Step 2: 잔존 `cliOauthToken` body-키 참조 점검**

Run: `rg -n "cliOauthToken|\"cliOauthToken\"" apps/firehub-api/src/main apps/firehub-ai-agent/src`
Expected: body 주입/구조분해에 남은 `cliOauthToken` 없음(파라미터명 등 내부 변수는 무해하나 가급적 정리). DB 키 `ai.cli_oauth_token`·`getDecryptedCliOauthToken`은 그대로 유지됨을 확인.

- [ ] **Step 3: (선택) 수동 종단 스모크**

`/verify` 스킬 또는 수동으로: 관리자 설정 agent_type=sdk + OAuth 토큰 저장 → 인증 확인(✓) → 채팅 1회 정상 응답. (로컬 서버: `pnpm dev:full`.)

- [ ] **Step 4: 최종 상태 확인**

Run: `git log --oneline feat/agent-sdk-oauth-auth ~7`
Expected: spec 커밋 + Task 1~5 커밋 존재.
