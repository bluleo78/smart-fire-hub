# AI 분류(AI_CLASSIFY) 전용 공급자 — 독립 설정 탭 설계

- 이슈: #707 (테넌트별 AI 설정 3단계). 선행: #706(AI 설정 테넌트 전용 전환, 로컬 main `b6745229`)
- 작성일: 2026-09-22 · 상태: 사용자 승인된 설계
- 이 문서는 `2026-09-18-ai-classify-model-selection-design.md` 를 **대체**한다(같은 파일을 개명·재작성).
  옛 문서는 #693·#706 이전 전제(`ai.agent_type`/`ai.api_key` 평면 키, `system_settings` 시드,
  opencode CLI 서브프로세스 provider)로 쓰여 절반이 이미 구현됐거나 무효였다. 아래 "옛 설계와의 차이" 참고.

## 목적

`AI_CLASSIFY` 스텝은 짧은 프롬프트 + 구조화 JSON 출력이라 채팅보다 훨씬 단순한데, 지금은 채팅과
**같은 공급자·같은 모델**을 강제로 쓴다. 분류만 싼 경로(예: Claude Haiku, 또는 OpenAI 호환
게이트웨이의 소형 모델)로 내릴 수 있게 해 비용을 줄인다.

## 확정 결정 (2026-09-22, 사용자)

1. **분류는 자기 인증·키·모델을 한 묶음으로 갖는다.** 설정 화면에 **"AI 분류" 독립 탭**(임베딩 탭과 같은 급).
2. **미설정이면 채팅(AI 에이전트 탭) 설정을 통째로 쓴다.** 현행과 바이트 단위 동일. 롤백 = "설정 해제".
3. **설정했다면 분류는 분류 묶음만 쓴다.** 채팅 설정과 칸 단위로 섞지 않는다.
   - 인증을 넣으면 **모델 필수**(서버에서 거부). 모델만 있고 인증 없는 상태는 존재할 수 없다.
4. **테넌트 전용**(`ai.credential` 과 같은 등급, 권한 `ai:settings`). 플랫폼 평면 없음(#706).
5. **추론 캐시 분할은 분류 전용을 설정한 테넌트에만** 적용한다(아래 §캐시).
6. **GraphRAG 는 채팅 공급자를 유지한다** — 추가 작업 없음. GraphRAG 는 채팅 요청의 자격증명을
   MCP 자식 경유로 받고, 분류 자격증명은 `/agent/classify` 바디로만 흐른다.
7. 해제 버튼 용어는 **"설정 해제"**("비우기"·"재정의 해제"·"오버라이드" 쓰지 않음).

## 비목표

- ai-agent 변경 — `/agent/classify` 는 이미 `agentType/apiKey/oauthToken/baseUrl/providerId/reasoningEffort/model` 을 받는다.
- 채팅 자격증명(`ai.credential`)의 삭제 API — 이번 범위 아님.
- 모델 교체 시 기존 분류 결과 재계산 — 캐시 키만 갈린다. 이미 적재된 결과 컬럼은 건드리지 않는다.
- 분류 전용 미설정 테넌트의 "ai.model 을 바꿔도 캐시가 안 갈리는" 기존 동작 수정(결정 5의 대가, 의도적 유지).

## 저장 구조

`tenant_settings` 에 키 두 개. **마이그레이션 없음**(key-value 행 추가일 뿐).

| 키 | 값 | 소유 |
|---|---|---|
| `ai.classify_credential` | `ai.credential` 과 **같은 문서 구조** `{v, agentType, payload, secret}` (secret 값별 AES-GCM) | `AiCredentialService` |
| `ai.classify_model` | 모델 문자열 (opencode 면 `providerId/model`) | `AiCredentialService` |

- 모델을 자격증명 문서에 넣지 않는 이유: `AiCredential` 은 "모델은 여기 없다"가 설계 불변식이다
  (채팅도 `ai.model` 이 별도 키). 같은 문서 구조를 유지해야 암호화·마스킹·SSRF 가드·probe·
  `AiCredentialSwitchGuardTest` 를 그대로 물려받는다.
- **두 키는 반드시 함께 쓰고 함께 지운다** — 한 서비스 메서드, 한 트랜잭션
  (`TenantSettingsRepository` 는 클래스 레벨 `@Transactional`).
- `SettingsOverridePolicy.planeOf()` 에 **두 키 모두 `EXTERNAL_OWNER`** 로 등록한다.
  안 하면 `ai.classify_model` 이 "그 밖의 `ai.*` → TENANT_ONLY" 규칙에 걸려 범용 `PUT /settings`
  로 단독 쓰기가 가능해져 묶음 불변식이 깨진다. `AiBehaviorDefaults` 에는 **넣지 않는다**
  (기본값이 없다 — 없음이 곧 "채팅 설정 사용"). 범용 `GET /settings?prefix=ai` 는 EXTERNAL_OWNER 를
  이미 제외하므로 채팅 경로(`getAsMap("ai")`)에 새 키가 새어 들어가지 않는다.

## 구성요소 — api

### 1. `AiCredentialService` 슬롯화

하드코딩된 `KEY = "ai.credential"` 를 슬롯으로 연다.

```java
enum AiCredentialSlot { CHAT("ai.credential"), CLASSIFY("ai.classify_credential") }
```

- `resolve(slot)`, `read(slot)`, `save(slot, upsert, userId)`, `tenantOpencodeCredential(slot)` 로 일반화.
  기존 무인자 메서드는 `CHAT` 위임으로 남겨 채팅·proactive·probe 호출처를 건드리지 않는다.
- 분류 묶음 전용 메서드:
  - `readClassify()` → `AiClassifyView(configured, credential: AiCredentialView, model)`
  - `saveClassify(upsert, model, userId)` — 모델 공백이면 400. 자격증명 병합 규칙(생략 secret=유지,
    `""`=삭제, agentType 변경 시 새 문서)은 채팅과 동일. opencode 면 `OpencodeCredentialValidation`
    (공급자 일관성·모델 소속)을 채팅 PUT 과 같은 방식으로 적용. 두 키 upsert 를 한 트랜잭션에서.
  - `clearClassify()` — 두 키 delete. 멱등(없어도 204).
- `KEY` 상수 참조처(`SettingsOverridePolicy`, `SettingsService` javadoc)는 슬롯 enum 으로 옮긴다.

### 2. `AiClassifyTargetResolver` (신규, 단일 해석 지점)

분류 요청 바디와 캐시 해시가 **같은 답**을 쓰도록 해석을 한 곳에 모은다.

```java
record AiClassifyTarget(boolean dedicated, AiCredential credential, String model) {
  /** dedicated 일 때만 캐시 해시에 섞을 식별자 — 비밀값은 절대 포함하지 않는다 */
  Optional<String> cacheDiscriminator();   // agentType | providerId | baseUrl | model
}
AiClassifyTarget resolve();
```

- 분류 슬롯 행이 있으면 `dedicated=true`, 분류 자격증명 + `ai.classify_model`.
  행이 있는데 모델이 없으면(묶음 불변식 위반 — 수동 DB 조작 등) **fail-closed**(예외, 채팅으로 폴백하지 않음).
- 없으면 `dedicated=false`, 채팅 자격증명 + `settingsService.getValue("ai.model")` — 현행 `buildClassifyBody` 와 동일.
- 알 수 없는 agentType → 기존과 같이 `UnknownAgentTypeException`.

### 3. `AiAgentClient.buildClassifyBody`

`settingsService.getValue("ai.model")` + `aiCredentialService.resolve()` 두 줄을 `resolver.resolve()` 로
교체. 이후 `requireComplete()` → `applyTo(body)` → `requireModelUsable(model)` 순서는 그대로
(opencode 형식 위반은 호출 전 실패).

### 4. `AiClassifyExecutor` — 캐시 (결정 5)

- `buildPromptHash(config)` → `buildPromptHash(config, target)`.
  - `dedicated=false`: 입력 문자열 **현행과 완전히 동일** → 해시 동일 → 기존 캐시 전부 히트.
  - `dedicated=true`: `prompt + JSON(outputColumns) + "\u001f" + cacheDiscriminator` 로 해시.
- `promptHash` 는 이미 `rowContentHash` 의 입력이자 `prompt_version` 컬럼 값이므로 한 곳만 고치면
  두 키가 같이 갈린다. `rowContentHash` 시그니처·테스트는 그대로.
- 결과: 분류 전용 설정/모델 교체 → 해당 테넌트만 1회 캐시 미스. **설정 해제 → 옛(채팅) 해시로 돌아가
  옛 캐시가 다시 히트한다**(의도된 동작 — 되돌림이 공짜).
- 해시는 스텝 실행 시작 시 1회 해석한 `target` 으로 계산하고, 같은 `target` 을 요청에도 쓰도록
  실행 단위로 넘긴다(실행 도중 설정이 바뀌어 해시와 요청이 어긋나는 것을 막는다).

### 5. `AiClassifyCredentialController` (신규)

기존 `AiCredentialController`(`/api/v1/settings/ai-credential`, `/probe` 가 하위 경로라 `/{slot}` 을
끼울 수 없음)는 **건드리지 않는다**. 별도 베이스 `/api/v1/settings/ai-classify-credential`,
전부 `@RequirePermission("ai:settings")`:

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | `` | `{configured, agentType, payload, secretFieldNames, model}` (미설정이면 `configured:false`) |
| PUT | `` | `{agentType, payload, secret, model}` → 204. opencode 면 SSRF 가드 항상 실행 |
| DELETE | `` | 설정 해제 → 204 (멱등) |
| POST | `/probe` | 모델 목록 — 저장된 키 재사용은 **분류 슬롯의** 키에서만 |

- probe 의 저장 키 재사용(`resolveApiKey`)은 슬롯을 받아야 한다 — 채팅 키로 분류 게이트웨이를
  찌르거나 그 반대가 되면 안 된다(baseURL 일치 검사와 별개로).
- 감사 로그: 채팅 자격증명 경로에도 없다 — 이번 범위에서 추가하지 않는다(채팅과 동등).

## 구성요소 — web

### 6. "AI 분류" 탭 (`SettingsPage.tsx`)

탭 순서: 일반 | AI 에이전트 | **AI 분류** | 이메일 | 임베딩. 카드 제목 "분류 모델 설정".

- **미설정 상태**: 정보 배너 "AI 에이전트 설정을 사용 중입니다." + 현재 채팅의 에이전트 유형·모델 표시
  (기존 `GET /settings/ai-credential`, `GET /settings?prefix=ai` 재사용) + **"분류 전용 설정하기"** 버튼.
  누르면 빈 폼이 펼쳐진다(저장 전까지는 서버 상태 불변).
- **설정 상태/편집 중**: `AiCredentialFieldset` 재사용(설명 문구만 "AI 분류에 사용할 에이전트 유형") +
  **모델(필수)** 칸 — opencode 면 `OpencodeModelField`(모델 불러오기 = 분류 probe), 그 외엔 Claude 모델
  Select. 하단 좌측 **"분류 전용 설정 해제"**(설정된 상태에서만), 우측 **"저장"**.
- 해제 확인창: 제목 "분류 전용 설정 해제", 본문 "분류 전용 인증·키·모델이 삭제되고, AI 분류는 AI 에이전트
  설정으로 실행됩니다. 저장된 API 키는 복구할 수 없습니다."
- 목업: `.superpowers/brainstorm/75687-1790066980/content/classify-tab-v2.html` (커밋하지 않음).

### 7. 재사용을 위한 최소 변경

- `useAiCredentialForm.ts` — API 모듈(엔드포인트 묶음)을 인자로 받게 한다. 기본값은 채팅 API
  → 기존 호출처·E2E 무변경.
- `AiCredentialFieldset` — `idPrefix` prop 추가(기본 `ai-cred`). 두 인스턴스가 같은 DOM id
  (`ai-cred-oauth-token` 등)를 만들지 않게. 설명 문구 prop 1개.
- `api/settings.ts` — `aiClassifyCredentialApi` (get/put/delete/probe) + 타입.
- admin 앱: 변경 없음(카탈로그 테스트가 이미 `ai.*` 부재를 단언).

## 오류 처리

| 상황 | 결과 |
|---|---|
| PUT 에 모델 없음 / 공백 | 400 "분류 모델을 선택하세요" |
| PUT opencode: 모델 공급자 접두어 ≠ providerId, 목록에 없음 | 400 (채팅 PUT 과 같은 검증기) |
| PUT opencode: 사설/비 https URL | 400 (SSRF 가드) |
| 분류 행은 있는데 모델 행 없음(불변식 위반) | 스텝 실행 실패, 채팅으로 폴백하지 않음 |
| 분류 자격증명 불완전(필수 secret 없음) | 기존 `requireComplete()` 와 같은 실패 — 스텝 오류 메시지 |
| 미설정 + 채팅 자격증명 없음 | 현행과 동일 |

## 테스트

**api (JUnit)**
- `AiCredentialServiceTest` — 슬롯 분리: 분류 저장/해제가 `ai.credential` 을 건드리지 않고 그 역도 성립.
  `saveClassify` 모델 공백 거부, 두 키 원자 저장/삭제, 해제 멱등.
- `AiClassifyTargetResolverTest` — 미설정 → 채팅 자격증명+`ai.model`; 설정 → 분류 묶음;
  행만 있고 모델 없음 → 예외; discriminator 에 비밀값 없음.
- `AiAgentClientTest` — 분류 설정 시 바디의 agentType/baseUrl/model 이 분류 슬롯 값; opencode 형식
  위반 시 HTTP 호출 전 실패.
- `AiClassifyExecutorTest` — 미설정이면 promptHash 가 **현행 고정값과 동일**(회귀 가드: 기존 해시
  리터럴 단언); 설정 시 모델만 달라도 해시가 갈린다; 해제 후 원래 해시로 복귀.
- `AiClassifyCredentialControllerTest` — 4라우트, 권한 없음 403, probe 가 채팅 키를 재사용하지 않음.
- 설정 키집합 불변식 3종(`SettingsKeyWhitelistInvariantTest`) + `SettingsOverridePolicyTest` —
  두 새 키가 EXTERNAL_OWNER, 범용 `PUT /settings` 로 `ai.classify_model` 쓰기 거부.
- 변이 확인: resolver 의 dedicated 분기를 뒤집었을 때 위 테스트가 실제로 실패하는지 1회 확인.

**web (Playwright, 신규 `e2e/pages/admin/ai-classify-settings.spec.ts`)**
- 미설정: 배너에 채팅 유형·모델 표시 (`@smoke`).
- 설정하기 → sdk + 토큰 + Haiku 저장 → PUT payload 단언(`model` 포함) → 설정 상태 렌더.
- opencode: 모델 불러오기가 **분류** probe 엔드포인트로 가는지, 모델 필수 검증.
- 설정 해제: 확인창 → DELETE 호출 → 미설정 배너로 복귀.
- 서버 400 메시지 표시.
- 기존 `tenant-ai-settings.spec.ts` 는 수정하지 않고 그대로 통과해야 한다(재사용 리팩터링 회귀 가드).

## 배포·롤백

- 마이그레이션 없음 → 번호 충돌 위험 없음. 배포는 api+web 동시(web 이 새 엔드포인트를 부른다).
- 배포 직후 모든 테넌트는 미설정 → 동작·캐시 모두 현행과 동일.
- 롤백: 테넌트 단위는 "설정 해제". 코드 롤백 시 남은 `ai.classify_*` 행은 옛 코드가 읽지 않으므로 무해.

## 옛 설계(2026-09-18)와의 차이

| 옛 문서 | 현재 |
|---|---|
| 결정 A: completion 슬롯을 agentType 으로 분기, `OpenCodeCompletionProvider`(CLI) 신설 | 이미 구현됨 — `createCompletionProvider` 가 opencode → `OpenAICompatCompletionProvider`(HTTP) |
| agentType·credential 을 classify 까지 전달 | 이미 구현됨 (`classify.ts` zod, `AiCredential.applyTo`) |
| "opencode 면 자격증명 미주입" | 무효 — opencode 가 테넌트 apiKey/baseUrl 을 들고 다닌다 |
| 결정 B: `ai.classify_model` 하나, 비면 모델 미지정(백엔드 기본값) | 모델만이 아니라 **공급자까지** 분류 묶음으로. 비면 채팅 설정 통째로 |
| 결정 C: 캐시 키에 항상 `agentType+classify_model` (rowContentHash) | 분류 전용 설정 테넌트만, `buildPromptHash` 에 섞는다 |
| `ai.agent_type`/`ai.api_key` 평면 키, `system_settings` 시드 | #706 으로 제거됨 — 테넌트 전용 |
