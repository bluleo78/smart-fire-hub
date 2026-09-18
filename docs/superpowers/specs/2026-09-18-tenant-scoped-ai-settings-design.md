# 테넌트별 AI·임베딩 설정 설계

- 작성일: 2026-09-18
- 대상 앱: `apps/firehub-api` (설정·임베딩·검색), `apps/firehub-web` (설정 화면)
- 관련: [2026-09-18 AI_CLASSIFY 분류 전용 모델 선택 설계](2026-09-18-ai-classify-model-selection-design.md)

## 배경

"테넌트별로 어떤 AI를 쓰는가"가 제품의 핵심 요구인데, 현재 그것을 결정하는 키가 전부 플랫폼 잠금이다.

`SettingsOverridePolicy.TENANT_OVERRIDABLE`에 없는 7키:

| 키 | 현행 사유(코드 주석) |
|---|---|
| `ai.api_key`, `ai.cli_oauth_token`, `ai.agent_type` | 과금 주체와 실행 형태라 **BYO 키 정책이 정해질 때까지** 플랫폼이 갖는다 |
| `embedding.provider`, `embedding.model`, `embedding.base_url`, `embedding.api_key` | 모델 변경이 벡터 차원을 바꿔 기존 임베딩 전량을 무효화한다 |

앞의 3키는 스스로 한시적 보류라고 적고 있고, SMTP 6키가 P7-c1(2026-08-22)에 같은 방식으로 재분류된 전례가 있다. 뒤의 4키는 사유가 실제 제약(스키마의 `vector(1024)`)이므로 그 제약을 먼저 풀어야 한다.

### 이미 되어 있는 것

**해석 배선은 전부 테넌트를 인지한다.** `getValue()`는 테넌트 오버라이드 → 플랫폼 기본값 순으로 해석하고(`SettingsResolutionTest`), `tenant_settings`에 RLS가 걸려 있으며(`TenantSettingsRlsTest`), 테넌트 쓰기 평면도 플랫폼 평면과 **같은 순서로** 검증·센티널·암호화를 지난다(`encryptSecrets`, `dropMaskSentinels`).

자격증명 접근자도 같은 경로다:

```java
public Optional<String> getDecryptedApiKey() {
  return getValue("ai.api_key").filter(v -> !v.isBlank()).map(encryptionService::decrypt);
}
```

`EmbeddingProviderFactory`도 `settingsService.getValue("embedding.*")`로 읽는다. **즉 화이트리스트에 키를 넣는 것만으로 테넌트별 해석이 동작한다.**

**비동기 경로의 테넌트 컨텍스트도 이미 선다.** 프로액티브 스케줄러는 `TenantScopedRunner`, `@Async`·파이프라인은 `TenantContextTaskDecorator`로 승계받는다.

**차원 검증도 이미 있다.** `OpenAiEmbeddingProvider`·`OllamaEmbeddingProvider` 모두 응답 차원이 기대와 다르면 `EmbeddingException`을 던진다. OpenAI는 `dimensions` 파라미터로 축소를 강제한다.

### 막고 있는 것

1. `SettingsOverridePolicy.TENANT_OVERRIDABLE` 화이트리스트에 7키가 없다.
2. `dataset_embedding.embedding` / `document_chunk.embedding`이 **`vector(1024)` 고정**이고 각각 HNSW 인덱스가 걸려 있다. 두 테이블은 `tenant_id` NOT NULL + RLS로 **행은 격리되지만 컬럼 타입은 공유**된다 — 컬럼 타입은 테이블의 속성이라 테넌트별로 다를 수 없다.

## 목표

1. 테넌트가 자기 AI(실행 형태·자격증명)와 자기 임베딩(provider·모델·엔드포인트·키)을 고를 수 있다.
2. 테넌트마다 임베딩 차원이 달라도 된다.
3. 미설정 테넌트는 플랫폼 기본값으로 폴백해 현행과 동일하게 동작한다.

## 비목표

- **테넌트별 opencode provider** — `ai.agent_type`을 열어도 opencode는 여전히 전 테넌트가 같은 사내 엔드포인트를 공유한다. provider(URL·키·모델)가 PVC 전역 `opencode.jsonc`에 있기 때문이다. 별도 과제(②).
- **임베딩 테이블의 테넌트 스키마 이전** — 완전 분리는 이 설계의 다음 단계다(아래 "장래").
- **임의 차원 지원** — 지원 차원은 열거한다(아래 결정 B).

## 구현 단계

두 단계는 독립적으로 배포 가능하다. A는 스키마 변경이 없어 먼저 낸다.

- **Phase A — AI 3키** (`ai.api_key`, `ai.cli_oauth_token`, `ai.agent_type`)
- **Phase B — 임베딩 4키 + 차원별 컬럼**

## 아키텍처 결정

### 결정 A: 오버라이드를 열되 플랫폼 기본값은 남긴다

"AI 설정을 테넌트 전용으로(플랫폼 평면 제거)" 대신 **오버라이드 허용**을 택한다. `getValue()`가 `테넌트 → 플랫폼` 순이고 자격증명은 `filter(v -> !v.isBlank())`로 빈 값을 거르므로, **플랫폼 값을 비워두면 "테넌트 전용"과 동작이 같다.** 반대는 성립하지 않는다 — 코드를 테넌트 전용으로 좁히면 플랫폼 공통 기본값이라는 선택지가 사라진다.

따라서 "플랫폼이 기본 키를 대주느냐"는 **코드가 아니라 운영 값으로 결정**된다. BYO 키 정책이 확정되면 값만 비우면 된다.

### 결정 B: 차원별 컬럼 + 부분 HNSW 인덱스

한 테이블에 차원별 nullable 컬럼을 두고 각 컬럼에 부분 인덱스를 건다. 행마다 정확히 하나만 채워진다.

**지원 차원: 1024, 1536.** 384·768은 제외한다(소형 모델은 지원 대상이 아니다).

| 차원 | 대표 모델 |
|---|---|
| 1024 | `bge-m3`(현 기본값), `multilingual-e5-large`, Cohere embed v3, OpenAI `text-embedding-3-*` 축소 |
| 1536 | OpenAI `text-embedding-3-small`(native), `text-embedding-ada-002`(1536 고정 — 현재는 쓸 수 없다고 주석에 명시된 모델이 열린다) |

**기존 컬럼은 RENAME으로 보존한다.** 복사·백필이 아니다:

```sql
ALTER TABLE dataset_embedding RENAME COLUMN embedding TO embedding_1024;
ALTER TABLE dataset_embedding ADD COLUMN embedding_1536 vector(1536);
ALTER TABLE dataset_embedding ADD COLUMN embedding_dim SMALLINT;
UPDATE dataset_embedding SET embedding_dim = 1024 WHERE embedding_1024 IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_dataset_embedding_vector_1536
  ON dataset_embedding USING hnsw (embedding_1536 vector_cosine_ops)
  WHERE embedding_1536 IS NOT NULL;
```

기존 데이터와 기존 HNSW 인덱스(`idx_dataset_embedding_vector`)가 그대로 살아남는다 — 다운타임도 백필도 없다. `document_chunk`도 동일하게 처리한다.

**런타임 DDL은 하지 않는다.** 새 차원이 필요하면 Flyway 마이그레이션 1건(컬럼 + 부분 인덱스)이다. 공유 RLS 테이블에 런타임 `ALTER TABLE`을 걸면 전 테넌트를 막는 배타 락이 잡힌다.

**상한: pgvector HNSW는 2000차원까지다.** `text-embedding-3-large`의 native 3072는 인덱싱할 수 없고 `dimensions`로 축소해야만 쓸 수 있다. 프로브는 이 경우를 **일반 차원 불일치와 구분해** 그 이유를 말해야 한다.

### 결정 C: 설정 저장 시점 프로브를 정합성의 축으로 삼는다

지금은 컬럼이 하나라 잘못된 모델이 임베딩 시점에 `EmbeddingException`으로 터진다. 차원별 컬럼이 되면 **잘못된 모델도 자기 차원 컬럼에 성공적으로 들어가고, 대신 검색이 조용히 망가진다.** 그래서 프로브가 필수가 된다.

`embedding.*`를 저장할 때:
1. 그 설정으로 `EmbeddingProviderFactory`를 만들어 시험 임베딩을 1회 호출한다.
2. 실측 차원이 지원 목록(1024/1536)에 없으면 **저장 자체를 거부**한다. 2000 초과면 HNSW 상한을 이유로 명시한다.
3. 통과하면 실측 차원을 **파생 설정 키 `embedding.dimension`**에 같은 평면(테넌트 또는 플랫폼)으로 기록한다.

`embedding.dimension`은 **프로브만 쓰는 읽기 전용 파생값**이다. 사용자 입력을 받지 않는다 — 설정 화면에는 현재 값만 표시하고 편집 필드를 두지 않으며, `PUT /settings` 요청에 이 키가 오면 거부한다. 이 키가 필요한 이유는 읽기 경로 때문이다: 검색 쿼리가 어느 차원 컬럼을 볼지 정하려면 테넌트의 실효 차원을 알아야 하는데, 매 검색마다 프로브를 호출할 수는 없다. 모델 이름에서 차원을 유추하는 하드코딩 표는 두지 않는다 — 같은 이름의 모델이 엔드포인트마다 다른 차원을 낼 수 있고, 그것이 애초에 프로브를 두는 이유다.

### 결정 D: 설정 변경 시 재임베딩을 걸고 진행 상황을 노출한다

차원이 바뀌면 기존 행은 옛 컬럼에, 쿼리는 새 컬럼을 본다. **결과가 0건이 된다** — 품질 저하가 아니다. 조용한 0건은 장애로 오인되므로:

- 설정 저장 시 그 테넌트의 재임베딩을 즉시 큐에 넣는다. 부품은 이미 있다 — `DocumentChunkReembedService.reembedDataset(datasetId, tenantId)`(이미 `tenantId`를 받는다), `.reembedAll()`, `DatasetEmbeddingBackfillService.backfillAll()`.
- `EmbeddingStatusService.status()`에 **"재임베딩 진행 중, N건 남음"**을 노출한다. 행별 `embedding_model` + 새 `embedding_dim`으로 stale 판정이 가능하다.

**읽기 경로는 fail-closed다.** 테넌트 설정 차원이 지원 목록에 없거나 프로브를 거치지 않은 상태면, 빈 컬럼을 스캔해 0건을 돌려주지 말고 오류를 낸다.

### 결정 E: 자격증명 번들 — 섞이면 과금 주체가 샌다

**SMTP 밴드(P7-c1)가 이미 닫은 유출과 같은 형태가 AI·임베딩에도 있다.** 그쪽 코드 주석의 표현을 그대로 빌리면 *"테넌트가 지정한 호스트 + 플랫폼 자격증명"*이고, 여기서는 **"테넌트가 고른 실행 형태 + 플랫폼 키"**다.

구체적 시나리오: 테넌트 ADMIN이 `ai.agent_type`만 `sdk`로 바꿔 저장한다. API 키 필드는 화면에 마스킹된 채 채워져 보이므로(플랫폼 값이 폼에 시드된다) 손댈 이유가 없고, web은 **바뀐 키만 전송**하므로 `ai.api_key` 테넌트 행이 생기지 않는다. 결과는 테넌트가 고른 실행 형태로 **플랫폼 키가 과금**되는 상태다 — BYO 키 정책이 막으려던 바로 그것이다.

그래서 `applySmtpConnectionBundle`과 같은 규칙을 둔다.

**AI 자격증명 번들** = `ai.api_key`, `ai.cli_oauth_token`, `ai.agent_type`
셋 중 **하나라도** 테넌트 행이 있으면 **셋 전부**를 테넌트 평면에서 해석한다. 행이 없는 키는 채운다:

| 키 | 채움 값 | 이유 |
|---|---|---|
| `ai.api_key` | `""` | 자격증명 — "비어 있음 = 미설정"이고, 미설정은 **눈에 보이게 실패**해야 한다(기존 `missingCredential` 경로가 명확한 오류를 낸다) |
| `ai.cli_oauth_token` | `""` | 동상 |
| `ai.agent_type` | `"sdk"` | 자격증명이 아니라 **실행 형태 선택자**다. 빈 값은 안전하지도 의미 있지도 않다(빈 값이 `cli-api` 분기로 떨어지는 것이 A6이 고치는 결함이다). `sdk`는 API 키·OAuth 어느 쪽으로도 인증되므로 테넌트가 키만 넣은 경우에 맞고, 기존 코드 기본값(`getOrDefault("ai.agent_type","sdk")`)과도 일치한다 |

`agent_type=opencode`만 저장하는 경우도 올바르게 동작한다 — 두 자격증명이 `""`로 채워지고, **채팅 경로**는 opencode 로 라우팅되므로 배포 측 인증을 쓴다.

> **주의(2026-09-18 정정).** 이 문장을 "그러므로 opencode 테넌트에게는 앱 키를 보낼 필요가 없다"로
> 일반화하면 안 된다. **분류(AI_CLASSIFY) 경로에는 적용되지 않는다** — `provider-factory.ts` 의
> `createCompletionProvider` 는 agentType 분기 없이 SDK 경로로 통일하므로, 분류는 `agent_type` 이
> 무엇이든 Claude SDK 로 나간다. 거기서 키를 withhold 하면 ai-agent 컨테이너의 ambient 자격증명으로
> 떨어져 **이 설계가 막으려는 과금 혼입이 그대로 재현된다.** 실제로 구현 중 그 가드가 들어갔다가
> 브랜치 코드 리뷰에서 Major 로 잡혀 `6b1c6383` 에서 제거됐다.

**임베딩 번들** = `embedding.provider`, `embedding.model`, `embedding.base_url`, `embedding.api_key`
같은 규칙. 채움 값은 **전부 `""`**다 — `EmbeddingProviderFactory.settingValue()`가 `.filter(v -> !v.isBlank()).orElse(default)`이므로 빈 값은 **플랫폼 값이 아니라 코드 기본값**(Ollama 로컬)으로 떨어진다. 즉 "테넌트 엔드포인트 + 플랫폼 OpenAI 키" 조합이 생기지 않는다.

`embedding.dimension`(결정 C의 파생 키)은 번들에 넣지 않는다 — 프로브가 임베딩 설정을 저장할 때 같은 평면에 함께 쓰므로 자연히 따라간다.

**번들 키는 전부 `TENANT_OVERRIDABLE`에 있어야 한다.** 하나라도 플랫폼으로 회수하면 채움이 그 키를 되살려 `overridden=true` + `tenantEditable=false`라는 모순 조합이 나오고, web이 fail-closed로 읽어 그룹 전체를 조용히 잠근다. SMTP가 이 규칙을 `연결_번들_5키는_전부_테넌트_오버라이드_허용키다` 테스트로 고정했으므로, AI·임베딩 번들도 같은 형태의 테스트를 둔다.

## 구성요소

### Phase A

| # | 변경 | 위치 |
|---|---|---|
| A1 | `ai.api_key`, `ai.cli_oauth_token`, `ai.agent_type` 추가 | `SettingsOverridePolicy.TENANT_OVERRIDABLE` (12 → 15키) |
| A2 | 프론트 사본에도 동일 추가 | `settings-fields.ts:TENANT_EDITABLE_KEYS` |
| A3 | 자격증명 필드를 테넌트 평면에 노출 | `SettingsPage.tsx` |
| A4 | 낡은 주석 정정 | `SettingsService` javadoc(§30-33), `SettingsOverridePolicy` 클래스 javadoc("여기 없는 7키" → 4키) |
| A5 | **낡은 가정 정정 (중요)** | `ProactiveJobAsyncRunner:123`, `AiController:90-92` — 두 곳 모두 *"그 키는 플랫폼 잠금이라 테넌트 오버라이드 해석이 원리적으로 결과를 바꾸지 않는다"*고 적혀 있다. 이 변경이 그 문장을 거짓으로 만든다. 동작은 테넌트 컨텍스트가 이미 서 있어 옳게 바뀌지만, `/auth-status`는 **호출자의 테넌트 기준 검증**으로 의미가 바뀌므로 명시한다 |
| A6 | **선재 결함 수정** | `AiAgentProxyService:172` — `getOrDefault("ai.agent_type","sdk")`는 값이 **빈 문자열**이면 `""`를 돌려주어 `sdk` 폴백으로 가지 않고 `cli-api` 분기로 떨어진다. `isBlank() → "sdk"` 정규화 |
| A7 | **AI 자격증명 번들** (결정 E) | `SettingsService` — `AI_CREDENTIAL_KEYS` 상수 + `applyAiCredentialBundle()`, `resolveOverridesByPrefix`에서 `applySmtpConnectionBundle` 옆에 호출. `BUNDLE_FILL_VALUES`에 `ai.agent_type → "sdk"` 추가 |

### Phase B

| # | 변경 | 위치 |
|---|---|---|
| B1 | 마이그레이션 — RENAME + 1536 컬럼 + `embedding_dim` + 부분 인덱스 | 신규 Flyway (두 테이블) |
| B2 | `embedding.*` 4키 추가 | `SettingsOverridePolicy`, `settings-fields.ts` (15 → 19키) |
| B3 | 차원별 컬럼 선택 (쓰기) | `DatasetEmbeddingRepository`, `DocumentIngestionService` |
| B4 | 차원별 컬럼 선택 (읽기) | `DatasetSearchRepository:37,48`, `DocumentChunkRepository:80,92` — `<=>`를 쓰는 곳은 이 두 파일 네 줄뿐이고 이미 `sql.append` 조립 방식이라 컬럼명 주입이 자연스럽다 |
| B5 | 저장 시점 프로브 + `embedding.dimension` 파생 키 | `SettingsService` 쓰기 경로 + `EmbeddingProviderFactory`. 새 키는 `ALLOWED_EMBEDDING_KEYS`와 `TENANT_OVERRIDABLE`에 넣되(불변식 유지) 쓰기 API에서는 거부한다 |
| B6 | 테넌트 재임베딩 트리거 + 진행 노출 | `EmbeddingStatusService`, `DocumentChunkReembedService`, `DatasetEmbeddingBackfillService` |
| B7 | `EmbeddingProviderFactory.DIMENSION` 상수 제거 | 지금은 `private static final int DIMENSION = 1024` 배포 단위 고정. 테넌트 설정에서 해석하도록 변경 |
| B8 | **임베딩 번들** (결정 E) | `SettingsService` — `EMBEDDING_CONNECTION_KEYS` 상수 + `applyEmbeddingBundle()`. 채움 값은 전부 `""` |

## 오류 처리

| 상황 | 동작 |
|---|---|
| 테넌트가 지원 목록 밖 차원 모델 저장 | 프로브가 저장 거부. "이 모델은 N차원입니다. 지원 차원: 1024, 1536" |
| 2000 초과 차원 | 저장 거부 + HNSW 상한이 이유임을 명시 (축소 파라미터 안내) |
| 재임베딩 진행 중 검색 | 0건이 나올 수 있음 — 상태 엔드포인트가 "진행 중 N건 남음"을 함께 노출 |
| 설정 차원이 지원 목록 밖인데 읽기 경로 진입 | fail-closed 오류 (빈 컬럼 스캔 후 0건 반환 금지) |
| 테넌트 자격증명 없음 | 플랫폼 값으로 폴백. 둘 다 없으면 기존 `missingCredential` 경로 |

## 테스트

- 테넌트 A/B가 서로 다른 `ai.agent_type`·자격증명으로 해석되는지 (`SettingsResolutionTest` 확장)
- 빈 `ai.agent_type` → `sdk` 정규화 (`AiAgentProxyServiceTest`)
- 프로액티브·파이프라인 비동기 경로에서 테넌트별 자격증명이 실제로 갈리는지
- 화이트리스트 불변식 (`SettingsKeyWhitelistInvariantTest` — 테넌트 허용 ⊆ 플랫폼 쓰기 가능; 7키 모두 `ALLOWED_*`에 이미 있으므로 불변식은 유지된다)
- **번들 불변식** — `AI_CREDENTIAL_KEYS`·`EMBEDDING_CONNECTION_KEYS`가 전부 `tenantOverridableKeys()`에 있는지 (SMTP의 `연결_번들_5키는_...` 테스트와 동형)
- **번들 원자성** — 테넌트가 `ai.agent_type`만 저장했을 때 `ai.api_key`가 **플랫폼 값이 아니라 빈 값**으로 해석되는지. 이 테스트가 결정 E가 막는 과금 유출을 고정한다
- 테넌트가 `ai.api_key`만 저장 → `ai.agent_type`이 `"sdk"`로 채워지는지
- 차원별 컬럼 라우팅 — 1024 테넌트와 1536 테넌트가 각자 자기 컬럼으로 쓰고 읽는지
- 마이그레이션 — RENAME 후 기존 1024 데이터·인덱스가 보존되고 검색이 그대로 동작하는지
- 프로브 — 지원 목록 밖 차원 거부, 2000 초과 시 별도 메시지
- 재임베딩 — 차원 변경 후 stale 판정과 진행 카운트
- E2E: 테넌트 관리자가 자기 API 키·임베딩 설정을 저장하고 마스킹이 동작하는지

## 마이그레이션·롤백

- Phase A는 화이트리스트 편집이라 DB 변경이 없다. 롤백은 코드 되돌리기.
- Phase B의 RENAME은 기존 데이터·인덱스를 보존한다. 되돌리려면 역 RENAME 1건.
- 두 Phase 모두 **테넌트가 값을 넣기 전까지 동작이 바뀌지 않는다** — 플랫폼 기본값 폴백이 현행과 동일하다.

## 장래: 완전 분리

이 설계는 공유 테이블에 차원별 컬럼을 두는 절충이다. 최종 형태는 임베딩 테이블을 **테넌트 스키마로 옮기는 것**이고(`TenantSchemaProvisioner`·`DataSchema` 인프라가 이미 있다), 그러면 테넌트마다 `embedding vector(N)` 단일 컬럼과 온전한 HNSW 인덱스를 갖는다. 차원 열거도 사라진다.

**이 설계가 그 길을 막지 않고 오히려 돕는다** — 행마다 `embedding_dim`이 기록되고 테넌트별로 차원이 하나로 수렴하므로, 이전 시점에 그 테넌트의 non-null 컬럼 하나만 단일 컬럼으로 복사하면 된다. 지금처럼 차원 정보 없이 섞여 있는 상태보다 분리가 쉽다.

## 검증 항목 (구현 후 실측)

1. RENAME 후 기존 HNSW 인덱스가 실제로 계속 쓰이는지 (`EXPLAIN`으로 인덱스 스캔 확인)
2. 부분 인덱스(`WHERE col IS NOT NULL`)가 HNSW에서 기대대로 선택되는지
3. 테넌트별 자격증명이 프로액티브·파이프라인 배경 잡에서 실제로 갈리는지 (컨텍스트 승계 실측)
4. 1536 모델(예: `text-embedding-3-small` native)로 저장 → 프로브 통과 → 검색까지 한 사이클
