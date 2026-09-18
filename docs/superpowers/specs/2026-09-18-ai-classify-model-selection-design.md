# AI_CLASSIFY 분류 전용 모델 선택 설계

- 작성일: 2026-09-18
- 대상 앱: `apps/firehub-api` (설정·파이프라인 실행), `apps/firehub-ai-agent` (분류 실행), `apps/firehub-web` (관리자 설정)
- 관련: [2026-06-23 OpenCode Provider 설계](2026-06-23-opencode-provider-design.md), [2026-07-08 Agent SDK OAuth 인증 설계](2026-07-08-agent-sdk-oauth-auth-design.md)

## 배경

파이프라인의 `AI_CLASSIFY` 스텝은 행 배치를 LLM에 넘겨 분류 결과 컬럼을 채운다. 분류는 짧은 프롬프트 + 구조화된 JSON 출력이라 채팅보다 훨씬 단순한 작업인데, **현재는 채팅과 같은 모델(기본 `claude-sonnet-5`)을 강제로 쓴다.** 분류를 더 저렴한 모델(예: `claude-haiku-4-5`)로 내리면 비용이 크게 줄지만, 지금은 모델을 낮추는 유일한 방법이 `ai.model`을 바꾸는 것이고 그러면 채팅까지 같이 내려간다.

### 현재 모델 결정 경로

```
AiClassifyExecutor
  └ AiAgentClient.classify()            ← 모델이 여기서 고정된다
       body.model = settingsService.getValue("ai.model").orElse("claude-sonnet-5")   // AiAgentClient.java:81
    → POST /agent/classify              (routes/classify.ts — model 은 이미 optional 필드)
       → ClaudeClassifyProvider          (claude-classify-provider.ts)
          → classifyBatch(..., model)    (classification-service.ts:129)
             → ProviderFactory.createCompletionProvider({ apiKey, oauthToken, model })
                → ClaudeSdkCompletionProvider → query({ options: { model } })
```

ai-agent 쪽 배선은 이미 임의 모델 문자열을 받아 그대로 Agent SDK에 넘긴다. **모델을 채팅에 묶는 곳은 `AiAgentClient.java:81` 한 줄뿐이다.**

### agentType과 completion 슬롯

`ProviderFactory`는 세 종류의 provider를 만드는데, `agentType` 분기는 채팅에만 있다.

| 팩토리 | agentType 분기 | 구현체 |
|---|---|---|
| `createChatProvider` | `sdk` / `cli` / `cli-api` / `opencode` | 4종 |
| `createClassifyProvider` | 없음 | `ClaudeClassifyProvider` 고정 |
| `createCompletionProvider` | 없음 | `ClaudeSdkCompletionProvider` 고정 |

`createCompletionProvider`가 단일 경로인 것은 의도된 설계였다(`provider-factory.ts:42-47` 주석: *"내부 호출은 사용자가 고른 에이전트 종류와 무관하게 SDK 경로 하나로 통일"*). 그 결과 **`ai.agent_type=opencode` 배포에서도 AI_CLASSIFY와 GraphRAG는 Claude SDK로 나간다.**

이것이 기존 결함 하나를 만든다. `AiAgentProxyService.java:220-229`는 opencode일 때 `apiKey`/`oauthToken`을 **일부러 주입하지 않는다**(옵션 3: 모델 인증은 배포 측 `opencode auth`에 의존). 즉 opencode 배포는 `ai.api_key`·`ai.cli_oauth_token`이 둘 다 비어 있을 수 있는데, `AiAgentClient.classify()`는 조건 없이 그 두 설정을 읽어 넣으므로 비어 있으면 아무 자격증명도 안 보낸다. `buildCompletionEnv`가 프로세스 환경/로컬 키체인으로 폴백하지만 컨테이너에는 대개 아무것도 없다. `AiClassifyExecutor`에 사전 검사도 없어, 실패는 파이프라인과 무관해 보이는 SDK 인증 오류로만 드러난다.

### 캐시 키

`AiClassifyExecutor.rowContentHash()`(509-540행)는 캐시 키를 **행 내용 + 프롬프트 해시**로만 만든다. 모델이 빠져 있다. 모델을 바꿀 수 있게 만드는 순간, 이전 모델로 캐시된 행이 그대로 히트해 한 데이터셋에 두 모델의 결과가 섞인다.

## 목표

1. 분류가 채팅과 **다른 모델**을 쓸 수 있게 한다.
2. `ai.agent_type=opencode` 배포에서도 분류가 **그 배포의 provider(Bedrock 등)를 경유**하게 한다. 즉 completion 슬롯이 agentType을 따르게 한다.
3. 모델이 바뀌면 추론 캐시가 갈리게 한다.
4. 미설정 배포의 동작은 **바뀌지 않는다**.

## 비목표

- 스텝별(파이프라인 스텝 단위) 모델 지정 — 하지 않는다. 전역 설정 하나로 시작한다.
- 모델 문자열 검증·정규화 — 하지 않는다. 값은 원본 그대로 통과시킨다(아래 결정 B).
- opencode 전역 설정 변경을 감지한 캐시 무효화 — 불가능하다(아래 한계).

### 옵션 3 방침의 부분 수정

[2026-06-23 OpenCode Provider 설계](2026-06-23-opencode-provider-design.md)는 *"모델/provider는 배포 측 OpenCode 설정에서 고정"*을 비목표로 명시했다. 이 설계는 그 방침을 **분류(및 GraphRAG) 경로에 한해** 뒤집는다 — `--model`을 조건부로 전달한다. 채팅 경로는 그대로 전역 상속을 유지한다. 비용 절감이라는 목적이 분류에만 해당하고, 채팅 모델을 UI에서 고르는 기능은 여전히 만들지 않기 때문이다.

## 아키텍처 결정

### 결정 A: completion 슬롯을 agentType으로 분기한다

`createCompletionProvider`에 `agentType`을 받아 분기한다.

| agentType | CompletionProvider |
|---|---|
| `sdk` / `cli` / `cli-api` | `ClaudeSdkCompletionProvider` (현행) |
| `opencode` | `OpenCodeCompletionProvider` (신규) |

`cli`/`cli-api`가 SDK 구현으로 가는 것은 현행 동작 유지다 — 이 슬롯은 원래 단발 호출용이고 CLI 서브프로세스를 따로 띄울 이유가 없다. opencode만 다른 이유는 **인증과 모델 접근 경로 자체가 다르기** 때문이다(배포 측 `opencode auth`, Bedrock 등).

소비자는 두 곳이며 **둘 다 같은 규칙을 받는다**:
- `classification-service.ts:129` — AI_CLASSIFY
- `graphrag/llm-completer.ts:30` — GraphRAG 추출·온톨로지 추론

GraphRAG도 opencode 배포에서는 opencode를 경유하게 된다. 이는 인증 경로가 갈라지지 않는다는 점에서 옳은 방향이지만, GraphRAG 프롬프트는 Claude 기준으로 튜닝돼 있어 추출 품질 재확인이 필요하다(→ 검증 항목).

`provider-factory.ts:42-47`의 "SDK 단일 경로" 주석은 더 이상 사실이 아니므로 갱신한다.

### 결정 B: `ai.classify_model` 설정 키 하나, 값은 원본 통과

새 설정 키 `ai.classify_model` 하나를 둔다. 값은 **해석·변환 없이 그대로** 하위로 전달한다.

- `agent_type`이 `sdk`/`cli`/`cli-api`면 Claude 네임스페이스 값을 넣는다 — 예: `claude-haiku-4-5`
- `agent_type`이 `opencode`면 OpenCode 네임스페이스 값을 넣는다 — 예: `anthropic/claude-haiku-4-5`, `amazon-bedrock/...`

키를 두 개 두지 않는 이유: 코드가 배포 측 provider 이름을 알 필요가 없고, 매핑 테이블을 유지할 필요도 없다. 대가는 `agent_type`을 바꾸면 이 값이 무효해진다는 것이고, 이는 설정 화면 안내로 처리한다(→ 구성요소 4).

**폴백은 `ai.model`이 아니라 "미지정"이다.** `ai.model`은 Claude 네임스페이스 값이라 opencode에 넘기면 틀린다. `ai.classify_model`이 비어 있으면 모델 필드를 아예 전달하지 않고, 하위가 각자 현행 기본값을 쓴다.

| `ai.classify_model` | sdk 경로 | opencode 경로 |
|---|---|---|
| 비어 있음 (기본) | `DEFAULT_MODEL`(`claude-sonnet-5`) | 배포 전역 설정 상속 (`--model` 미전달) |
| 값 있음 | 그 값을 `query({model})`에 전달 | 그 값을 `--model`로 전달 |

따라서 **미설정 배포의 동작은 완전히 현행과 같다.**

### 결정 C: 캐시 키에 `ai.classify_model` 설정값과 `agentType`을 넣는다

캐시는 LLM 호출 *전에* 조회하므로 응답의 실효 모델을 키에 쓸 수 없다. 키에 넣을 수 있는 것은 요청 시점에 아는 값뿐이다.

```
rowContentHash(row, promptHash, modelKey)
  modelKey = agentType + '' + (ai.classify_model ?? "")
```

**한계(명시적으로 감수한다):** opencode에서 `ai.classify_model`이 비어 있으면 실효 모델은 배포 측 전역 설정이 정하는데, 그 값이 바뀌어도 캐시 키는 동일하다. 즉 배포 측 모델 교체 후 낡은 캐시가 살아남는다. 이 경우는 캐시 수동 무효화로 대응한다. `ai.classify_model`을 명시한 배포에는 해당하지 않는다.

## 구성요소

### 1. `OpenCodeCompletionProvider` (신규)

`apps/firehub-ai-agent/src/providers/opencode-completion-provider.ts`

`CompletionProvider` 인터페이스를 구현한다 — `complete(systemPrompt, userText, options)`.

채팅 경로(`executeOpenCodeAgent`)와 공유하는 것: `opencode run --format json` spawn, `parseOpenCodeEvent` 파싱, 격리 워크스페이스 + `--dir`.

채팅 경로와 **다른** 것:

- **도구 미부착.** `buildOpenCodeConfig`에서 `mcp` 블록을 제외한 completion 전용 변형을 쓴다(`tools` 전부 false, `agent.build.permission.task` deny, `agent.general.disable` 유지). SDK 쪽이 `mcpServers` 미전달 + `allowedTools: []`을 쓰는 것과 같은 이유다 — 이 completer는 firehub MCP 도구 *안에서* 호출되므로 도구를 붙이면 재귀한다. `buildOpenCodeConfig`를 `{ withMcp: boolean }` 옵션으로 분기하거나 공통 베이스를 추출한다.
- **`--model` 조건부 전달.** `options.model`이 있으면 `buildOpenCodeRunArgs`에 `--model <값>`을 붙인다. 없으면 현행대로 미전달.
- **텍스트 수집.** 스트리밍이 아니라 `parseOpenCodeEvent`가 내는 `text` 이벤트를 누적해 한 문자열로 반환한다. `step_finish`(`reason === 'stop'`)의 `tokens`를 `CompletionResult.usage`에 채운다. `error` 이벤트는 `Error`로 던진다.
- **시스템 프롬프트.** OpenCode는 프로젝트 디렉토리의 `AGENTS.md`를 시스템 지시로 읽는다. `systemPrompt`를 그 파일로 쓴다. `systemPromptMode`는 opencode에 claude_code 프리셋 개념이 없으므로 `replace`만 지원하고, `append-to-preset`이 와도 동일하게 취급한다(GraphRAG가 이 모드를 쓰므로 동작 차이를 주석으로 남긴다).
- **타임아웃·중단.** `options.timeoutMs`와 `abortSignal`을 자식 프로세스 kill로 매핑한다. `maxOutputTokens`는 opencode에 대응 수단이 없으므로 무시하고, 무시한다는 사실을 주석에 남긴다.

### 2. `ProviderFactory.createCompletionProvider`

`agentType`을 받는 시그니처로 바꾼다. 소비자 2곳(`classification-service.ts`, `graphrag/llm-completer.ts`)도 함께 업데이트한다. `agentType` 미지정 시 `sdk`로 간주해 단독 스크립트(`graphrag/dump-extraction.ts`, `graphrag/eval/run-eval.ts`) 경로를 깨지 않는다.

### 3. `agentType`·`model`을 분류 요청까지 전달

- **`AiAgentClient.classify()`** (`AiAgentClient.java:81` 일대):
  - `ai.agent_type`을 읽어 `body.agentType`에 싣는다(기본 `sdk`).
  - `ai.classify_model`이 비어 있지 않을 때만 `body.model`에 싣는다. **`ai.model` 폴백을 제거한다.**
  - 자격증명은 채팅과 같은 규칙으로 맞춘다 — `agentType`이 `opencode`면 `apiKey`/`oauthToken`을 주입하지 않는다.

    > **선행 조건(2026-09-18 추가).** 이 규칙은 **결정 A(`OpenCodeCompletionProvider` 신설)가 먼저
    > 구현된 뒤에만** 옳다. 그 전까지 분류는 `agentType`과 무관하게 Claude SDK 로 나가므로
    > (`provider-factory.ts`의 `createCompletionProvider`에 agentType 분기가 없다), 키를 withhold 하면
    > ai-agent 컨테이너의 ambient 자격증명으로 떨어져 과금이 섞인다. 실제로 테넌트 오버라이드 밴드에서
    > 이 가드가 선행 없이 들어갔다가 Major 로 잡혀 `6b1c6383`에서 제거됐다.
    >
    > **따라서 이 스펙을 구현하는 사람은** 결정 A와 이 줄을 **같은 커밋에서** 처리하고,
    > `AiAgentClientTest.classify_opencode_stillSendsCredentialsWhenPresent`(현재 "보낸다"를 고정하는
    > 테스트)를 그때 함께 뒤집어야 한다. 순서를 어기면 그 테스트가 먼저 붉어진다.
  - `opencode`가 아니면서 두 자격증명이 모두 비어 있으면 **호출 전에** 명확한 오류로 실패한다. 현행은 SDK 스트림 깊은 곳에서야 인증 오류가 나서 원인이 파이프라인과 무관해 보인다.
- **`routes/classify.ts`**: zod 스키마에 `agentType: z.enum(['sdk','cli','cli-api','opencode']).optional()` 추가.
- **`ClaudeClassifyProvider`**: `agentType`을 받아 팩토리에 넘긴다. `ClassifyProviderOptions`에 `agentType` 추가. 구현체 이름이 더 이상 Claude 전용이 아니므로 `ClassifyProvider` 구현 클래스명을 중립적으로 바꾼다(`DefaultClassifyProvider`).
- **`classification-service.ts`**: `classifyBatch` 시그니처에 `agentType`을 추가하고, `model`을 optional(`string | undefined`)로 바꾼다.
- **`claude-classify-provider.ts`의 `options.model || DEFAULT_MODEL`을 제거한다** — 미지정을 그대로 아래로 흘려야 결정 B의 "미지정" 의미가 유지된다. `DEFAULT_MODEL` 적용은 `ClaudeSdkCompletionProvider` 내부에서만 일어난다.

### 4. `ai.classify_model` 설정 키

- **마이그레이션**: `system_settings`에 `('ai.classify_model', '', '분류 전용 모델 (비우면 기본값)')` seed. 기존 키가 snake_case(`ai.system_prompt`, `ai.max_turns`, `ai.agent_type`)이므로 그 규칙을 따른다.
- **`SettingsService.java:39` 허용 키 목록**에 추가. 값 검증은 `ai.model`과 동일하게 free-form 통과(`SettingsService.java:824` 주변). 단 `ai.model`과 달리 **빈 문자열이 유효**하다.
- **`SettingsOverridePolicy.java:34`**: 테넌트 오버라이드 허용. `ai.model`이 이미 오버라이드 가능하므로 같은 취급이 일관된다.
- **웹**: `settings-fields.ts:32` 목록 추가, `SettingsPage.tsx`의 폼 타입·초기값 추가. 입력은 `agent_type`에 따라 다르게 안내한다 — `opencode`가 아니면 `MODEL_OPTIONS` 드롭다운(`claude-haiku-4-5` 이미 포함) + "비우면 기본값", `opencode`면 자유 입력 + `provider/model` 형식 안내. `agent_type`을 바꾸면 이 값이 무효해질 수 있다는 경고를 함께 노출한다.

### 5. 캐시 키

`AiClassifyExecutor.rowContentHash(row, promptHash)` → `rowContentHash(row, promptHash, modelKey)`. `modelKey`는 결정 C의 형태. 호출부(538행 부근)와 `static` 테스트 진입점을 함께 고친다. 기존의 긴 설명 주석(509-536행)은 **왜 id를 빼는가**를 담고 있으므로 보존하고, 모델 키 추가 이유를 문단으로 덧붙인다.

## 데이터 흐름 (변경 후)

```
AiClassifyExecutor
  └ AiAgentClient.classify()
       body.agentType = ai.agent_type (기본 sdk)
       body.model     = ai.classify_model  (비어 있으면 미전달)
       body.apiKey/oauthToken = opencode 면 미주입, 아니면 주입(둘 다 없으면 즉시 실패)
    → POST /agent/classify
       → DefaultClassifyProvider
          → classifyBatch(..., agentType, model?)
             → ProviderFactory.createCompletionProvider({ agentType, model, apiKey, oauthToken })
                ├ sdk|cli|cli-api → ClaudeSdkCompletionProvider → query({ model ?? DEFAULT_MODEL })
                └ opencode        → OpenCodeCompletionProvider  → opencode run [--model <값>]
```

## 오류 처리

| 상황 | 동작 |
|---|---|
| `agentType != opencode`이고 자격증명 둘 다 없음 | `AiAgentClient.classify()`가 호출 전 실패. 메시지에 "관리자 설정에서 AI API 키 또는 OAuth 토큰을 등록하세요" 포함 |
| opencode 배포 측 인증 미구성 | `opencode run`이 오류 이벤트 반환 → `parseOpenCodeEvent`의 `error` → provider가 `Error` 던짐 → 기존 배치 실패 경로(배치 크기·상한을 붙이는 메시지)로 합류 |
| `ai.classify_model` 값이 해당 provider에 없는 모델 | 하위(SDK/opencode)가 오류를 낸다. 코드는 검증하지 않는다(비목표) |
| opencode completion 타임아웃 | 자식 프로세스 kill + `classifyTimeoutMs` 기반 메시지 — SDK 경로와 동일 계약 |

## 테스트

- **`AiAgentClientTest`** (Java): `agentType` 주입, `ai.classify_model` 비어 있을 때 `model` 키 미포함, opencode일 때 자격증명 미주입, 비-opencode에서 자격증명 부재 시 사전 실패.
- **`AiClassifyExecutorTest`** (Java): 동일 행·프롬프트라도 `modelKey`가 다르면 해시가 다르고, 같으면 같다. 기존 "id만 다르면 같은 해시" 성질 테스트는 유지.
- **`SettingsOverridePolicyTest` / `SettingsResolutionTest`** (Java): 키 집합을 열거하는 단언이 있으므로 새 키 반영.
- **`provider-factory.test.ts`**: agentType별 구현체 선택, 미지정 시 sdk 폴백.
- **`opencode-completion-provider.test.ts`** (신규): `--model` 조건부 포함 여부, MCP 블록 미포함 config, text 이벤트 누적, error 이벤트 → throw, 타임아웃 시 kill.
- **`classify.test.ts`**: zod 스키마가 `agentType`을 받고 잘못된 값을 거부.
- **E2E (Playwright)**: 관리자 설정에서 `ai.classify_model` 저장·조회, `agent_type`별 입력 UI 전환.

## 검증 항목 (구현 후 실측)

코드만으로 확정할 수 없어 실제 실행으로 확인해야 하는 것들:

1. **OAuth 구독 인증에서 haiku가 서빙되는가.** prod는 `ai.cli_oauth_token`만 설정돼 있다. Agent SDK가 `model`을 CLI로 그대로 넘기므로 될 가능성이 높지만 소스로는 확정 불가. 분류 1회 실행으로 확인한다.
2. **opencode `--model` 인자가 실제로 먹는가.** `buildOpenCodeRunArgs`는 `--model`을 한 번도 보낸 적이 없다. 배포 측 provider 네임스페이스 표기도 실측으로 확인한다.
3. **GraphRAG 추출 품질.** opencode 배포에서 GraphRAG가 opencode를 경유하게 되므로, 추출 결과를 기존 Claude 경로와 비교한다. 품질이 무너지면 `llm-completer.ts`만 sdk 고정으로 되돌리는 것을 대안으로 둔다.
4. **분류 정확도.** haiku로 내린 뒤 기존 sonnet 결과와 표본 비교. 떨어지면 `ai.classify_model`을 비우는 것만으로 즉시 롤백된다.

## 마이그레이션·롤백

- 신규 키 기본값이 빈 문자열이므로 **배포만으로는 동작이 바뀌지 않는다.** 관리자가 값을 넣을 때 비로소 모델이 바뀐다.
- 롤백은 `ai.classify_model`을 비우는 것으로 끝난다(코드 롤백 불필요). 단 캐시 키에 설정값이 들어가므로, 되돌리면 이전 모델의 캐시가 다시 히트한다 — 이는 의도된 동작이다.
- 결정 A(completion 슬롯 분기)는 설정과 무관하게 적용되므로, opencode 배포의 GraphRAG·분류 경로는 배포 시점부터 바뀐다. 이것이 이 변경에서 유일하게 설정 없이 동작이 달라지는 부분이며, 검증 항목 3이 여기에 대응한다.
