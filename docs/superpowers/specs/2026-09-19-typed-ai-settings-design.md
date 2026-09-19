# AI 설정 유형별 구조 전환 + opencode provider 테넌트 스코프화 설계

**작성일:** 2026-09-19
**이슈:** [#693](https://github.com/bluleo78/smart-fire-hub/issues/693)
**로드맵:** 테넌트별 AI 설정 2단계 (1단계 완료 — `e66714b8`, `d8034d58`)
**리뷰:** 2026-09-19 UI/UX·API·백엔드 3방향 고강도 리뷰 반영 (아래 「리뷰에서 바뀐 것」 참고)

## 목표

`ai.agent_type` 을 판별자로 하는 유형별 구조로 AI 자격증명을 전환하고, opencode provider(baseURL·키)를 테넌트별로 가른다.

## 문제

1단계에서 `ai.api_key` / `ai.cli_oauth_token` / `ai.agent_type` 3키를 테넌트 오버라이드로 열었다. 그런데 `opencode` 를 고른 테넌트들은 여전히 전원이 같은 사내 엔드포인트를 공유한다. opencode 는 모델이 아니라 provider 계층이라 baseURL + 키 + 모델 정의가 필요한데, 그 정의가 배포 측 PVC 전역 `opencode.jsonc` 에 있기 때문이다.

더 근본적인 문제가 그 아래 있다. **설정 구조가 "유형은 하나"라는 가정 위에 서 있다.** 유형은 4종(`sdk`/`cli`/`cli-api`/`opencode`)이고 유형마다 필요한 입력이 다른데, 평면 키는 "어느 유형에서만 유효한 키"를 표현하지 못한다. 번들 채움값 `""` 가 "설정 안 함"인지 "이 유형엔 해당 없음"인지 구분되지 않고, 검증은 키 단위 고정 규칙이라(`SettingsService:904-910`) "opencode 일 때만 baseURL 필수" 를 표현할 수 없다. 슈퍼어드민(`firehub-admin`)은 카탈로그 배열을 순서대로 찍는 범용 렌더러라 유형 분기 코드가 한 줄도 없다 — `에이전트 유형` 이 9개 중 8번째에 놓인 것은 증상이고 원인은 이 구조다.

1단계에서 유형을 테넌트가 고르게 만든 순간 이 전제가 깨졌다. opencode 가 "입력 없음"이라 드러나지 않았을 뿐이다.

## 지금 존재하는 버그 (이 작업이 고친다)

`agent_type=opencode` 만 저장한 테넌트는 번들 채움으로 `api_key=""` 가 된다. `AiAgentClient.classify()` 는 빈 값이면 자격증명을 싣지 않고, `buildCompletionEnv` 는 그러면 컨테이너의 ambient `ANTHROPIC_API_KEY` 로 폴백한다. **즉 opencode 테넌트의 AI 분류는 이미 플랫폼 계정으로 과금되고 있다.** GraphRAG 추출도 같은 경로를 쓰므로 같은 상태다. 아래 「completion 경로」가 이것을 테넌트 자기 공급자 호출로 바꾼다.

## 저장 계약

### `ai.credential` — 자격증명만

```json
{ "v": 1,
  "agentType": "opencode",
  "payload": { "providerId": "openai",
               "baseURL": "https://api.openai.com/v1",
               "reasoningEffort": "medium" },
  "secret":  { "apiKey": "<AES>" } }

{ "v": 1,
  "agentType": "sdk",
  "payload": {},
  "secret":  { "oauthToken": "<AES>", "apiKey": "<AES>" } }
```

`v` 는 스키마 버전이다. 3단계에서 필드가 늘 때 낡은 UI 의 read-modify-write 가 새 필드를 조용히 떨구지 않도록, 역직렬화는 **모르는 필드를 보존**한다.

두 평면 해석은 그대로다. `tenant_settings` 에 행이 있으면 테넌트 값, 없으면 `system_settings` 의 플랫폼 기본값. RLS 도 변함없다.

**1단계의 번들 기계가 사라진다** — `AI_CREDENTIAL_KEYS`, `applyAiCredentialBundle`, `BUNDLE_FILL_VALUES`. 행이 하나면 원자성은 구조상 공짜다. 규칙(자격증명은 한 평면에서만 해석된다)은 유지되고 그것을 지키던 코드만 없어진다.

### `ai.model` 은 평면 키로 남는다

**자격증명이 아니기 때문이다.** 1단계가 의도적으로 번들 밖에 둔 것을 안으로 넣으면 세 가지가 한꺼번에 생긴다 — 마이그레이션에 정의 불가능한 케이스(모델만 재정의한 테넌트), 모델 상속 단절(플랫폼 모델 변경이 더 이상 안 닿음), sealed 계층에서 `model` 이 네 record 전부에 중복. 평면으로 두면 셋 다 사라지고 "플랫폼 자격증명 + 우리 모델" 조합도 살아남는다.

**대가는 정합성을 콜로케이션이 아니라 검증으로 지켜야 한다는 것이다.** `agentType=opencode` 일 때 `ai.model` 은 `providerId/modelId` 형식이어야 하고 그 `providerId` 가 `credential.payload.providerId` 와 같아야 한다. 서버가 저장 시 검증한다(둘 중 무엇을 저장하든 상대를 함께 읽어 확인).

### 비밀 처리

키 단위 전체 암호화(`SECRET_KEYS`)에서 **JSON 하위 필드 단위**로 바뀐다.

- 저장: `secret.*` 각 값을 개별 AES 암호화
- 조회: `secret` 값은 **내보내지 않고** 이름 목록만 (`secretFieldNames: ["apiKey"]`)
- `payload` 는 평문 반환 (baseURL·providerId 는 비밀이 아니다)

마스크된 값이라는 것이 존재하지 않게 되어, 1단계에서 겪은 마스크 오염 버그가 계약 수준에서 불가능해진다.

**`secretFieldNames` 는 "값이 있음"을 뜻한다.** 빈 문자열로 저장된 키는 포함하지 않는다 — 지운 키를 "설정됨"으로 표시하면 안 된다.

### 범용 조회에서 제외한다 (필수)

`ai.credential` 은 `SECRET_KEYS` 에 없으므로 아무 조치도 하지 않으면 `getAll()` / `getResolvedByPrefix("ai")` / `getAsMap("ai")` / `getValue("ai.credential")` 이 **모든 비밀의 AES 암호문을 포함한 JSON 을 그대로 내보낸다.** `SettingsService:305` javadoc 이 기록한 사고와 같은 부류이고, 1단계에서 그 문을 닫던 `rejectBundleKey` 를 이 설계가 없애려 했다.

**대체 불변식: 어떤 범용 읽기 경로도 `ai.credential` 을 내보내지 않는다.** prefix/all 조회에서 제외하고, 단건 조회는 `rejectBundleKey` 에 해당하는 거부를 유지한다. 테스트로 고정한다.

### 키 변화

| | 키 |
|---|---|
| 삭제 | `ai.api_key`, `ai.cli_oauth_token`, `ai.agent_type` |
| 신설 | `ai.credential` |
| 평면 유지 | **`ai.model`**, `ai.system_prompt`, `ai.temperature`, `ai.max_turns`, `ai.max_tokens`, `ai.session_max_tokens` |

`SettingsOverridePolicy.TENANT_OVERRIDABLE` 에서 3키를 빼고 `ai.credential` 을 넣는다. `ai.model` 은 그대로 둔다.

## 백엔드 해석 인터페이스

```java
public sealed interface AiCredential {
  record Sdk(String oauthToken, String apiKey) implements AiCredential {}
  record Cli(String oauthToken)                implements AiCredential {}
  record CliApi(String apiKey)                 implements AiCredential {}
  record Opencode(String providerId, String baseUrl,
                  String reasoningEffort, String apiKey) implements AiCredential {}
}
```

`model` 이 빠져 판별이 선명해졌다. `settingsService.getAiCredential()` 이 이것을 돌려주고, 소비처 4곳(`AiAgentClient`, `AiController`, `AiAgentProxyService`, `ProactiveJobAsyncRunner`)은 switch 로 받는다. 모델은 지금처럼 `getValue("ai.model")` 로 따로 읽는다 — **기존 호출부(`AiAgentClient:83`, `AiAgentProxyService:242`)가 그대로 유효하다.**

**이 형태를 고르는 이유**: 1단계에서 `agentType` 이 문자열 비교였기 때문에 잘못된 분기가 조용히 컴파일되고 조용히 틀렸다(`6b1c6383` 회귀). sealed + switch 면 유형이 늘 때 누락이 컴파일 오류가 된다.

**알 수 없는 `agentType`** (손으로 고친 행, 롤백된 배포)은 **fail-closed** — 플랫폼 값으로 폴백하지 않는다(그 폴백이 `6b1c6383` 과 같은 과금 회귀다). 네 소비처 각각에서 사용자에게 보이는 오류로 끝내고, 설정 GET/DELETE 는 계속 동작해야 관리자가 되돌릴 수 있다.

`getDecryptedApiKey()` 류 단건 접근자는 부활시키지 않는다.

### completion 경로 — opencode 전용 프로바이더를 더한다

`createCompletionProvider` 는 지금 `agentType` 분기 없이 항상 `ClaudeSdkCompletionProvider` 를 돌려준다(`provider-factory.ts:52-57`). 호출부는 둘 — **분류**(`classification-service.ts:129`)와 **GraphRAG 추출**(`llm-completer.ts:30`).

**`Opencode.apiKey` 는 OpenAI 호환 공급자의 키다.** Claude SDK 에 실으면 401 이고, 비어 있으면 ambient 키로 떨어진다(= 위에 적은 현존 버그). 1단계의 "opencode 도 분류에는 자격증명을 싣는다" 규칙은 그때 `ai.api_key` 가 의미상 항상 Anthropic 키였기에 성립했고, 유형별 구조에서는 성립하지 않는다.

`createCompletionProvider` 에 `agentType` 분기를 넣고 **`OpenAICompatCompletionProvider`** 를 더한다 — `POST {baseURL}/chat/completions` 한 번이다.

```ts
switch (cred.agentType) {
  case 'opencode': return new OpenAICompatCompletionProvider(baseURL, apiKey, model);
  default:         return new ClaudeSdkCompletionProvider(apiKey, oauthToken, model);
}
```

**이것이 가능해진 이유가 이 변경 자체다.** 예전에는 baseURL·키가 배포 측 전역 파일에 있어 앱이 알 수 없었다. 이제 credential 에 있으므로 직접 호출할 수 있다. opencode CLI 를 다시 스폰할 필요가 없다 — 분류·추출은 단발 completion 이라 CLI 의 도구·세션 기계가 필요 없다.

"분류 불가"로 두지 않는 이유: 테넌트가 opencode 를 고른 것은 자기 AI 로 돌리겠다는 뜻이고 분류도 AI 작업이다. 파이프라인의 `AI_CLASSIFY` 스텝이 에이전트 유형에 따라 되고 안 되고 하면 기능 구멍이다.

`createCompletionProvider` 의 "채팅과 달리 agentType 분기가 없다" 주석은 그 이유("자격증명만 흘려받아 인증 경로가 갈라지지 않도록")가 **자격증명이 한 종류일 때** 성립하던 것이므로 함께 갱신한다.

**주의**: 분류·GraphRAG 추출 프롬프트는 Claude 기준으로 튜닝돼 있다(`systemPromptMode: 'append-to-preset'` 을 쓰는 호출부가 있다). OpenAI 호환 경로에는 claude_code 프리셋이 없으므로 그 모드의 의미를 정의하고, 출력 품질을 실제 공급자로 확인한다.

테스트로 고정할 것: (1) `Opencode` 자격증명이면 OpenAI 호환 프로바이더가 선택됨, (2) 테넌트 자격증명이 있을 때 ambient 키가 **결코** 쓰이지 않음, (3) 두 호출부(분류·GraphRAG) 모두 같은 분기를 지남.

## API 표면

```
GET    /api/v1/settings/ai-credential        해석된 자격증명 (secret 제외)
PUT    /api/v1/settings/ai-credential        저장
DELETE /api/v1/settings/ai-credential        테넌트 값 삭제 → 플랫폼 상속으로 복귀
POST   /api/v1/settings/ai-credential/probe  모델 목록 조회 (opencode 전용)
```

플랫폼은 `/api/platform/settings/ai-credential` 에 같은 모양(DELETE 제외). **플랫폼 응답에는 `tenantOwned` 를 넣지 않는다** — 상위 평면이 없어 의미가 없고, "항상 false" 는 거짓이다(플랫폼이 그 값을 소유한다).

**테넌트 응답:**
```json
{ "agentType": "opencode",
  "payload": { "providerId": "openai", "baseURL": "..." },
  "secretFieldNames": ["apiKey"],
  "tenantOwned": true }
```

**권한**: 테넌트는 `ai:settings`, 플랫폼은 `platform:settings:*`. **프로브는 인증된 외부 호출을 발생시키므로 쓰기 권한을 요구한다.** 상속 중인 GET 이 `secretFieldNames` 로 "플랫폼에 키가 있다"를 알려주는 것은 현재 마스크가 이미 주던 정보와 같으므로 허용한다.

**PUT 의 비밀 의미**: 필드를 **생략하면 현재 값 유지**, 빈 문자열이면 삭제. 화면의 "비워두면 현재 값 유지"가 이 계약에 대응한다. `agentType` 이 바뀌면 **이전 유형의 secret 은 이름이 겹치더라도 전부 폐기한다** — `Sdk.apiKey`(Anthropic)와 `Opencode.apiKey`(OpenAI 호환)는 이름만 같고 다른 비밀이다. 남겨두면 Anthropic 키가 임의의 호환 호스트로 Bearer 전송된다.

### 프로브 (opencode 전용)

`GET {baseURL}/models` 에 `Authorization: Bearer {apiKey}` 로 붙어 모델 ID 배열을 뽑는다. OpenAI 표준 `{data:[...]}` 과 배열 직반환을 모두 받는다. 타임아웃 10초.

**보안 요구 (전부 필수).** 이것은 인증된 테넌트 관리자가 서버로 하여금 임의 URL 에 Bearer 를 실어 보내게 할 수 있는 표면이다.

- **평면 교차 폴백 금지.** 요청이 apiKey 를 생략했을 때 쓰는 "저장된 값"은 **`tenant_settings` 의 값만**이다. 두 평면 해석기(`getValue`)를 쓰면 테넌트가 **플랫폼 키**를 임의 주소로 내보낼 수 있다. `tenantOwned=false` 인데 secret 을 생략했으면 400.
- **baseURL 이 저장된 값과 다르면 apiKey 를 요청에 반드시 포함**해야 한다.
- https 전용, 리다이렉트 추적 금지, 고정 포트 집합.
- DNS 해석 결과가 loopback / 사설 대역 / link-local / `169.254.169.254` 면 거부.
- 응답은 **모델 ID 배열만** 담는다. upstream 본문·상태 텍스트·헤더를 그대로 흘리지 않는다. apiKey 는 응답에도 로그에도 싣지 않는다.

**응답 형태**는 `POST /settings/smtp/test` 선례를 따라 항상 200 + `{ ok, models, message }`.

### 저장 시 검증 — 할 수 있는 것만 약속한다

`GET {baseURL}/models` 는 **모델 ID 만** 돌려준다. 어떤 모델이 어떤 `reasoningEffort` 를 지원하는지는 알 수 없다.

- **모델 존재**: 프로브가 **비어 있지 않은 목록**을 줄 때만 소속을 검증한다. 목록이 비면(공급자가 `/models` 를 주지 않음) 화면이 자유 입력으로 전환되므로, 그 경우 **저장을 막지 않는다.** (초안은 "존재하지 않는 모델이면 400"이라고 적어 자유 입력 상태와 모순됐다.)
- **추론 강도**: 정적 enum 으로만 검증한다. 지원 여부는 **런타임에 드러난다** — 과금되는 completion 호출로 저장을 게이트하지 않는다. 화면도 그렇게 말한다.
- **정합성**: `agentType=opencode` 면 `ai.model` 의 `providerId` 접두사가 `payload.providerId` 와 일치해야 한다.

**오류 의미**: 400 = 페이로드 형식, 422 = 공급자가 거부 / 모델이 목록에 없음, 502 = 도달 불가, 504 = 10초 타임아웃.

**동시성**: `updated_at` 기반 `If-Match` → 409 를 권장한다(필수 아님). 블롭 last-write-wins 는 1단계에도 있던 충돌이 한 행으로 모인 것뿐이지만, 저장 전 프로브(최대 10초)가 창을 넓힌다.

## 화면

### 공통

레이아웃·필드·유형 분기는 두 앱이 동일하다. 탭 → `모델 설정` 카드 → `자격증명` fieldset → **에이전트 유형(맨 앞)** → 유형별 필드 → 모델 → 동작 설정.

| 유형 | 자격증명 필드 | 검증 수단 |
|---|---|---|
| `sdk` | OAuth 토큰, API 키 | **인증 확인** (기존 버튼 유지) |
| `cli` | OAuth 토큰 | 인증 확인 |
| `cli-api` | API 키 | 인증 확인 |
| `opencode` | 공급자, 기본 URL, API 키, 추론 강도 | **모델 불러오기** |

**`인증 확인` 버튼과 `✓ 인증됨` 배지는 유지한다.** 저장·자격증명 변경 후 재실행하고, 경쟁 조건 가드(`SettingsPage.tsx:97-134`, `56cfb813` 에서 추가)도 유지한다. 프로브는 opencode 의 검증 수단이지 이것의 대체가 아니다.

**비밀 필드**: 값 대신 사실절("현재 값이 설정되어 있습니다")과 행동절("바꾸려면 새 값을 입력하세요")을 분리해 보여주고 입력칸은 비운다. 저장된 값이 없으면 사실절을 쓰지 않는다. 마스크 문자열은 어떤 경로로도 입력칸에 들어가지 않는다.

**유형 전환**: 유형을 바꾸면 이전 유형의 비밀이 **복구 불가로 사라진다.** 저장된 유형과 현재 선택이 다르면 유형 Select 아래에 정적 안내를 띄우고, 저장 확인에도 포함한다. Select 변경만으로 조용히 파괴되지 않게 한다.

**모델 칸 4상태**: 미로드(비활성 + "먼저 모델을 불러오세요") / 목록 있음(Select) / 목록 없음(자유 입력) / **실패(오류 문구 + "직접 입력으로 전환" 동작)**. 미로드와 실패가 같은 모양이 되지 않게 하고, 실패했을 때 자유 입력으로 갈 길을 준다. 기본 URL 을 고치면 미로드로 되돌아간다.

**[모델 불러오기] 활성 조건**: 기본 URL 이 있고, **요청에 실을 키가 있거나 `secretFieldNames` 에 `apiKey` 가 있을 때.** 저장된 키가 있는 테넌트에게 읽을 수도 없는 값을 다시 입력하라고 요구하지 않는다.

**모델 접두사**: `providerId/` 는 **서버에서 한 곳에만 붙인다.** 화면 Select 의 option value 에 붙이면 자유 입력 분기가 접두사 없는 값을 저장하고 채팅 시점에야 깨진다(참조 구현 `iacloud_eis` 에 이 잠재 버그가 있다 — `AiSettingsPage.tsx:529` 는 붙이고 자유 입력 분기는 안 붙이는데 `splitOpencodeModel` 은 `/` 가 없으면 throw 한다).

**추론 강도**(opencode 전용): `기본값` + `low` / `medium` / `high`. opencode 가 공급자에게 그대로 넘기는 필드이므로 **권위 있는 집합은 공급자의 것**이고 우리 목록은 후보다. 저장된 값이 목록에 없으면 맨 앞에 끼워 보존한다. `기본값` 은 빈 값으로 저장되고 아무것도 내려보내지 않는다.

**잠금 상태**: 서버가 편집 불가로 보고하면 라디오 둘 다 비활성 + `PlatformLockedNote`. 그룹은 fail-closed 를 유지한다.

### 테넌트 화면 — "가져다 쓸까, 직접 정할까"

배지와 [재정의 해제] 버튼을 없애고 **라디오 2개**로 바꾼다. 라벨은 **「플랫폼 설정을 사용」 / 「우리 조직이 직접 설정」** (현재형 단정 대신 선택지로 읽히게).

- **플랫폼 설정을 사용** — 입력칸 없음. 지금 적용 중인 값을 정의 목록으로 보여준다.
  **플랫폼에도 자격증명이 없으면** 빈 목록이 아니라 *"플랫폼에 설정된 값이 없습니다 — AI 기능이 동작하지 않습니다. 직접 설정하거나 플랫폼 운영자에게 요청하세요."* 를 보여준다(배지와 함께 사라진 `no-default` 상태의 대체).
- **우리 조직이 직접 설정** — 폼이 열린다.

**전환 의미: 폼 상태다.** 라디오를 바꿔도 즉시 DELETE 하지 않고 **저장할 때 반영**한다. 따라서 저장 전에 되돌리면 확인 다이얼로그도 DELETE 도 없다. (즉시 삭제로 하면 실수로 한 번 누른 대가가 모든 비밀 재입력이다 — 평문을 돌려주지 않으므로.) 저장 시 확인 다이얼로그를 띄우고, 미저장 입력이 있으면 그 사실을 문구에 더한다(`UNSAVED_CREDENTIAL_WARNING` 의 성질을 유지).

확인 문구는 기존 [재정의 해제] 텍스트를 **그대로 재사용하지 않는다** — 그 문자열에 "다시 재정의할 수 있습니다"가 들어 있다. 목업(`inherit-or-own.html`)의 문구를 쓴다.

**과금 문구 주의**: "사용량도 우리 계정으로 청구됩니다"는 `cli`(구독 OAuth)와 사내 엔드포인트에서 거짓이다. **"AI 호출이 우리 조직 자격증명으로 나갑니다"** 로 쓴다.

**접근성**: 라디오는 `자격증명` fieldset 안에 들어가므로, 질문 자체가 읽히도록 중첩 `fieldset`/`legend` 또는 `role="radiogroup"` + `aria-label` 을 준다. 잠금 사유는 툴팁이 아니라 정적 텍스트로 유지한다.

**적용 범위는 AI 자격증명 그룹뿐이다.** 다만 같은 화면의 단일 키 배지가 라디오 두 줄 아래에서 같은 상태를 다른 말로 부르면 안 되므로, 배지 문구를 라디오 어휘로 맞춘다 — `테넌트 재정의 적용됨` → **`우리 조직 값 적용 중`**, `기본값 사용 중` → **`플랫폼 값 사용 중`**. 단일 키를 라디오로 바꾸지는 않는다. E2E 셀렉터를 함께 갱신한다.

### 플랫폼 화면 — 플랫폼 값만

**배지 없음, 안내문 없음.** 테넌트 화면과 같은 레이아웃에서 라디오 2개만 없다 — 플랫폼에는 "플랫폼 걸 쓸까" 라는 선택지가 없다. 가져다 쓸지는 테넌트가 결정한다.

AI 탭에서 `테넌트 재정의 가능` / `전역 고정` 배지를 뺀다. 이 변경 뒤 AI 탭의 모든 키가 테넌트 재정의 가능해져 배지가 전부 같은 문구가 되고, 같은 문구가 9번 반복되면 구별에 쓰이지 않는다.

"직접 설정한 조직에는 적용되지 않습니다" 같은 안내도 넣지 않는다 — 어느 조직인지도 몇 개인지도 말해주지 않아 읽고 할 수 있는 일이 없다. 그 정보가 필요하다면 실제 숫자와 링크여야 하고, 그것은 테넌트 목록 화면의 일이다.

### 카탈로그

`firehub-admin` 의 AI 탭은 범용 렌더러(`tab.keys.map`)를 벗어나 전용 화면이 된다. **이메일·임베딩 탭은 지금 렌더러 그대로 둔다.**

카탈로그 항목에 적용 유형과 평면을 단다:

```ts
'ai.credential.baseURL': { label: '기본 URL', kind: 'text',
                           appliesTo: ['opencode'], plane: 'payload' },
'ai.credential.apiKey':  { label: 'API 키', kind: 'secret',
                           appliesTo: ['sdk','cli-api','opencode'], plane: 'secret' },
```

**공유 패키지는 만들지 않는다.** `packages/*` 는 워크스페이스에 선언돼 있지만 디렉터리가 없어 선례가 0건이고, 첫 공유 패키지를 이 과제에서 만들면 빌드·타입·린트 배선이 딸려 온다. 두 앱의 정의가 어긋나면 서버의 유형별 스키마 검증이 400 으로 잡는다.

## 에이전트

`agent-opencode.ts` 의 `buildOpenCodeConfig` 가 `provider` 블록을 쓰도록 바꾼다.

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

`npm` 은 고정한다 — `iacloud_eis` 는 payload 에서 덮어쓸 수 있게 두었지만 화면에서 입력받지 않아 실제로는 항상 기본값이다. 쓰지 않는 자유도는 넣지 않는다.

이는 2026-06-23 의 "옵션 3: 배포 측 전역 설정 상속" 결정을 **뒤집는 것**이다. 그것을 고정하던 `agent-opencode.test.ts:16` 도 함께 뒤집는다.

`iacloud_eis` 가 실측으로 얻은 함정을 그대로 가져온다:

- `modalities.input` 에 `image` 필수 — 없으면 opencode 코어가 image 파트를 제거하고 "does not support image input" 에러 텍스트로 바꿔친다.
- 빈 문자열은 "설정 안 함"이다 — 그대로 보내면 400.
- 모델의 `providerID` 와 `payload.providerId` 가 다르면 provider 미스매치로 실패하므로 방어적으로 먼저 throw.

## 마이그레이션

### 착수 전 증거 수집 (필수)

로컬 `tenant_settings` 는 0행이라 여기서는 규모를 알 수 없다. **운영 DB 에서 먼저 확인한다.**

```sql
SELECT tenant_id, array_agg(key ORDER BY key) FROM tenant_settings WHERE key LIKE 'ai.%' GROUP BY tenant_id;
SELECT count(*) FROM tenant_settings WHERE key='ai.agent_type' AND value='opencode';
```

두 번째 질의가 0 이 아니면 **마이그레이션을 중단한다** — "opencode 사용자 없음" 전제가 깨지면 필드가 빈 `Opencode` 레코드가 만들어진다. 마이그레이션 자체에도 이 가드를 넣는다.

### 변환

`ai.model` 이 평면으로 남으므로 케이스는 둘뿐이다.

| 번들 3키 | 처리 | 결과 |
|---|---|---|
| 하나도 없음 | 아무것도 안 함 | 플랫폼 상속 유지 |
| 하나라도 있음 | 1단계 번들 규칙대로(없는 키는 자격증명 `""`, `agent_type` `"sdk"`) `ai.credential` 생성 | 적용 값·두 평면 관계 모두 보존 |

`ai.model` 행은 **건드리지 않는다.** 초안의 "모델만 재정의한 테넌트" 난제가 사라진다.

**빈 비밀의 두 형태에 주의**: V31/V41 이 리터럴 `''` 로 시드했고 `encryptIfSecret` 은 이 두 키에 한해 빈 값도 암호화한다 — 즉 "비어 있음"이 평문 `''` 과 빈 문자열의 암호문 두 모양으로 존재한다. 복호화는 앱 안에 있으므로 **순수 SQL 로는 구분할 수 없다.** 마이그레이션을 앱 코드(또는 Java 기반 Flyway 마이그레이션)로 수행하거나, 변환 후 `secretFieldNames` 계산이 "키 존재"가 아니라 "복호화 결과가 비어 있지 않음"을 보도록 한다.

**RLS**: 마이그레이션은 소유자 `app` 으로 돌고 V114 가 `FORCE` 를 걸지 않아 GUC 없이 전 행을 본다. 이 전제를 주석에 남기고 **변환 행 수를 단언한다** — 나중에 `FORCE` 가 추가되면 조용히 0행을 변환하게 된다.

### 롤백

Flyway community 는 undo 가 없으므로 **forward-only** 다. 되돌리는 마이그레이션은 블롭을 다시 펼치는 V(n+1) 이 된다. 복원 가능: `sdk`/`cli`/`cli-api` 의 필드(암호문 그대로). 복원 불가: `providerId`·`baseURL`·`reasoningEffort`.

따라서 **양 테이블을 스냅샷하고, 옛 3키 삭제는 한 릴리스 미룬다.** 새 코드가 안정된 뒤 별도 마이그레이션으로 지운다.

## 테스트

`CLAUDE.md` 규칙대로 백엔드는 TC, 프런트는 Playwright E2E. **추가·변경하는 테스트마다 뮤테이션 검사를 한다** — 1단계에서 공허한 테스트가 네 번 나왔고 두 건은 리뷰를 통과했다.

반드시 고정할 것:

- **completion 분기**: `Opencode` 면 OpenAI 호환 프로바이더가 선택되고, 분류·GraphRAG 두 호출부가 같은 분기를 지남. 테넌트 자격증명이 있을 때 ambient 키가 결코 쓰이지 않음.
- **범용 조회 차단**: `getAll` / prefix 조회 / 단건 조회 어느 것도 `ai.credential` 을 내보내지 않음(암호문 유출 회귀 방지).
- **프로브 보안**: 평면 교차 폴백 거부, baseURL 변경 시 키 요구, 사설 대역 거부, 응답에 upstream 본문·apiKey 없음.
- **유형 전환**: 이전 유형의 secret 이 이름이 겹쳐도 전부 폐기됨.
- **알 수 없는 agentType**: fail-closed 이면서 설정 GET/DELETE 는 동작.
- **마이그레이션**: 변환 전후로 각 테넌트에 적용되는 값이 동일. opencode 행이 있으면 중단.

E2E 는 이 머신에서 핀 고정 브라우저가 설치되지 않으므로 시스템 Chrome 우회로 돌리고 `playwright.config.ts` 원복을 증명한다.

## 범위 밖

- **Claude 계열 추론 강도** — `firehub-ai-agent` 에 전달 경로가 전혀 없다(`thinking`·`reasoningEffort`·`budget_tokens` 0건). 현행 모델은 `budget_tokens` 를 400 으로 거부하고 별도 파라미터를 쓰므로 조사가 먼저 필요하다. **별도 이슈.**
- **`enabled` 플래그** — `iacloud_eis` 에는 있으나 쓸 자리가 없다.
- **테넌트 재정의 허용 여부 토글** — 껐을 때 이미 직접 설정한 테넌트 처리가 과금 주체를 바꾸므로 별도 설계.
- **SMTP 후속 3건** — `useSmtpSettingsForm.ts:346` 의 `failedLabels` 폐기 버그, 부분 실패 어휘 분기, 두 훅에 복사된 재조회 실패 문구.
- **3단계 `ai.classify_model`** — 별건.

## 리뷰에서 바뀐 것

초안 대비 주요 수정:

1. **`ai.model` 을 credential 에 넣지 않는다** (3개 리뷰 모두 반대). 마이그레이션 난제·모델 상속 단절·sealed 중복·`ai.model` 소비처 무성 회귀가 한꺼번에 해소된다.
2. **completion 경로에 opencode 분기를 더한다.** 초안은 현존 과금 혼입 버그를 테스트로 고정하려 했다. 리뷰는 "분류 불가로 막고 3단계에서 해결"을 권했으나, 그 판단은 provider 설정이 앱 밖에 있던 전제였다 — 이 변경이 baseURL·키를 앱에 주므로 `POST {baseURL}/chat/completions` 하나로 끝난다. GraphRAG 추출도 같은 호출부라 함께 해결된다.
3. **범용 조회 차단**을 불변식으로 추가. 초안은 `rejectBundleKey` 를 없애면서 대체를 두지 않아 암호문 유출 경로를 열어두었다.
4. **프로브 보안 요구**를 명시. 평면 교차 폴백은 플랫폼 키 유출이다.
5. **저장 시 검증의 약속을 축소**. `/models` 로는 추론 강도 지원 여부를 알 수 없고, "없는 모델이면 400"은 자유 입력 상태와 모순됐다.
6. **`인증 확인` 유지**. 초안은 opencode 아닌 유형의 유일한 검증 수단을 말없이 없앴다.
7. 라디오 전환은 **폼 상태**, 플랫폼 무설정 상태 추가, 모델 접두사는 서버 한 곳, 유형 전환 시 비밀 폐기 경고, 배지 어휘 정렬, 과금 문구 수정, `tenantOwned` 는 플랫폼 응답에서 제외.

## 참조

- 참조 구현: `~/git/iacloud_eis` — `apps/eis-ai-agent/src/agent/opencode-config.ts`, `opencode-probe.ts`, `apps/eis-web/src/features/ai/AiSettingsPage.tsx`, `opencode-presets.ts`
- 1단계 설계: `docs/superpowers/specs/2026-09-18-tenant-scoped-ai-settings-design.md`
- 3단계 설계: `docs/superpowers/specs/2026-09-18-ai-classify-model-selection-design.md`
- 화면 목업: `.superpowers/brainstorm/23724-1789773261/content/` (git 추적 안 함)
