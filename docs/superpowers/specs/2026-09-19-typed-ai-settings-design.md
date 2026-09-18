# AI 설정 유형별 구조 전환 + opencode provider 테넌트 스코프화 설계

**작성일:** 2026-09-19
**이슈:** [#693](https://github.com/bluleo78/smart-fire-hub/issues/693)
**로드맵:** 테넌트별 AI 설정 2단계 (1단계 완료 — `e66714b8`, `d8034d58`)

## 목표

`ai.agent_type` 을 판별자로 하는 유형별 구조로 AI 설정을 전환하고, opencode provider(baseURL·키·모델)를 테넌트별로 가른다.

## 문제

1단계에서 `ai.api_key` / `ai.cli_oauth_token` / `ai.agent_type` 3키를 테넌트 오버라이드로 열었다. 그런데 `opencode` 를 고른 테넌트들은 **여전히 전원이 같은 사내 엔드포인트를 공유한다.** opencode 는 모델이 아니라 provider 계층이라 baseURL + 키 + 모델 정의가 필요한데, 그 정의가 앱이 아니라 배포 측 PVC 전역 `opencode.jsonc` 에 있기 때문이다.

더 근본적인 문제가 그 아래 있다. **설정 구조가 "유형은 하나"라는 가정 위에 서 있다.**

- 유형은 4종이다 — `sdk` / `cli` / `cli-api` / `opencode`. 유형마다 필요한 입력이 다르다.
- 평면 settings 키는 "어느 유형에서만 유효한 키"를 표현하지 못한다. 번들 채움값 `""` 가 "설정 안 함"인지 "이 유형엔 해당 없음"인지 구분되지 않는다.
- 검증이 키 단위 고정 규칙이라(`SettingsService:904-910`) "opencode 일 때만 baseURL 필수" 를 표현할 수 없다.
- 슈퍼어드민(`firehub-admin`)은 카탈로그 배열을 순서대로 찍는 범용 렌더러라 유형 분기 코드가 **한 줄도 없다.** `에이전트 유형` 이 9개 중 8번째에 놓여 있는 것은 증상이고, 원인은 이 구조다.

1단계에서 유형을 테넌트가 고르게 만든 순간 이 전제가 깨졌다. opencode 가 "입력 없음"이라 지금까지 드러나지 않았을 뿐이다.

## 저장 계약

### `ai.credential` 단일 JSON 키

```json
{ "agentType": "opencode",
  "payload": { "providerId": "openai",
               "baseURL": "https://api.openai.com/v1",
               "model": "openai/gpt-4o",
               "reasoningEffort": "medium" },
  "secret":  { "apiKey": "<AES>" } }

{ "agentType": "sdk",
  "payload": { "model": "claude-sonnet-5" },
  "secret":  { "oauthToken": "<AES>", "apiKey": "<AES>" } }
```

두 평면 해석은 그대로다. `tenant_settings` 에 `ai.credential` 행이 있으면 테넌트 값, 없으면 `system_settings` 의 플랫폼 기본값. RLS 도 변함없다.

**1단계의 번들 기계가 통째로 사라진다** — `AI_CREDENTIAL_KEYS`, `applyAiCredentialBundle`, `BUNDLE_FILL_VALUES`, `rejectBundleKey`. 행이 하나면 원자성은 구조상 공짜다. **규칙(자격증명은 한 평면에서만 해석된다)은 유지되고 그것을 지키던 코드만 없어진다.**

### 비밀 처리

키 단위 전체 암호화(`SECRET_KEYS`)에서 **JSON 하위 필드 단위**로 바뀐다.

- 저장: `secret.*` 각 값을 개별 AES 암호화
- 조회: `secret` 값은 **내보내지 않고** 이름 목록만 (`secretFieldNames: ["apiKey"]`)
- `payload` 는 평문 반환 (baseURL·providerId 는 비밀이 아니다)

마스크된 값이라는 것이 존재하지 않게 된다. 1단계에서 겪은 마스크 오염 버그(`****ab12` 가 편집 가능 입력에 시드돼 덧붙이면 그대로 저장되던 것)가 **계약 수준에서 불가능해진다.**

### 키 변화

| | 키 |
|---|---|
| 삭제 | `ai.model`, `ai.api_key`, `ai.cli_oauth_token`, `ai.agent_type` |
| 신설 | `ai.credential` |
| 평면 유지 | `ai.system_prompt`, `ai.temperature`, `ai.max_turns`, `ai.max_tokens`, `ai.session_max_tokens` |

평면으로 남는 5키는 에이전트 유형과 무관한 동작 설정이라 유형별로 갈릴 이유가 없다.

`SettingsOverridePolicy.TENANT_OVERRIDABLE` 에서 4키를 빼고 `ai.credential` 을 넣는다.

## 백엔드 해석 인터페이스

`AiCredentials` record 를 **봉인된 계층**으로 바꾼다.

```java
public sealed interface AiCredential {
  record Sdk(String model, String oauthToken, String apiKey) implements AiCredential {}
  record Cli(String model, String oauthToken)                 implements AiCredential {}
  record CliApi(String model, String apiKey)                  implements AiCredential {}
  record Opencode(String providerId, String baseUrl, String model,
                  String reasoningEffort, String apiKey)      implements AiCredential {}
}
```

`settingsService.getAiCredential()` 이 이것을 돌려주고, 소비처 4곳(`AiAgentClient`, `AiController`, `AiAgentProxyService`, `ProactiveJobAsyncRunner`)은 switch 로 받는다.

**이 형태를 고르는 이유**: 1단계에서 `agentType` 이 문자열 비교였기 때문에 잘못된 분기가 조용히 컴파일되고 조용히 틀렸다(`6b1c6383` 이 고친 회귀 — opencode 테넌트가 플랫폼 키로 과금될 수 있었다). sealed + switch 면 유형이 늘 때 누락이 컴파일 오류가 된다.

`getDecryptedApiKey()` 류 단건 접근자는 부활시키지 않는다. 우회 경로를 컴파일 오류로 만든다는 1단계 판단 그대로다.

### 분류 경로는 예외가 아니다

`AiAgentClient.classify()` 는 `agent_type` 과 무관하게 항상 SDK 경로로 간다 — `provider-factory.ts` 의 `createCompletionProvider` 에 `agentType` 분기가 없고 `classification-service.ts:129` 가 무조건 호출한다. 따라서 **opencode 자격증명이어도 분류에는 그 키를 그대로 싣는다.** 테스트로 고정한다.

## API 표면

범용 key-value 엔드포인트에 JSON 을 문자열로 밀어 넣지 않는다. 전용 하위 리소스를 둔다(`POST /settings/smtp/test` 가 같은 모양의 선례다).

```
GET    /api/v1/settings/ai-credential        해석된 자격증명 (secret 제외)
PUT    /api/v1/settings/ai-credential        저장
DELETE /api/v1/settings/ai-credential        테넌트 값 삭제 → 플랫폼 상속으로 복귀
POST   /api/v1/settings/ai-credential/probe  연결 테스트 + 모델 목록
```

플랫폼은 `/api/platform/settings/ai-credential` 에 같은 모양(DELETE 제외 — 상속할 상위가 없다).

**응답:**
```json
{ "agentType": "opencode",
  "payload": { "providerId": "openai", "baseURL": "...", "model": "openai/gpt-4o" },
  "secretFieldNames": ["apiKey"],
  "tenantOwned": true }
```

`tenantOwned` 는 테넌트가 직접 설정했는지 여부다(기존 `overridden` 개념이되 화면 어휘에 맞춘 이름).

**프로브**는 `GET {baseURL}/models` 에 `Authorization: Bearer {apiKey}` 로 붙어 모델 ID 배열을 뽑는다. OpenAI 표준 `{data:[...]}` 과 배열 직반환을 모두 받는다. 타임아웃 10초. **apiKey 는 응답에도 로그에도 싣지 않는다.** 저장되지 않은 입력값으로 호출되며, 요청에 apiKey 가 없으면 저장된 값을 쓴다.

**저장 시 재검증**: `PUT` 은 저장 전에 프로브를 한 번 더 돌려 모델·추론 강도가 실제로 유효한지 확인하고, 아니면 400 으로 거부한다. 지원하지 않는 추론 강도는 런타임 오류가 되므로 저장 시점에 막는다. (`iacloud_eis` 는 이 동작이 없다 — 대신 목록을 받아야만 저장 버튼이 열린다.)

## 화면

### 공통

레이아웃·필드·유형 분기는 두 앱이 **동일**하다. 탭 → `모델 설정` 카드 → `자격증명` fieldset → **에이전트 유형(맨 앞)** → 유형별 필드 → 동작 설정.

유형별 필드:

| 유형 | 필드 |
|---|---|
| `sdk` | 모델, OAuth 토큰, API 키 |
| `cli` | 모델, OAuth 토큰 |
| `cli-api` | 모델, API 키 |
| `opencode` | 공급자, 기본 URL, API 키, 모델(+모델 불러오기), 추론 강도 |

비밀 필드는 값 대신 **"현재 값이 설정되어 있습니다"** 를 보여주고 입력칸은 비워 둔다. 마스크 문자열을 입력칸에 넣지 않는다.

**모델 칸 4상태**: 미로드(비활성 + "먼저 모델을 불러오세요") / 목록 있음(Select) / 목록 없음(자유 입력) / 실패(오류 문구). [모델 불러오기] 버튼은 **모델 칸 옆**에 두고, 기본 URL·API 키가 채워져야 활성화된다. 기본 URL 을 고치면 미로드로 되돌아간다.

**추론 강도**는 opencode 전용. 후보는 `기본값` + `low` / `medium` / `xhigh` 하드코딩(공급자에게 물어볼 API 가 없다). 목록 밖의 값이 저장돼 있으면 목록 맨 앞에 끼워 보존한다. `기본값` 은 빈 값으로 저장되고 아무것도 내려보내지 않아 공급자 기본값을 따른다.

### 테넌트 화면 — "가져다 쓸까, 직접 정할까"

배지와 [재정의 해제] 버튼을 없애고 **라디오 2개**로 바꾼다.

- **플랫폼 설정을 사용합니다** — 입력칸 없음. 지금 적용 중인 값만 정의 목록으로 보여준다.
- **우리 조직이 직접 설정합니다** — 폼이 열린다.

"재정의"는 시스템 내부 용어다. 사용자 입장에서는 상태를 읽는 것이 아니라 **고르는 것**이다. 되돌릴 때는 확인 다이얼로그를 띄우고, 문구는 기존 [재정의 해제] 것을 재사용한다(내용은 같고 부르는 말만 바뀐다).

**적용 범위는 AI 자격증명 그룹뿐이다.** 같은 화면의 단일 키(시스템 프롬프트·Temperature)와 다른 탭(이메일·임베딩)은 기존 배지 방식을 유지한다. 자격증명은 "한 벌로 함께 적용되는 묶음"이라 라디오가 자연스럽지만, 단일 키마다 라디오 두 줄을 붙이는 것은 과하다.

### 플랫폼 화면 — 플랫폼 값만

**배지 없음, 안내문 없음.** 테넌트 화면과 같은 레이아웃에서 **라디오 2개만 없다** — 플랫폼에는 "플랫폼 걸 쓸까" 라는 선택지가 없다. 가져다 쓸지는 테넌트가 결정한다.

기존 `테넌트 재정의 가능` / `전역 고정` 배지를 AI 탭에서 뺀다. 이 변경 뒤 AI 탭의 모든 키가 테넌트 재정의 가능해져 배지가 전부 같은 문구가 되고, 같은 문구가 9번 반복되면 구별에 쓰이지 않는다.

"직접 설정한 조직에는 적용되지 않습니다" 같은 안내도 넣지 않는다. 어느 조직인지도 몇 개인지도 말해주지 않아 읽고 나서 할 수 있는 일이 없다. 그 정보가 필요하다면 실제 숫자와 링크여야 하고, 그것은 테넌트 목록 화면의 일이다.

### 카탈로그

`firehub-admin` 의 AI 탭은 범용 렌더러(`tab.keys.map`)를 벗어나 전용 화면이 된다. **이메일·임베딩 탭은 지금 렌더러 그대로 둔다** — 유형 분기가 없어 바꿀 이유가 없다.

카탈로그 항목에 적용 유형과 평면을 단다:

```ts
'ai.credential.baseURL': { label: '기본 URL', kind: 'text',
                           appliesTo: ['opencode'], plane: 'payload' },
'ai.credential.apiKey':  { label: 'API 키', kind: 'secret',
                           appliesTo: ['sdk','cli-api','opencode'], plane: 'secret' },
```

**공유 패키지는 만들지 않는다.** `packages/*` 는 워크스페이스에 선언돼 있지만 디렉터리가 없어 선례가 0건이고, 첫 공유 패키지를 이 과제에서 만들면 빌드·타입·린트 배선이 딸려 온다. 두 앱의 정의가 어긋나면 서버의 유형별 스키마 검증이 400 으로 잡는다 — 조용히 틀리는 경로가 없다는 점이 중요하다.

## 에이전트

`apps/firehub-ai-agent/src/agent/agent-opencode.ts` 의 `buildOpenCodeConfig` 가 `provider` 블록을 쓰도록 바꾼다.

```ts
provider: {
  [payload.providerId]: {
    npm: '@ai-sdk/openai-compatible',
    options: { baseURL, apiKey },
    models: { [modelId]: { modalities: { input: ['text','image'], output: ['text'] },
                           ...(effort ? { options: { reasoningEffort: effort } } : {}) } },
  },
}
```

`npm` 은 `@ai-sdk/openai-compatible` 로 고정한다. `iacloud_eis` 는 payload 에서 덮어쓸 수 있게 두었지만 화면에서 입력받지 않아 실제로는 항상 기본값이다 — 쓰지 않는 자유도는 넣지 않는다.

이는 2026-06-23 의 "옵션 3: 배포 측 전역 설정 상속" 결정을 **뒤집는 것**이다. 그것을 고정하던 `agent-opencode.test.ts:16` (`model 필드를 넣지 않는다`)도 함께 뒤집는다.

`iacloud_eis` 가 실측으로 얻은 함정을 그대로 가져온다:

- `modalities.input` 에 `image` 필수 — 없으면 opencode 코어가 사용자 메시지의 image 파트를 제거하고 "does not support image input" 에러 텍스트로 바꿔친다.
- 빈 문자열은 "설정 안 함"이다 — 그대로 보내면 400.
- 모델의 `providerID` 와 `payload.providerId` 가 다르면 provider 미스매치로 실패하므로 방어적으로 먼저 throw.

## 마이그레이션

기존 행을 SQL 로 변환한다. **비밀은 재암호화가 필요 없다** — 값 단위 AES 암호문이라 문자열을 JSON 안으로 옮기기만 하면 된다.

```
system_settings:  ai.model + ai.api_key + ai.cli_oauth_token + ai.agent_type → ai.credential
tenant_settings:  테넌트별로 같은 변환
그 후 옛 4키 삭제
```

테넌트에 4키가 전부 있지는 않을 수 있다. 1단계 번들 규칙이 이미 그 경우의 해석을 정해 두었으므로 **같은 규칙을 그대로 적용한다** — 3키 중 하나라도 행이 있으면 나머지는 자격증명은 `""`, `agent_type` 은 `"sdk"` 로 채워진 것으로 보고 변환한다. `ai.model` 은 번들 밖이었으므로 행이 없으면 플랫폼 값을 읽어 넣는다. 즉 **변환 전후로 각 테넌트에 적용되는 실제 값이 바뀌지 않는다** — 이것이 이 마이그레이션의 성공 기준이다.

`opencode` 인 행은 `providerId`/`baseURL` 이 없으므로 **미설정 상태의 opencode credential** 로 남는다 — 운영자가 입력해야 동작한다. **현재 opencode 사용자가 없음을 전제로 한 결정이다.**

배포 측 전역 `opencode.jsonc` 의존은 코드에서 걷어낸다.

## 테스트

`CLAUDE.md` 규칙대로 백엔드는 TC, 프런트는 Playwright E2E.

**추가·변경하는 테스트마다 뮤테이션 검사를 한다.** 1단계에서 공허한 테스트가 네 번 나왔고 두 건은 리뷰를 통과했다.

반드시 고정할 것:

- **분류 경로** — opencode 자격증명일 때도 분류에는 그 키가 실린다(1단계에서 반대로 지시해 회귀를 만든 지점).
- **프로브 비밀 미노출** — 응답·로그 어디에도 apiKey 가 없다.
- **저장 시 재검증** — 지원하지 않는 추론 강도·존재하지 않는 모델이 400 으로 거부된다.
- **유형 전환** — 유형을 바꾸면 이전 유형의 필드가 payload/secret 에 남지 않는다.

E2E 는 이 머신에서 핀 고정 브라우저가 설치되지 않으므로 시스템 Chrome 우회로 돌리고 `playwright.config.ts` 원복을 증명한다.

## 범위 밖

- **Claude 계열 추론 강도** — `firehub-ai-agent` 에 전달 경로가 전혀 없다(`thinking`·`reasoningEffort`·`budget_tokens` 0건). 모델마다 받는 형식이 달라(최신 모델은 `budget_tokens` 를 400 으로 거부하고 adaptive thinking 을 쓴다) 조사가 먼저 필요하다. **별도 이슈.**
- **`enabled` 플래그** — `iacloud_eis` 에는 있으나 우리 쪽에 쓸 자리가 없다.
- **테넌트 재정의 허용 여부를 운영자가 토글** — 껐을 때 이미 직접 설정한 테넌트를 어떻게 할지가 과금 주체를 바꾸므로 별도 설계가 필요하다.
- **SMTP 후속 3건** — `useSmtpSettingsForm.ts:346` 의 `failedLabels` 폐기 버그, AI 와 벌어진 부분 실패 어휘, 두 훅에 복사된 재조회 실패 문구.
- **3단계 `ai.classify_model`** — 별건. 다만 모델이 `credential` 로 들어간 이상 분류 모델도 같은 자리에 오는 것이 일관적이다.

## 참조

- 참조 구현: `~/git/iacloud_eis` — `apps/eis-ai-agent/src/agent/opencode-config.ts`, `opencode-probe.ts`, `apps/eis-web/src/features/ai/AiSettingsPage.tsx`, `opencode-presets.ts`
- 1단계 설계: `docs/superpowers/specs/2026-09-18-tenant-scoped-ai-settings-design.md`
- 3단계 설계: `docs/superpowers/specs/2026-09-18-ai-classify-model-selection-design.md`
- 화면 목업: `.superpowers/brainstorm/23724-1789773261/content/` (git 추적 안 함)
