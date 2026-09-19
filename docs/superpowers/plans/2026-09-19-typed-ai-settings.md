# 테넌트별 AI 설정 2단계 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 자격증명을 `ai.agent_type` 판별 유형별 구조로 바꾸고, opencode provider(baseURL·키)를 테넌트별로 가른다.

**Architecture:** 평면 3키(`ai.api_key`/`ai.cli_oauth_token`/`ai.agent_type`)를 `ai.credential` JSON 한 키로 합친다. 비밀은 JSON 하위 필드 단위로 암호화하고 읽을 때는 이름만 내보낸다. 자바 쪽은 sealed interface 로 받아 유형 누락이 컴파일 오류가 되게 한다. 에이전트는 opencode 용 OpenAI 호환 completion 프로바이더를 더해 분류·GraphRAG 추출이 테넌트 자기 공급자에서 돌게 한다. `ai.model` 은 자격증명이 아니므로 평면 키로 남는다.

**Tech Stack:** Spring Boot + jOOQ + Flyway(PostgreSQL, RLS), React + TanStack Query + shadcn/ui, Playwright, Vitest, Node/TypeScript(ai-agent)

**Spec:** `docs/superpowers/specs/2026-09-19-typed-ai-settings-design.md`

**이슈:** [#693](https://github.com/bluleo78/smart-fire-hub/issues/693)

## Global Constraints

- **한국어 주석 필수** — 클래스·메서드·주요 로직에 무엇을·왜. 기존 파일의 주석 밀도를 따른다. 설명 주석을 노이즈로 지우지 않는다.
- **SMTP·임베딩·이메일 탭의 동작과 외관을 바꾸지 않는다.** 예외: Task 11 의 배지 문구 정렬은 AI 탭에 한정한다.
- **`git add -A` 금지** — `apps/firehub-api/src/main/generated` 는 추적되지 않는 심볼릭 링크이고 그대로 두어야 한다. 항상 경로를 명시해 스테이징한다.
- **`spotlessApply` 실행 금지** — 무관한 파일 약 425개를 재포맷한다.
- **의존성 핀·Playwright 버전 변경 금지.**
- **E2E 는 시스템 Chrome 우회로 실행한다.** 핀 고정 브라우저는 이 머신에 설치되지 않는다(`playwright install` 실행 금지). `apps/firehub-web/playwright.config.ts` 의 `chromium` 프로젝트 `use` 블록에 `channel: 'chrome'` 을 임시로 넣고 실행한 뒤 **완전히 원복**하고 `git diff -- apps/firehub-web/playwright.config.ts` 가 비어 있음을 증명한다. 절대 스테이징하지 않는다.
- **`pnpm lint` 는 main 에서 이미 9건 실패한다**(무관한 e2e 스펙의 `no-restricted-syntax`). 건드린 파일만 `npx eslint <paths>` 로 검사하고 exit 0 을 확인한다. 무관한 9건을 고치지 않는다.
- **pre-commit 훅은 워크트리에서 실패한다**(`node_modules` 없음). `--no-verify` 로 커밋하되, 수동으로 돌린 게이트를 커밋 본문에 적는다. 우회할 때는 `apps/firehub-web/e2e/` 에서 변경한 셀렉터·문자열을 grep 해 기존 단언이 여전히 성립하는지 정적 교차검증한다.
- **추가·변경한 테스트마다 뮤테이션 검사**: 대상 동작을 깨뜨려 테스트가 RED 가 되는지 확인하고, 복구해 GREEN 을 확인한다. 무엇을 깨뜨렸고 무엇이 실패했는지 보고한다. 1단계에서 공허한 테스트가 네 번 나왔고 두 건은 리뷰를 통과했다.
- **커밋 메시지 말미:**
  ```
  Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01NpkoVvy52KkDbHhw4C4ayk
  ```
- **백엔드 테스트 전제**: `apps/firehub-api/src/main/generated` 심볼릭 링크가 살아 있어야 하고 Postgres 컨테이너 2개(5432, 5433)가 떠 있어야 한다. `cd apps/firehub-api && ./gradlew test -x generateJooq` 로 돌린다(`-x generateJooq` 를 빼면 jOOQ 가 심볼릭 링크를 지운다). gradlew 는 리포 루트가 아니라 `apps/firehub-api/` 에 있다.
- **`agentType` 값 4종:** `sdk` / `cli` / `cli-api` / `opencode`. 새 값을 만들지 않는다.
- **추론 강도 후보:** `기본값`(빈 값) / `low` / `medium` / `high`. 저장된 값이 목록에 없으면 맨 앞에 끼워 보존한다.

---

## 파일 구조

**신규**

| 파일 | 책임 |
|---|---|
| `apps/firehub-api/.../settings/model/AiCredential.java` | sealed interface + 4 record. 해석된 자격증명의 타입. |
| `apps/firehub-api/.../settings/model/AiCredentialDocument.java` | 저장 형태(`{v, agentType, payload, secret}`)의 (역)직렬화. 모르는 필드 보존. |
| `apps/firehub-api/.../settings/service/AiCredentialService.java` | `ai.credential` 키의 유일한 소유자. 읽기·쓰기·유형별 검증·하위 필드 암호화. |
| `apps/firehub-api/.../settings/service/OpencodeProbeService.java` | `GET {baseURL}/models` 프로브 + SSRF 가드. |
| `apps/firehub-api/.../settings/controller/AiCredentialController.java` | 테넌트 REST 4개. |
| `apps/firehub-api/.../platform/controller/PlatformAiCredentialController.java` | 플랫폼 REST 3개(DELETE 없음). |
| `apps/firehub-api/src/main/resources/db/migration/V122__ai_credential.sql` | 3키 → `ai.credential` 변환. |
| `apps/firehub-ai-agent/src/providers/openai-compat-completion-provider.ts` | `POST {baseURL}/chat/completions`. |
| `apps/firehub-web/src/lib/ai-credential.ts` | 유형별 필드 정의 + 타입. |
| `apps/firehub-web/src/hooks/useAiCredentialForm.ts` | 라디오 상태·비밀 의미·프로브를 쥔 폼 훅. |
| `apps/firehub-admin/src/lib/ai-credential.ts` | 같은 필드 정의(어드민 사본). |
| `apps/firehub-admin/src/pages/settings/AiCredentialSection.tsx` | 어드민 AI 탭 전용 섹션. |

**수정**

| 파일 | 변경 |
|---|---|
| `settings/service/SettingsService.java` | 번들 기계 삭제, `ai.credential` 범용 조회 차단, `getAiCredentials()` → 위임 제거 |
| `settings/service/SettingsOverridePolicy.java` | 3키 제거, `ai.credential` 추가 |
| `ai/service/AiAgentProxyService.java`, `ai/controller/AiController.java`, `pipeline/service/executor/AiAgentClient.java`, `proactive/service/ProactiveJobAsyncRunner.java` | `AiCredential` switch 로 전환 |
| `ai-agent/src/providers/provider-factory.ts`, `types.ts` | `createCompletionProvider` 에 `agentType` 분기 |
| `ai-agent/src/agent/agent-opencode.ts` (+ `.test.ts`) | `provider` 블록 작성, 핀 테스트 뒤집기 |
| `firehub-web/src/pages/admin/SettingsPage.tsx` | AI 탭 재작성, 배지 문구 정렬 |
| `firehub-admin/src/lib/settings-catalog.ts`, `src/pages/SettingsPage.tsx` | AI 탭에서 3키 제거, 전용 섹션 삽입 |

**삭제**: `firehub-web/src/hooks/useAiSettingsForm.ts` (→ `useAiCredentialForm.ts` 로 대체)

---

## Task 1: `AiCredential` 타입과 저장 문서

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/settings/model/AiCredential.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/settings/model/AiCredentialDocument.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/settings/model/AiCredentialDocumentTest.java`

**Interfaces:**
- Produces: `AiCredential`(sealed, 4 record), `AiCredentialDocument.parse(String json)`, `AiCredentialDocument.toJson()`, `AiCredentialDocument.withSecret(String name, String cipher)`, `AiCredentialDocument.agentType()`, `.payload()`, `.secretNames()`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
// apps/firehub-api/src/test/java/com/smartfirehub/settings/model/AiCredentialDocumentTest.java
class AiCredentialDocumentTest {

  @Test
  void parse_모르는_필드를_보존한다() {
    // 3단계에서 필드가 늘 때 낡은 UI 의 read-modify-write 가 새 필드를 떨구면 안 된다.
    String json = """
        {"v":1,"agentType":"sdk","payload":{"futureKey":"x"},"secret":{},"unknownTop":7}""";
    AiCredentialDocument doc = AiCredentialDocument.parse(json);
    assertThat(doc.toJson()).contains("futureKey").contains("unknownTop");
  }

  @Test
  void secretNames_는_빈_값을_제외한다() {
    // "설정됨" 표시의 근거라, 지운 키가 섞이면 사용자가 있지도 않은 값을 믿는다.
    AiCredentialDocument doc =
        AiCredentialDocument.parse("""
            {"v":1,"agentType":"sdk","payload":{},"secret":{"apiKey":"ZW5j","oauthToken":""}}""");
    assertThat(doc.secretNames(cipher -> cipher.isEmpty() ? "" : "plain")).containsExactly("apiKey");
  }

  @Test
  void parse_알수없는_agentType_은_거부하지_않고_보존한다() {
    // fail-closed 는 소비처의 책임이다. 문서 계층에서 throw 하면 관리자가 GET/DELETE 로
    // 되돌릴 수 없다(롤백된 배포가 남긴 행을 화면에서 고칠 수 없게 된다).
    AiCredentialDocument doc =
        AiCredentialDocument.parse("""
            {"v":1,"agentType":"martian","payload":{},"secret":{}}""");
    assertThat(doc.agentType()).isEqualTo("martian");
  }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq --tests '*AiCredentialDocumentTest*'`
Expected: FAIL — `AiCredentialDocument` 심볼 없음(컴파일 오류)

- [ ] **Step 3: 최소 구현을 쓴다**

`AiCredential.java` — 해석된 형태. 스펙의 sealed 계층 그대로:

```java
/**
 * 해석된 AI 자격증명. {@code agentType} 을 문자열로 비교하지 않고 타입으로 판별한다 —
 * 1단계에서 문자열 분기가 조용히 컴파일되고 조용히 틀려 과금 혼입 회귀를 만들었다(6b1c6383).
 * 유형이 늘면 switch 누락이 컴파일 오류가 된다.
 *
 * <p>모델은 여기 없다. {@code ai.model} 은 자격증명이 아니라 평면 설정 키로 남는다.
 */
public sealed interface AiCredential {
  record Sdk(String oauthToken, String apiKey) implements AiCredential {}
  record Cli(String oauthToken) implements AiCredential {}
  record CliApi(String apiKey) implements AiCredential {}
  record Opencode(String providerId, String baseUrl, String reasoningEffort, String apiKey)
      implements AiCredential {}
}
```

`AiCredentialDocument.java` — 저장 형태. Jackson `ObjectNode` 를 그대로 들고 다녀 모르는 필드를 보존한다:

```java
/**
 * {@code ai.credential} 의 저장 형태 {@code {v, agentType, payload, secret}}.
 *
 * <p><b>ObjectNode 를 그대로 보관한다.</b> POJO 로 매핑하면 3단계가 필드를 더했을 때
 * 낡은 화면의 read-modify-write 가 그 필드를 조용히 떨군다.
 *
 * <p>{@code secret} 의 값은 <b>암호문</b>이다. 이 클래스는 복호화하지 않는다 —
 * 복호화 책임을 한 곳({@code AiCredentialService})에 묶어 평문이 도는 경로를 좁힌다.
 */
public final class AiCredentialDocument {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final ObjectNode root;

  private AiCredentialDocument(ObjectNode root) { this.root = root; }

  public static AiCredentialDocument parse(String json) { /* readTree → ObjectNode */ }
  public static AiCredentialDocument empty(String agentType) { /* v=1, 빈 payload/secret */ }

  public String agentType() { return root.path("agentType").asText(""); }
  public ObjectNode payload() { return (ObjectNode) root.with("payload"); }

  /** 값이 실제로 있는 비밀의 이름만. {@code decrypt} 로 복호화해 공백이 아닌 것만 센다. */
  public List<String> secretNames(UnaryOperator<String> decrypt) { /* ... */ }

  public AiCredentialDocument withSecret(String name, String cipher) { /* ... */ }
  public String toJson() { return root.toString(); }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq --tests '*AiCredentialDocumentTest*'`
Expected: PASS (3개)

- [ ] **Step 5: 뮤테이션 검사**

`secretNames` 에서 빈 값 필터를 제거 → 두 번째 테스트가 RED 여야 한다. 복구 후 GREEN 확인. 결과를 보고한다.

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/settings/model/AiCredential.java \
        apps/firehub-api/src/main/java/com/smartfirehub/settings/model/AiCredentialDocument.java \
        apps/firehub-api/src/test/java/com/smartfirehub/settings/model/AiCredentialDocumentTest.java
git commit --no-verify -m "feat(settings): AI 자격증명 sealed 타입과 저장 문서 추가"
```

---

## Task 2: `AiCredentialService` — 읽기·쓰기·검증

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/settings/service/AiCredentialService.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/settings/service/AiCredentialServiceTest.java`

**Interfaces:**
- Consumes: `AiCredential`, `AiCredentialDocument` (Task 1), 기존 `EncryptionService`, `SettingsRepository`
- Produces:
  - `AiCredential resolve()` — 두 평면 해석 후 타입으로
  - `AiCredentialView read()` — 화면용(`agentType`, `payload`, `secretFieldNames`, `tenantOwned`)
  - `void save(AiCredentialUpsert req, Long userId, boolean platformPlane)`
  - `void clearTenantOverride()`
  - `record AiCredentialView(String agentType, Map<String,Object> payload, List<String> secretFieldNames, Boolean tenantOwned)`
  - `record AiCredentialUpsert(String agentType, Map<String,Object> payload, Map<String,String> secret)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
class AiCredentialServiceTest {

  @Test
  void save_유형이_바뀌면_이름이_겹치는_비밀도_전부_폐기한다() {
    // Sdk.apiKey(Anthropic)와 Opencode.apiKey(OpenAI 호환)는 이름만 같고 다른 비밀이다.
    // 남겨두면 Anthropic 키가 임의의 호환 호스트로 Bearer 전송된다.
    service.save(upsert("sdk", Map.of(), Map.of("apiKey", "sk-ant-live")), USER, false);
    service.save(upsert("opencode",
        Map.of("providerId", "openai", "baseURL", "https://api.openai.com/v1"),
        Map.of()), USER, false);

    AiCredential resolved = service.resolve();
    assertThat(resolved).isInstanceOf(AiCredential.Opencode.class);
    assertThat(((AiCredential.Opencode) resolved).apiKey()).isEmpty();
  }

  @Test
  void save_비밀을_생략하면_현재_값을_유지한다() {
    service.save(upsert("sdk", Map.of(), Map.of("apiKey", "sk-keep")), USER, false);
    service.save(upsert("sdk", Map.of(), Map.of()), USER, false); // 생략

    assertThat(((AiCredential.Sdk) service.resolve()).apiKey()).isEqualTo("sk-keep");
  }

  @Test
  void save_비밀에_빈_문자열을_주면_삭제한다() {
    service.save(upsert("sdk", Map.of(), Map.of("apiKey", "sk-del")), USER, false);
    service.save(upsert("sdk", Map.of(), Map.of("apiKey", "")), USER, false);

    assertThat(((AiCredential.Sdk) service.resolve()).apiKey()).isEmpty();
    assertThat(service.read().secretFieldNames()).doesNotContain("apiKey");
  }

  @Test
  void save_opencode_는_baseURL_이_없으면_거부한다() {
    assertThatThrownBy(() -> service.save(
            upsert("opencode", Map.of("providerId", "openai"), Map.of("apiKey", "k")), USER, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolve_알수없는_agentType_은_예외다() {
    // fail-closed. 플랫폼 값으로 폴백하면 6b1c6383 과 같은 과금 회귀가 된다.
    seedTenantRaw("""{"v":1,"agentType":"martian","payload":{},"secret":{}}""");
    assertThatThrownBy(() -> service.resolve()).isInstanceOf(UnknownAgentTypeException.class);
  }

  @Test
  void read_는_알수없는_agentType_이어도_동작한다() {
    // 관리자가 화면에서 되돌릴 수 있어야 한다.
    seedTenantRaw("""{"v":1,"agentType":"martian","payload":{},"secret":{}}""");
    assertThat(service.read().agentType()).isEqualTo("martian");
  }

  @Test
  void read_는_비밀_값을_절대_내보내지_않는다() {
    service.save(upsert("sdk", Map.of(), Map.of("apiKey", "sk-secret")), USER, false);
    AiCredentialView view = service.read();
    assertThat(view.payload().toString()).doesNotContain("sk-secret");
    assertThat(view.secretFieldNames()).containsExactly("apiKey");
  }

  @Test
  void clearTenantOverride_후에는_플랫폼_값이_해석된다() {
    savePlatform(upsert("sdk", Map.of(), Map.of("apiKey", "sk-platform")));
    service.save(upsert("cli-api", Map.of(), Map.of("apiKey", "sk-tenant")), USER, false);
    service.clearTenantOverride();

    assertThat(service.resolve()).isInstanceOf(AiCredential.Sdk.class);
    assertThat(service.read().tenantOwned()).isFalse();
  }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq --tests '*AiCredentialServiceTest*'`
Expected: FAIL — `AiCredentialService` 없음

- [ ] **Step 3: 구현한다**

핵심 규칙(주석으로 남길 것):

```java
/**
 * {@code ai.credential} 키의 <b>유일한 소유자</b>. 이 키를 읽고 쓰는 모든 경로가 여기를 지난다.
 *
 * <p><b>왜 SettingsService 가 아닌가</b>: 범용 설정 경로는 {@code Map<String,String>} 을 키마다
 * 검증·암호화한다. 이 값은 JSON 이고 비밀이 <b>하위 필드</b>에 있어, 범용 경로에 얹으면
 * (1) 검증이 문자열 파싱이 되고 (2) 하위 필드 암호화를 범용 경로가 알아야 하며
 * (3) {@code secretFieldNames} 같은 응답 성형이 불가능하다.
 *
 * <p><b>두 평면</b>: 테넌트 행이 있으면 그 값, 없으면 플랫폼 값. 블롭이 하나라 재정의는
 * 항상 통째다 — 1단계 번들 규칙이 원하던 원자성이 구조상 공짜가 된다.
 */
```

- `resolve()`: 테넌트 행 → 없으면 플랫폼 행 → `AiCredentialDocument.parse` → 복호화 → `switch (agentType)` 으로 record 생성. 알 수 없는 값은 `UnknownAgentTypeException`.
- `save()`: 기존 문서를 읽어 **비밀 병합**(생략=유지, 빈 문자열=삭제), `agentType` 이 바뀌면 `secret` 을 통째로 비우고 새로 받은 것만 넣는다. 유형별 필수 필드 검증(`opencode` → `providerId`, `baseURL`).
- `read()`: `resolve()` 를 쓰지 않는다(알 수 없는 유형에서도 동작해야 한다). 문서에서 직접 `agentType`/`payload`/`secretNames` 를 뽑는다.
- `platformPlane` 이면 `system_settings`, 아니면 `tenant_settings`.

- [ ] **Step 4: 통과를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq --tests '*AiCredentialServiceTest*'`
Expected: PASS (8개)

- [ ] **Step 5: 뮤테이션 검사 3회**

(a) 유형 변경 시 `secret` 초기화를 제거 → 1번 테스트 RED
(b) 비밀 병합에서 "생략=유지"를 "생략=삭제"로 → 2번 테스트 RED
(c) `read()` 가 `resolve()` 를 쓰게 변경 → 6번 테스트 RED
각각 복구 후 GREEN 확인. 결과 보고.

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/settings/service/AiCredentialService.java \
        apps/firehub-api/src/test/java/com/smartfirehub/settings/service/AiCredentialServiceTest.java
git commit --no-verify -m "feat(settings): AiCredentialService — 유형별 검증과 비밀 하위 필드 처리"
```

---

## Task 3: 범용 조회 차단 + 번들 기계 제거 + 정책 갱신

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/settings/service/SettingsService.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/settings/service/SettingsOverridePolicy.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/settings/service/AiCredentialLeakGuardTest.java`

**Interfaces:**
- Consumes: Task 2 의 `AiCredentialService`
- Produces: `ai.credential` 을 범용 경로에서 제외하는 불변식

**이 작업이 막는 것:** `ai.credential` 은 `SECRET_KEYS` 에 없으므로 아무 조치를 안 하면 `getAll()` / `getResolvedByPrefix("ai")` / `getAsMap("ai")` / `getValue("ai.credential")` 이 **모든 비밀의 AES 암호문을 포함한 JSON 을 그대로 내보낸다.** `SettingsService:305` javadoc 이 기록한 사고와 같은 부류다. 1단계에서 그 문을 닫던 `rejectBundleKey` 를 이 설계가 없애므로 대체 불변식이 필요하다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
class AiCredentialLeakGuardTest extends IntegrationTestBase {

  @BeforeEach
  void seed() {
    aiCredentialService.save(
        new AiCredentialUpsert("sdk", Map.of(), Map.of("apiKey", "sk-live-secret")), USER, true);
  }

  @Test
  void getAll_은_ai_credential_을_내보내지_않는다() {
    assertThat(settingsService.getAll())
        .extracting(SettingResponse::key)
        .doesNotContain("ai.credential");
  }

  @Test
  void prefix_조회는_ai_credential_을_내보내지_않는다() {
    assertThat(settingsService.getResolvedByPrefix("ai"))
        .extracting(ResolvedSettingResponse::key)
        .doesNotContain("ai.credential");
    assertThat(settingsService.getAsMap("ai")).doesNotContainKey("ai.credential");
  }

  @Test
  void 단건_조회는_거부된다() {
    assertThatThrownBy(() -> settingsService.getValue("ai.credential"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 범용_쓰기로는_저장할_수_없다() {
    assertThatThrownBy(
            () -> settingsService.updateSettings(Map.of("ai.credential", "{}"), USER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 어떤_범용_경로에도_암호문이_섞이지_않는다() {
    // 위 네 테스트가 키 이름만 본다면 이 테스트는 값 전체를 본다 — 나중에 키 이름이
    // 바뀌어도 유출이 잡히게 하는 마지막 그물이다.
    String all = settingsService.getAll().toString()
        + settingsService.getResolvedByPrefix("ai")
        + settingsService.getAsMap("ai");
    assertThat(all).doesNotContain("sk-live-secret").doesNotContain("agentType");
  }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq --tests '*AiCredentialLeakGuardTest*'`
Expected: FAIL — 5개 모두(현재는 그냥 흘러나온다)

- [ ] **Step 3: 구현한다**

1. `SettingsService` 에 `static final String AI_CREDENTIAL_KEY = "ai.credential";` 를 두고, `getAll` / `getResolvedByPrefix` / `getAsMap` 에서 필터링, `getValue` / `updateSettings` / `updatePlatformSettings` 에서 거부(`rejectBundleKey` 자리를 이 키 하나로 교체).
2. 번들 기계를 지운다: `AI_CREDENTIAL_KEYS`, `applyAiCredentialBundle`, `BUNDLE_FILL_VALUES` 의 `ai.agent_type` 항목, `resolveOverridesByPrefix` 의 호출.
   **`SMTP_CONNECTION_KEYS` 와 `applySmtpConnectionBundle` 은 건드리지 않는다.**
3. `getAiCredentials()` / `AiCredentials` record 를 삭제한다(Task 4 에서 소비처를 옮긴다 — 이 단계에서는 컴파일이 깨진 채로 두지 말고 Task 4 와 **같은 커밋으로 묶어도 된다**. 분리가 어려우면 Task 3·4 를 한 커밋으로 합치고 그렇게 보고한다).
4. `SettingsOverridePolicy.TENANT_OVERRIDABLE` 에서 `ai.api_key`·`ai.cli_oauth_token`·`ai.agent_type` 을 빼고 `ai.credential` 을 넣는다. **`ai.model` 은 그대로 둔다.** 클래스 javadoc 의 키 개수 설명도 갱신한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq --tests '*AiCredentialLeakGuardTest*' --tests '*SettingsOverridePolicyTest*' --tests '*SettingsKeyWhitelistInvariantTest*'`
Expected: PASS

- [ ] **Step 5: 뮤테이션 검사**

`getResolvedByPrefix` 의 필터를 제거 → 2번·5번 테스트 RED. 복구 후 GREEN. 보고.

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/settings/service/SettingsService.java \
        apps/firehub-api/src/main/java/com/smartfirehub/settings/service/SettingsOverridePolicy.java \
        apps/firehub-api/src/test/java/com/smartfirehub/settings/service/AiCredentialLeakGuardTest.java
git commit --no-verify -m "feat(settings): ai.credential 을 범용 조회에서 차단하고 번들 기계를 제거한다"
```

---

## Task 4: 소비처 4곳을 `AiCredential` switch 로 전환

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/ai/service/AiAgentProxyService.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/ai/controller/AiController.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/executor/AiAgentClient.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobAsyncRunner.java`
- Test: 각 클래스의 기존 테스트 갱신 + `apps/firehub-api/src/test/java/com/smartfirehub/ai/AmbientKeyNeverUsedTest.java` (신규)

**Interfaces:**
- Consumes: `AiCredentialService.resolve()` → `AiCredential` (Task 2)

**중요:** `ai.model` 은 평면 키로 남으므로 **`AiAgentClient:83` 과 `AiAgentProxyService:242` 의 `ai.model` 읽기는 그대로 둔다.** 자격증명 읽기만 바꾼다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
class AmbientKeyNeverUsedTest extends IntegrationTestBase {

  @Test
  void opencode_자격증명이면_요청_바디에_provider_설정이_실린다() {
    // 이것이 없으면 ai-agent 가 컨테이너의 ambient ANTHROPIC_API_KEY 로 폴백한다 —
    // 이 브랜치가 막으려는 과금 혼입 그 자체다.
    aiCredentialService.save(new AiCredentialUpsert("opencode",
        Map.of("providerId", "openai", "baseURL", "https://api.openai.com/v1"),
        Map.of("apiKey", "sk-oai")), USER, false);

    Map<String, Object> body = aiAgentClient.buildClassifyBody("text", List.of("A", "B"));

    assertThat(body).containsEntry("agentType", "opencode");
    assertThat(body).containsEntry("baseUrl", "https://api.openai.com/v1");
    assertThat(body).containsEntry("apiKey", "sk-oai");
  }

  @Test
  void sdk_자격증명이면_oauth_우선으로_실린다() {
    aiCredentialService.save(new AiCredentialUpsert("sdk", Map.of(),
        Map.of("oauthToken", "oat", "apiKey", "sk-ant")), USER, false);

    Map<String, Object> body = aiAgentClient.buildClassifyBody("text", List.of("A"));

    assertThat(body).containsEntry("oauthToken", "oat").containsEntry("apiKey", "sk-ant");
    assertThat(body).doesNotContainKey("baseUrl");
  }

  @Test
  void 알수없는_유형이면_분류가_명시적으로_실패한다() {
    seedTenantRawCredential("""{"v":1,"agentType":"martian","payload":{},"secret":{}}""");
    assertThatThrownBy(() -> aiAgentClient.buildClassifyBody("t", List.of("A")))
        .isInstanceOf(UnknownAgentTypeException.class);
  }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq --tests '*AmbientKeyNeverUsedTest*'`
Expected: FAIL

- [ ] **Step 3: 구현한다**

각 소비처에서 `settingsService.getAiCredentials()` → `aiCredentialService.resolve()` 로 바꾸고 switch 로 분기한다. 예:

```java
// AiAgentClient.classify() — 요청 바디에 자격증명을 싣는다.
// 유형마다 실리는 키가 다르다. switch 를 쓰면 유형이 늘 때 누락이 컴파일 오류가 된다.
switch (aiCredentialService.resolve()) {
  case AiCredential.Opencode oc -> {
    body.put("agentType", "opencode");
    body.put("providerId", oc.providerId());
    body.put("baseUrl", oc.baseUrl());
    if (!oc.reasoningEffort().isBlank()) body.put("reasoningEffort", oc.reasoningEffort());
    if (!oc.apiKey().isBlank()) body.put("apiKey", oc.apiKey());
  }
  case AiCredential.Sdk sdk -> {
    body.put("agentType", "sdk");
    if (!sdk.oauthToken().isBlank()) body.put("oauthToken", sdk.oauthToken());
    if (!sdk.apiKey().isBlank()) body.put("apiKey", sdk.apiKey());
  }
  case AiCredential.Cli cli -> { /* ... */ }
  case AiCredential.CliApi ca -> { /* ... */ }
}
```

`AiController` 의 인증 상태 확인은 `Opencode` 에서 무엇을 답할지 정한다 — opencode 는 Anthropic 인증 개념이 없으므로 "해당 없음"으로 응답하고 화면이 `인증 확인` 버튼을 opencode 에서 숨긴다(Task 11 과 일치시킨다).

- [ ] **Step 4: 통과를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq`
Expected: 전체 스위트 PASS

- [ ] **Step 5: 뮤테이션 검사**

`Opencode` 분기에서 `baseUrl` 을 빼기 → 1번 테스트 RED. 복구 후 GREEN. 보고.

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/ai/ \
        apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/executor/AiAgentClient.java \
        apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobAsyncRunner.java \
        apps/firehub-api/src/test/java/com/smartfirehub/
git commit --no-verify -m "refactor(ai): 자격증명 소비처를 AiCredential switch 로 전환"
```

---

## Task 5: 마이그레이션 V122

**Files:**
- Create: `apps/firehub-api/src/main/resources/db/migration/V122__ai_credential.sql`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/settings/AiCredentialMigrationTest.java`

**착수 전 필수 확인 (사람이 운영 DB 에서 실행):**

```sql
SELECT tenant_id, array_agg(key ORDER BY key) FROM tenant_settings WHERE key LIKE 'ai.%' GROUP BY tenant_id;
SELECT count(*) FROM tenant_settings WHERE key='ai.agent_type' AND value='opencode';
```

두 번째가 0 이 아니면 **중단하고 컨트롤러에 보고한다** — "opencode 사용자 없음" 전제가 깨지면 필드가 빈 `Opencode` 레코드가 만들어진다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
class AiCredentialMigrationTest extends IntegrationTestBase {

  @Test
  void 번들_행이_하나도_없으면_credential_을_만들지_않는다() {
    // 상속 중인 테넌트를 소유자로 바꿔 버리면 화면이 "우리 조직이 직접 설정"이라고
    // 거짓말하고 플랫폼 키 로테이션도 안 닿는다.
    assertThat(rawTenantValue(TENANT_INHERITING, "ai.credential")).isNull();
  }

  @Test
  void 번들_행이_하나라도_있으면_1단계_채움_규칙대로_변환한다() {
    // agent_type 만 있던 테넌트: 자격증명 2키는 "", agent_type 은 그 값.
    String json = rawTenantValue(TENANT_AGENT_TYPE_ONLY, "ai.credential");
    assertThat(json).contains("\"agentType\":\"cli-api\"");
    assertThat(readSecret(json, "apiKey")).isEmpty();
  }

  @Test
  void 암호문은_그대로_옮겨진다() {
    // 값 단위 AES 라 재암호화가 필요 없다. 문자열이 바뀌면 복호화가 깨진다.
    assertThat(readSecretCipher(rawTenantValue(TENANT_WITH_KEY, "ai.credential"), "apiKey"))
        .isEqualTo(OLD_API_KEY_CIPHER);
  }

  @Test
  void ai_model_행은_건드리지_않는다() {
    assertThat(rawTenantValue(TENANT_MODEL_ONLY, "ai.model")).isEqualTo("claude-haiku-4-5");
    assertThat(rawTenantValue(TENANT_MODEL_ONLY, "ai.credential")).isNull();
  }

  @Test
  void 적용되는_값이_변환_전후로_같다() {
    // 이 마이그레이션의 성공 기준. 각 테넌트 컨텍스트에서 해석 결과를 비교한다.
    for (long tenantId : ALL_SEEDED_TENANTS) {
      assertThat(resolveAfter(tenantId)).isEqualTo(EXPECTED_BEFORE.get(tenantId));
    }
  }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq --tests '*AiCredentialMigrationTest*'`
Expected: FAIL

- [ ] **Step 3: 마이그레이션을 쓴다**

```sql
-- V122: ai.api_key / ai.cli_oauth_token / ai.agent_type 3키를 ai.credential JSON 하나로 합친다.
--
-- 옛 3키는 **지우지 않는다**. Flyway community 에는 undo 가 없어 이 변환은 forward-only 이고,
-- providerId·baseURL·reasoningEffort 는 되돌릴 수 없다. 새 코드가 안정된 뒤 별도
-- 마이그레이션으로 지운다(스펙 「롤백」 참고).
--
-- ai.model 은 건드리지 않는다 — 자격증명이 아니라 평면 설정 키로 남는다.
--
-- RLS: 이 마이그레이션은 소유자 app 으로 돌고 V114 가 FORCE 를 걸지 않아 GUC 없이 전 행을 본다.
-- 나중에 FORCE 가 추가되면 조용히 0행을 변환하게 되므로 아래에서 행 수를 단언한다.

DO $$
DECLARE
  opencode_count int;
  converted int;
BEGIN
  SELECT count(*) INTO opencode_count
    FROM tenant_settings WHERE key = 'ai.agent_type' AND value = 'opencode';
  IF opencode_count > 0 THEN
    RAISE EXCEPTION
      'opencode 를 쓰는 테넌트가 % 개 있다. 설계 전제(사용자 없음)가 깨졌으므로 중단한다.',
      opencode_count;
  END IF;
END $$;

-- 테넌트 평면
INSERT INTO tenant_settings (tenant_id, key, value, updated_by, updated_at)
SELECT t.tenant_id, 'ai.credential',
       jsonb_build_object(
         'v', 1,
         'agentType', coalesce(max(value) FILTER (WHERE key='ai.agent_type'), 'sdk'),
         'payload', '{}'::jsonb,
         'secret', jsonb_strip_nulls(jsonb_build_object(
             'apiKey',     max(value) FILTER (WHERE key='ai.api_key'),
             'oauthToken', max(value) FILTER (WHERE key='ai.cli_oauth_token')))
       )::text,
       max(updated_by), max(updated_at)
  FROM tenant_settings t
 WHERE key IN ('ai.api_key','ai.cli_oauth_token','ai.agent_type')
 GROUP BY t.tenant_id;

-- 플랫폼 평면 (동일 형태, system_settings)
INSERT INTO system_settings (key, value, description)
SELECT 'ai.credential', jsonb_build_object(...)::text, 'AI 자격증명(유형별 구조)'
  FROM system_settings WHERE key IN ('ai.api_key','ai.cli_oauth_token','ai.agent_type')
 HAVING count(*) > 0;

DO $$
DECLARE converted int;
BEGIN
  SELECT count(*) INTO converted FROM tenant_settings WHERE key='ai.credential';
  RAISE NOTICE 'ai.credential 변환 행: %', converted;
END $$;
```

- [ ] **Step 4: 통과를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq --tests '*AiCredentialMigrationTest*'`
Expected: PASS (5개)

- [ ] **Step 5: 뮤테이션 검사**

`WHERE key IN (...)` 에서 `ai.agent_type` 을 빼기 → 2번 테스트 RED. opencode 가드를 제거하고 opencode 행을 시드 → 가드 테스트 추가 후 RED 확인. 복구 후 GREEN. 보고.

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-api/src/main/resources/db/migration/V122__ai_credential.sql \
        apps/firehub-api/src/test/java/com/smartfirehub/settings/AiCredentialMigrationTest.java
git commit --no-verify -m "feat(settings): V122 — AI 자격증명 3키를 ai.credential 로 합친다"
```

---

## Task 6: 프로브 서비스 (SSRF 가드 포함)

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/settings/service/OpencodeProbeService.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/settings/service/OpencodeProbeServiceTest.java`

**Interfaces:**
- Produces: `ProbeResult probe(String baseUrl, String apiKey)` / `record ProbeResult(boolean ok, List<String> models, String message)`

**이 작업이 막는 것:** 인증된 테넌트 관리자가 서버로 하여금 **임의 URL 에 Bearer 를 실어 보내게** 할 수 있는 표면이다. 아래 가드는 전부 필수다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
class OpencodeProbeServiceTest {

  @Test
  void 사설_대역은_거부한다() {
    for (String url : List.of("http://127.0.0.1/v1", "https://10.0.0.5/v1",
                              "https://192.168.1.1/v1", "https://169.254.169.254/v1")) {
      assertThat(probeService.probe(url, "k").ok()).isFalse();
    }
  }

  @Test
  void http_는_거부한다() {
    assertThat(probeService.probe("http://example.com/v1", "k").ok()).isFalse();
  }

  @Test
  void 리다이렉트를_따라가지_않는다() {
    wireMock.stubFor(get("/v1/models").willReturn(temporaryRedirect("https://evil.example/x")));
    assertThat(probeService.probe(wireMockUrl("/v1"), "k").ok()).isFalse();
  }

  @Test
  void 성공하면_모델_ID_만_돌려준다() {
    wireMock.stubFor(get("/v1/models").willReturn(okJson("""
        {"data":[{"id":"gpt-4o","owner":"x"},{"id":"gpt-4o-mini"}]}""")));
    assertThat(probeService.probe(wireMockUrl("/v1"), "k").models())
        .containsExactly("gpt-4o", "gpt-4o-mini");
  }

  @Test
  void 배열_직반환도_받는다() {
    wireMock.stubFor(get("/v1/models").willReturn(okJson("""[{"id":"m1"}]""")));
    assertThat(probeService.probe(wireMockUrl("/v1"), "k").models()).containsExactly("m1");
  }

  @Test
  void 실패_메시지에_apiKey_와_upstream_본문이_없다() {
    wireMock.stubFor(get("/v1/models")
        .willReturn(aResponse().withStatus(401).withBody("bad key sk-secret-123")));
    ProbeResult r = probeService.probe(wireMockUrl("/v1"), "sk-secret-123");
    assertThat(r.ok()).isFalse();
    assertThat(r.message()).doesNotContain("sk-secret-123").doesNotContain("bad key");
  }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq --tests '*OpencodeProbeServiceTest*'`
Expected: FAIL

- [ ] **Step 3: 구현한다**

`GET {baseUrl}/models` + `Authorization: Bearer {apiKey}`, 타임아웃 10초. 가드:
- https 전용, 고정 포트 집합(443 및 명시 포트 허용 목록)
- 리다이렉트 추적 금지
- **DNS 해석 결과**가 loopback / 사설 / link-local / `169.254.169.254` 면 거부(호스트명만 보고 판정하면 DNS rebinding 으로 우회된다)
- 응답은 모델 ID 배열만. upstream 본문·상태 텍스트·헤더를 흘리지 않는다
- apiKey 를 로그에 남기지 않는다

- [ ] **Step 4: 통과를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq --tests '*OpencodeProbeServiceTest*'`
Expected: PASS (6개)

- [ ] **Step 5: 뮤테이션 검사**

사설 대역 검사를 제거 → 1번 RED. 실패 메시지에 upstream 본문을 포함시키기 → 6번 RED. 각각 복구 후 GREEN. 보고.

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/settings/service/OpencodeProbeService.java \
        apps/firehub-api/src/test/java/com/smartfirehub/settings/service/OpencodeProbeServiceTest.java
git commit --no-verify -m "feat(settings): opencode 모델 프로브와 SSRF 가드"
```

---

## Task 7: REST 엔드포인트 (테넌트 + 플랫폼)

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/settings/controller/AiCredentialController.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/platform/controller/PlatformAiCredentialController.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/settings/controller/AiCredentialControllerTest.java`

**Interfaces:**
- Consumes: `AiCredentialService`(Task 2), `OpencodeProbeService`(Task 6)
- Produces:
  ```
  GET    /api/v1/settings/ai-credential        → AiCredentialView (tenantOwned 포함)
  PUT    /api/v1/settings/ai-credential
  DELETE /api/v1/settings/ai-credential
  POST   /api/v1/settings/ai-credential/probe  → {ok, models, message} (항상 200)
  GET/PUT/POST /api/platform/settings/ai-credential[/probe]  (tenantOwned 없음)
  ```

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
class AiCredentialControllerTest extends IntegrationTestBase {

  @Test
  void 프로브는_평면을_건너_폴백하지_않는다() throws Exception {
    // 테넌트가 자기 행 없이 키를 생략하면, 폴백이 플랫폼 키를 임의 주소로 내보낸다.
    savePlatformCredential("opencode", "https://api.openai.com/v1", "sk-PLATFORM");

    mockMvc.perform(post("/api/v1/settings/ai-credential/probe")
            .contentType(APPLICATION_JSON)
            .content("""{"baseURL":"https://evil.example/v1"}""")
            .with(tenantAdmin()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void baseURL_이_저장된_값과_다르면_apiKey_를_요구한다() throws Exception {
    saveTenantCredential("opencode", "https://api.openai.com/v1", "sk-TENANT");

    mockMvc.perform(post("/api/v1/settings/ai-credential/probe")
            .contentType(APPLICATION_JSON)
            .content("""{"baseURL":"https://other.example/v1"}""")
            .with(tenantAdmin()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 프로브는_쓰기_권한을_요구한다() throws Exception {
    mockMvc.perform(post("/api/v1/settings/ai-credential/probe")
            .contentType(APPLICATION_JSON).content("{}").with(readOnlyUser()))
        .andExpect(status().isForbidden());
  }

  @Test
  void GET_은_비밀_값을_내보내지_않는다() throws Exception {
    saveTenantCredential("sdk", null, "sk-secret");
    mockMvc.perform(get("/api/v1/settings/ai-credential").with(tenantAdmin()))
        .andExpect(jsonPath("$.secretFieldNames[0]").value("apiKey"))
        .andExpect(content().string(not(containsString("sk-secret"))));
  }

  @Test
  void DELETE_후에는_플랫폼_값을_상속한다() throws Exception {
    savePlatformCredential("sdk", null, "sk-platform");
    saveTenantCredential("cli-api", null, "sk-tenant");

    mockMvc.perform(delete("/api/v1/settings/ai-credential").with(tenantAdmin()))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/api/v1/settings/ai-credential").with(tenantAdmin()))
        .andExpect(jsonPath("$.agentType").value("sdk"))
        .andExpect(jsonPath("$.tenantOwned").value(false));
  }

  @Test
  void 플랫폼_응답에는_tenantOwned_가_없다() throws Exception {
    mockMvc.perform(get("/api/platform/settings/ai-credential").with(platformAdmin()))
        .andExpect(jsonPath("$.tenantOwned").doesNotExist());
  }

  @Test
  void 도달_불가와_타임아웃과_형식_오류가_구분된다() throws Exception {
    // 400 = 페이로드 형식, 422 = 공급자 거부/모델 없음, 502 = 도달 불가, 504 = 타임아웃
    // (프로브 자체는 항상 200 + ok:false 이고, 이 구분은 PUT 의 응답 코드다)
    assertPutStatus("""{"agentType":"opencode","payload":{}}""", 400);      // baseURL 없음
    assertPutStatus(validOpencodeWithUnreachableHost(), 502);
  }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq --tests '*AiCredentialControllerTest*'`
Expected: FAIL

- [ ] **Step 3: 구현한다**

- 권한: 테넌트 `@RequirePermission("ai:settings")`, 플랫폼은 `PlatformSettingsController` 의 기존 방식을 따른다. **프로브는 쓰기 권한**을 요구한다.
- 프로브 요청에 `apiKey` 가 없을 때의 폴백은 **`tenant_settings` 의 값만** 읽는다(두 평면 해석기 금지). `tenantOwned=false` 인데 생략이면 400.
- `baseURL` 이 저장된 값과 다르면 `apiKey` 필수.
- PUT 은 저장 전에 검증한다: 유형별 필수 필드 → 400; `agentType=opencode` 면 `ai.model` 과 `providerId` 정합; 프로브가 **비어 있지 않은 목록**을 줄 때만 모델 소속 검사(목록이 비면 자유 입력이 정당하므로 막지 않는다); `reasoningEffort` 는 정적 enum 검사만.
- 프로브 응답은 `POST /settings/smtp/test` 선례대로 **항상 200** + `{ok, models, message}`.
- **동시성은 이번에 다루지 않는다.** 스펙이 `If-Match` → 409 를 권장(필수 아님)으로 두었다. 블롭 last-write-wins 는 1단계에도 있던 충돌이 한 행으로 모인 것뿐이다. 저장 전 프로브(최대 10초)가 창을 넓힌다는 점만 주석으로 남기고, 실제로 충돌이 관찰되면 별건으로 처리한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `cd apps/firehub-api && ./gradlew test -x generateJooq`
Expected: 전체 PASS

- [ ] **Step 5: 뮤테이션 검사**

폴백을 두 평면 해석기(`getValue`)로 바꾸기 → 1번 테스트 RED. 복구 후 GREEN. 보고.

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/settings/controller/AiCredentialController.java \
        apps/firehub-api/src/main/java/com/smartfirehub/platform/controller/PlatformAiCredentialController.java \
        apps/firehub-api/src/test/java/com/smartfirehub/settings/controller/AiCredentialControllerTest.java
git commit --no-verify -m "feat(settings): AI 자격증명 전용 엔드포인트와 프로브"
```

---

## Task 8: 에이전트 — OpenAI 호환 completion 프로바이더

**Files:**
- Create: `apps/firehub-ai-agent/src/providers/openai-compat-completion-provider.ts`
- Modify: `apps/firehub-ai-agent/src/providers/provider-factory.ts`
- Modify: `apps/firehub-ai-agent/src/providers/types.ts`
- Test: `apps/firehub-ai-agent/src/providers/__tests__/openai-compat-completion-provider.test.ts`
- Test: `apps/firehub-ai-agent/src/providers/provider-factory.test.ts` (기존 파일에 추가)

**Interfaces:**
- Consumes: 기존 `CompletionProvider`, `CompletionOptions`, `CompletionResult`
- Produces: `OpenAICompatCompletionProvider(baseUrl, apiKey, model)`; `ProviderConfig` 에 `baseUrl?`, `providerId?` 추가

**왜 필요한가:** `createCompletionProvider` 는 지금 `agentType` 분기 없이 항상 `ClaudeSdkCompletionProvider` 를 돌려준다. 호출부는 **분류**(`classification-service.ts:129`)와 **GraphRAG 추출**(`llm-completer.ts:30`) 둘. opencode 테넌트는 OpenAI 호환 키를 갖는데 Claude SDK 에 실리면 401 이거나, 비어 있으면 ambient 키로 떨어진다 — 둘 다 이미 운영에서 일어나고 있다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```ts
describe('OpenAICompatCompletionProvider', () => {
  it('chat/completions 를 부르고 텍스트를 돌려준다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      choices: [{ message: { content: '답' } }],
      usage: { prompt_tokens: 3, completion_tokens: 5 },
    }));
    const p = new OpenAICompatCompletionProvider('https://x/v1', 'k', 'openai/gpt-4o', fetchMock);

    const r = await p.complete('시스템', '사용자');

    expect(fetchMock.mock.calls[0][0]).toBe('https://x/v1/chat/completions');
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer k');
    expect(r.text).toBe('답');
    expect(r.usage).toEqual({ inputTokens: 3, outputTokens: 5 });
  });

  it('providerId 접두사를 떼고 모델 ID 만 보낸다', async () => {
    // 저장 규약은 providerId/modelId 인데 공급자는 modelId 만 안다.
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ choices: [{ message: { content: '' } }] }));
    await new OpenAICompatCompletionProvider('https://x/v1', 'k', 'openai/gpt-4o', fetchMock)
      .complete('s', 'u');
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).model).toBe('gpt-4o');
  });

  it('timeoutMs 를 넘기면 abort 한다', async () => { /* AbortSignal 확인 */ });
});

describe('createCompletionProvider', () => {
  it('opencode 면 OpenAI 호환 프로바이더를 고른다', () => {
    const p = ProviderFactory.createCompletionProvider({
      agentType: 'opencode', baseUrl: 'https://x/v1', apiKey: 'k', model: 'openai/gpt-4o' });
    expect(p.name).toBe('openai-compat');
  });

  it('나머지 유형은 Claude SDK 프로바이더를 고른다', () => {
    for (const agentType of ['sdk', 'cli', 'cli-api'] as const) {
      expect(ProviderFactory.createCompletionProvider({ agentType, apiKey: 'k' }).name)
        .toBe('claude-sdk');
    }
  });

  it('config 없이 부르면 Claude SDK 로 폴백한다 (단독 스크립트용)', () => {
    expect(ProviderFactory.createCompletionProvider().name).toBe('claude-sdk');
  });
});
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd apps/firehub-ai-agent && pnpm vitest run src/providers`
Expected: FAIL

- [ ] **Step 3: 구현한다**

`openai-compat-completion-provider.ts`: `POST {baseUrl}/chat/completions`, body `{model, messages:[{role:'system'},{role:'user'}], max_tokens?, temperature?}`. `providerId/` 접두사를 떼어 `model` 로 보낸다. `usage` 는 best-effort.

`provider-factory.ts` 의 `createCompletionProvider` 에 분기를 넣고, **"채팅과 달리 agentType 분기가 없다" 주석을 갱신한다** — 그 이유("자격증명만 흘려받아 인증 경로가 갈라지지 않도록")는 자격증명이 한 종류일 때 성립하던 것이다.

`systemPromptMode: 'append-to-preset'` 은 OpenAI 호환 경로에 claude_code 프리셋이 없으므로 `replace` 와 동일하게 처리하고, 그 사실을 주석에 남긴다.

- [ ] **Step 4: 통과를 확인한다**

Run: `cd apps/firehub-ai-agent && pnpm vitest run && pnpm typecheck`
Expected: PASS

- [ ] **Step 5: 뮤테이션 검사**

팩토리의 `opencode` 분기를 제거 → 팩토리 테스트 RED. 접두사 제거 로직을 없애기 → 2번 RED. 복구 후 GREEN. 보고.

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-ai-agent/src/providers/
git commit --no-verify -m "feat(ai-agent): opencode 용 OpenAI 호환 completion 프로바이더"
```

---

## Task 9: 에이전트 — opencode provider 블록

**Files:**
- Modify: `apps/firehub-ai-agent/src/agent/agent-opencode.ts`
- Modify: `apps/firehub-ai-agent/src/agent/agent-opencode.test.ts`

**Interfaces:**
- Consumes: 요청 바디의 `providerId` / `baseUrl` / `apiKey` / `reasoningEffort` / `model` (Task 4 가 싣는다)

**이 작업이 뒤집는 것:** 2026-06-23 의 "옵션 3: 배포 측 전역 설정 상속" 결정. 그것을 고정하던 테스트 `agent-opencode.test.ts:16` (`model 필드를 넣지 않는다 (옵션 3: 배포 측 전역 설정 상속)`) 도 함께 뒤집는다.

- [ ] **Step 1: 핀 테스트를 뒤집고 새 테스트를 쓴다**

```ts
it('provider 블록을 쓴다 (옵션 3 폐기 — 2026-09-19, 이슈 #693)', () => {
  const config = buildOpenCodeConfig({
    providerId: 'openai', baseUrl: 'https://api.openai.com/v1',
    apiKey: 'k', model: 'openai/gpt-4o', reasoningEffort: 'medium', /* ... */ });

  expect(config.provider.openai.options).toEqual({
    baseURL: 'https://api.openai.com/v1', apiKey: 'k' });
  expect(config.provider.openai.npm).toBe('@ai-sdk/openai-compatible');
});

it('modalities.input 에 image 를 반드시 넣는다', () => {
  // 없으면 opencode 코어가 사용자 메시지의 image 파트를 제거하고
  // "does not support image input" 에러 텍스트로 바꿔친다(iacloud_eis 실측).
  const config = buildOpenCodeConfig({ /* ... */ model: 'openai/gpt-4o' });
  expect(config.provider.openai.models['gpt-4o'].modalities.input).toContain('image');
});

it('추론 강도가 비면 options 를 아예 넣지 않는다', () => {
  // 빈 문자열을 그대로 보내면 400 이다.
  const config = buildOpenCodeConfig({ /* ... */ reasoningEffort: '' });
  expect(config.provider.openai.models['gpt-4o'].options).toBeUndefined();
});

it('모델의 providerId 가 payload 와 다르면 throw 한다', () => {
  expect(() => buildOpenCodeConfig({ providerId: 'openai', model: 'google/gemini' }))
    .toThrow(/providerID/);
});
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd apps/firehub-ai-agent && pnpm vitest run src/agent/agent-opencode.test.ts`
Expected: FAIL (4개)

- [ ] **Step 3: 구현한다**

```ts
provider: {
  [providerId]: {
    npm: '@ai-sdk/openai-compatible',
    options: { baseURL, apiKey },
    models: { [modelId]: {
      modalities: { input: ['text', 'image'], output: ['text'] },
      ...(effort ? { options: { reasoningEffort: effort } } : {}),
    } },
  },
}
```

`npm` 은 고정한다 — 참조 구현은 payload 에서 덮어쓸 수 있게 두었지만 화면에서 입력받지 않아 실제로는 항상 기본값이다. 쓰지 않는 자유도는 넣지 않는다.

- [ ] **Step 4: 통과를 확인한다**

Run: `cd apps/firehub-ai-agent && pnpm vitest run && pnpm typecheck`
Expected: PASS

- [ ] **Step 5: 뮤테이션 검사**

`modalities` 를 제거 → 2번 RED. 빈 effort 가드를 제거 → 3번 RED. 복구 후 GREEN. 보고.

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-ai-agent/src/agent/agent-opencode.ts apps/firehub-ai-agent/src/agent/agent-opencode.test.ts
git commit --no-verify -m "feat(ai-agent): opencode provider 블록을 앱이 쓴다 (옵션 3 폐기)"
```

---

## Task 10: 웹 — 필드 정의와 폼 훅

**Files:**
- Create: `apps/firehub-web/src/lib/ai-credential.ts`
- Create: `apps/firehub-web/src/hooks/useAiCredentialForm.ts`
- Delete: `apps/firehub-web/src/hooks/useAiSettingsForm.ts`
- Test: `apps/firehub-web/src/hooks/useAiCredentialForm.test.ts`

**Interfaces:**
- Consumes: Task 7 의 엔드포인트
- Produces:
  ```ts
  type AgentType = 'sdk' | 'cli' | 'cli-api' | 'opencode';
  const CREDENTIAL_FIELDS: Record<AgentType, FieldSpec[]>;   // 유형별 필드
  const REASONING_EFFORTS = ['low','medium','high'] as const;
  useAiCredentialForm(): {
    plane: 'platform' | 'tenant';            // 라디오 선택 (폼 상태)
    setPlane(p): void;
    agentType, setAgentType, payload, setPayloadField,
    secretInputs, setSecretInput,             // 빈 값 = 유지
    secretFieldNames: string[],               // 서버가 준 "설정됨" 목록
    models: string[] | null, loadModels(): Promise<void>, modelsError: string | null,
    canLoadModels: boolean,                   // baseURL 있고 (키 입력 || secretFieldNames 에 apiKey)
    isLocked: boolean, hasUnsavedInput: boolean,
    save(): Promise<void>, staleNotice: string | null,
  }
  ```

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```ts
describe('useAiCredentialForm', () => {
  it('라디오 전환은 폼 상태다 — 저장 전에는 DELETE 를 부르지 않는다', async () => {
    // 즉시 삭제로 하면 실수로 한 번 누른 대가가 모든 비밀 재입력이다(평문을 돌려주지 않으므로).
    const { result } = renderHook(() => useAiCredentialForm(), { wrapper });
    act(() => result.current.setPlane('platform'));
    act(() => result.current.setPlane('tenant'));
    expect(deleteSpy).not.toHaveBeenCalled();
  });

  it('비밀 입력을 건드리지 않으면 payload 에 싣지 않는다', async () => {
    const { result } = renderHook(() => useAiCredentialForm(), { wrapper });
    act(() => result.current.setPayloadField('baseURL', 'https://x/v1'));
    await act(() => result.current.save());
    expect(putSpy.mock.calls[0][0].secret).toEqual({});
  });

  it('모델 불러오기는 저장된 키가 있으면 키 입력 없이도 활성이다', () => {
    // 저장된 키는 읽을 수 없으므로 다시 타이핑을 요구하면 막다른 길이 된다.
    const { result } = renderHook(() => useAiCredentialForm(), { wrapper: withSecretNames(['apiKey']) });
    act(() => result.current.setPayloadField('baseURL', 'https://x/v1'));
    expect(result.current.canLoadModels).toBe(true);
  });

  it('기본 URL 을 고치면 모델 목록이 무효화된다', async () => {
    const { result } = renderHook(() => useAiCredentialForm(), { wrapper });
    await act(() => result.current.loadModels());
    expect(result.current.models).not.toBeNull();
    act(() => result.current.setPayloadField('baseURL', 'https://other/v1'));
    expect(result.current.models).toBeNull();
  });

  it('유형을 바꾸면 이전 유형의 비밀 입력이 폼에서 지워진다', () => {
    const { result } = renderHook(() => useAiCredentialForm(), { wrapper });
    act(() => result.current.setSecretInput('oauthToken', 'oat'));
    act(() => result.current.setAgentType('opencode'));
    expect(result.current.secretInputs.oauthToken).toBeUndefined();
  });
});
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd apps/firehub-web && pnpm vitest run src/hooks/useAiCredentialForm.test.ts`
Expected: FAIL

- [ ] **Step 3: 구현한다**

`ai-credential.ts` 의 필드 정의:

```ts
// 유형별로 어떤 필드가 유효한가. 화면과 검증이 같은 곳을 본다.
export const CREDENTIAL_FIELDS: Record<AgentType, FieldSpec[]> = {
  sdk:        [{ name:'oauthToken', plane:'secret', label:'OAuth 토큰' },
               { name:'apiKey',     plane:'secret', label:'API 키' }],
  cli:        [{ name:'oauthToken', plane:'secret', label:'OAuth 토큰' }],
  'cli-api':  [{ name:'apiKey',     plane:'secret', label:'API 키' }],
  opencode:   [{ name:'providerId',      plane:'payload', label:'공급자',   kind:'select' },
               { name:'baseURL',         plane:'payload', label:'기본 URL', kind:'text', required:true },
               { name:'apiKey',          plane:'secret',  label:'API 키' },
               { name:'reasoningEffort', plane:'payload', label:'추론 강도', kind:'select' }],
};
```

훅은 `useSettingsOverrideForm` 의 관용구(로드 → 폼/원본 분리 → 변경분만 전송 → `staleNotice`)를 따른다. 단 비밀은 **원본을 갖지 않는다**(서버가 값을 주지 않으므로) — 입력이 비어 있으면 전송하지 않는다.

- [ ] **Step 4: 통과를 확인한다**

Run: `cd apps/firehub-web && pnpm vitest run && pnpm typecheck && npx eslint src/lib/ai-credential.ts src/hooks/useAiCredentialForm.ts`
Expected: PASS, eslint exit 0

- [ ] **Step 5: 뮤테이션 검사**

라디오 전환에서 즉시 DELETE 를 호출하게 변경 → 1번 RED. `canLoadModels` 에서 `secretFieldNames` 조건을 제거 → 3번 RED. 복구 후 GREEN. 보고.

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-web/src/lib/ai-credential.ts apps/firehub-web/src/hooks/useAiCredentialForm.ts \
        apps/firehub-web/src/hooks/useAiCredentialForm.test.ts
git rm apps/firehub-web/src/hooks/useAiSettingsForm.ts
git commit --no-verify -m "feat(web): AI 자격증명 필드 정의와 폼 훅"
```

---

## Task 11: 웹 — AI 탭 화면

**Files:**
- Modify: `apps/firehub-web/src/pages/admin/SettingsPage.tsx`
- Modify: `apps/firehub-web/src/pages/admin/settings-lock.tsx` (배지 문구)
- Test: Task 12 의 E2E 가 담당

**Interfaces:**
- Consumes: Task 10 의 `useAiCredentialForm`, `CREDENTIAL_FIELDS`

- [ ] **Step 1: 라디오와 유형별 필드를 넣는다**

`자격증명` fieldset 안 맨 위에 라디오 2개:

```tsx
{/* "재정의"는 시스템 내부 용어다. 쓰는 사람의 질문은 "플랫폼 걸 쓸까, 우리가 정할까"이고,
    상태를 읽는 배지가 아니라 고르는 선택으로 두면 그 질문에 그대로 답하게 된다.
    전환은 폼 상태다 — 즉시 삭제하면 실수로 한 번 누른 대가가 모든 비밀 재입력이다. */}
<fieldset role="radiogroup" aria-label="자격증명 출처">
  <legend className="sr-only">자격증명 출처</legend>
  <RadioOption checked={plane === 'platform'} onSelect={() => setPlane('platform')}
    title="플랫폼 설정을 사용"
    description="플랫폼 운영자가 정한 값이 그대로 적용됩니다. 운영자가 값을 바꾸면 우리 조직에도 함께 반영됩니다." />
  <RadioOption checked={plane === 'tenant'} onSelect={() => setPlane('tenant')}
    title="우리 조직이 직접 설정"
    description="AI 호출이 우리 조직 자격증명으로 나갑니다." />
</fieldset>
```

**과금 문구 주의**: "사용량도 우리 계정으로 청구됩니다"로 쓰지 않는다 — `cli`(구독 OAuth)와 사내 엔드포인트에서 거짓이다.

- [ ] **Step 2: `플랫폼 설정 사용` 상태의 세 갈래를 만든다**

1. 플랫폼에 값 있음 → 정의 목록(유형·모델·기본 URL·설정된 비밀 이름)
2. **플랫폼에 값 없음** → *"플랫폼에 설정된 값이 없습니다 — AI 기능이 동작하지 않습니다. 직접 설정하거나 플랫폼 운영자에게 요청하세요."*
3. 잠김(`isLocked`) → 라디오 둘 다 비활성 + `PlatformLockedNote`

- [ ] **Step 3: 모델 칸 4상태를 만든다**

미로드(비활성 + "먼저 모델을 불러오세요") / 목록(Select) / 목록없음(자유 입력) / **실패(오류 문구 + "직접 입력으로 전환" 버튼)**. 미로드와 실패가 같은 모양이 되지 않게 한다. `[모델 불러오기]` 는 모델 칸 **옆**에 둔다.

- [ ] **Step 4: 유형 전환 경고를 넣는다**

저장된 유형과 현재 선택이 다르면 유형 Select 아래에 정적 안내: *"유형을 바꾸면 이전 유형의 저장된 비밀이 삭제됩니다. 복구할 수 없습니다."* 저장 확인 다이얼로그에도 포함한다.

- [ ] **Step 5: `인증 확인` 을 유지한다**

기존 버튼·배지·경쟁 조건 가드(`verifySeqRef`)를 그대로 둔다. **opencode 에서는 숨긴다**(Anthropic 인증 개념이 없다). 나머지 3유형에서는 이것이 유일한 검증 수단이다.

- [ ] **Step 6: 배지 문구를 라디오 어휘로 맞춘다**

`settings-lock.tsx`: `테넌트 재정의 적용됨` → **`우리 조직 값 적용 중`**, `기본값 사용 중` → **`플랫폼 값 사용 중`**. AI 탭의 단일 키(프롬프트·Temperature)가 라디오 두 줄 아래에서 같은 상태를 다른 말로 부르지 않게 한다. **단일 키를 라디오로 바꾸지 않는다.** `PlatformLockedNote` 와 `플랫폼 전용` 은 그대로 둔다.

- [ ] **Step 7: 게이트를 돌린다**

Run: `cd apps/firehub-web && pnpm typecheck && npx eslint src/pages/admin/SettingsPage.tsx src/pages/admin/settings-lock.tsx`
Expected: PASS, exit 0

- [ ] **Step 8: 정적 E2E 교차검증**

변경한 셀렉터·문자열을 `apps/firehub-web/e2e/` 에서 grep 한다. 특히 `테넌트 재정의 적용됨` / `기본값 사용 중` 은 여러 스펙이 단언하고 있을 가능성이 높다 — 전부 찾아 갱신한다. 어떤 단언이 여전히 성립하는지 보고한다.

- [ ] **Step 9: 커밋**

```bash
git add apps/firehub-web/src/pages/admin/SettingsPage.tsx apps/firehub-web/src/pages/admin/settings-lock.tsx \
        apps/firehub-web/e2e/
git commit --no-verify -m "feat(web): AI 탭을 유형별 구조와 '플랫폼/직접' 라디오로 재작성"
```

---

## Task 12: 웹 E2E

**Files:**
- Modify: `apps/firehub-web/e2e/pages/admin/tenant-ai-settings.spec.ts`
- Modify: `apps/firehub-web/e2e/fixtures/admin.fixture.ts` (필요 시 `mockAiCredential` 추가)

- [ ] **Step 1: 시나리오를 쓴다**

1. 라디오를 `플랫폼 설정 사용` → `직접 설정` → 다시 `플랫폼` 으로 바꾸고 저장하지 않는다 → **DELETE 요청이 없다**
2. `직접 설정` 에서 유형을 `opencode` 로 → 공급자·기본 URL·API 키·모델·추론 강도가 보이고 OAuth 토큰은 사라진다
3. `[모델 불러오기]` → Select 가 활성화되고 목록이 채워진다
4. 기본 URL 을 고치면 모델 칸이 미로드로 되돌아간다
5. 프로브 실패 → 오류 문구 + `직접 입력으로 전환` 이 보인다
6. 플랫폼에 자격증명이 없는 상태로 `플랫폼 설정 사용` → *"플랫폼에 설정된 값이 없습니다"* 가 보인다
7. 유형을 바꾸면 비밀 삭제 경고가 보인다
8. 비밀 입력은 항상 비어 있고 `현재 값이 설정되어 있습니다` 가 함께 뜬다
9. opencode 에서는 `인증 확인` 버튼이 없고, sdk 에서는 있다

- [ ] **Step 2: 시스템 Chrome 우회로 실행한다**

`apps/firehub-web/playwright.config.ts` 의 `chromium` 프로젝트 `use` 에 `channel: 'chrome'` 임시 추가 → 실행 → **완전 원복** → `git diff -- apps/firehub-web/playwright.config.ts` 가 비어 있음을 증명.

Run: `cd apps/firehub-web && npx playwright test e2e/pages/admin/tenant-ai-settings.spec.ts e2e/pages/admin/settings.spec.ts`
Expected: 신규 9 + 기존 전부 PASS

- [ ] **Step 3: 뮤테이션 검사**

라디오 전환에서 즉시 DELETE 를 호출하게 변경 → 1번 RED. 플랫폼 무설정 안내를 제거 → 6번 RED. 복구 후 GREEN. 보고.

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/e2e/
git commit --no-verify -m "test(e2e): AI 자격증명 유형별 화면과 라디오 전환 검증"
```

---

## Task 13: 어드민 — AI 탭 전용 섹션

**Files:**
- Create: `apps/firehub-admin/src/lib/ai-credential.ts`
- Create: `apps/firehub-admin/src/pages/settings/AiCredentialSection.tsx`
- Modify: `apps/firehub-admin/src/lib/settings-catalog.ts`
- Modify: `apps/firehub-admin/src/pages/SettingsPage.tsx`
- Test: `apps/firehub-admin/e2e/settings.spec.ts`

**Interfaces:**
- Consumes: Task 7 의 `/api/platform/settings/ai-credential`

**차이는 하나뿐이다:** 레이아웃·필드·유형 분기는 테넌트 화면과 동일하고 **라디오 2개만 없다.** 플랫폼에는 "플랫폼 걸 쓸까"라는 선택지가 없다 — 가져다 쓸지는 테넌트가 결정한다.

- [ ] **Step 1: 카탈로그에서 3키를 뺀다**

`settings-catalog.ts` 의 AI 탭 `keys` 에서 `ai.api_key` / `ai.agent_type` / `ai.cli_oauth_token` 을 제거한다. `ai.model` 은 남긴다. `SETTING_CATALOG` 의 해당 항목도 지운다.

- [ ] **Step 2: AI 탭에 전용 섹션을 끼운다**

`SettingsPage.tsx` 의 AI 탭만 `AiCredentialSection` 을 먼저 렌더하고 나머지 키는 기존 범용 렌더러로 그린다.

```tsx
{/* AI 탭만 전용 섹션을 쓴다 — 유형에 따라 필드가 달라지는 유일한 탭이라
    tab.keys.map 범용 렌더러로는 표현할 수 없다. 이메일·임베딩 탭은 그대로 둔다. */}
{tab.id === 'ai' && <AiCredentialSection />}
```

- [ ] **Step 3: 배지를 뺀다**

AI 탭에서 `테넌트 재정의 가능` / `전역 고정` 배지를 렌더하지 않는다. 이 변경 뒤 AI 탭의 모든 키가 테넌트 재정의 가능해져 배지가 전부 같은 문구가 되고, 같은 문구가 반복되면 구별에 쓰이지 않는다. **다른 탭의 배지는 그대로 둔다.**

"직접 설정한 조직에는 적용되지 않습니다" 같은 안내도 넣지 않는다.

- [ ] **Step 4: 게이트를 돌린다**

Run: `cd apps/firehub-admin && pnpm typecheck && npx eslint src/lib/ai-credential.ts src/pages/settings/AiCredentialSection.tsx src/lib/settings-catalog.ts src/pages/SettingsPage.tsx`
Expected: PASS, exit 0

- [ ] **Step 5: E2E**

`apps/firehub-admin/e2e/settings.spec.ts` 에 추가: 유형이 AI 탭 **맨 앞**에 온다 / 유형을 `opencode` 로 바꾸면 공급자·기본 URL 이 나타나고 OAuth 토큰이 사라진다 / AI 탭에 재정의 배지가 없다 / 이메일 탭에는 여전히 있다.

시스템 Chrome 우회로 실행하고 `playwright.config.ts` 원복을 증명한다.

- [ ] **Step 6: 뮤테이션 검사**

유형별 필터를 제거해 모든 필드를 항상 렌더 → 2번 시나리오 RED. 복구 후 GREEN. 보고.

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-admin/src/ apps/firehub-admin/e2e/
git commit --no-verify -m "feat(admin): AI 탭을 유형별 전용 섹션으로 바꾸고 배지를 뺀다"
```

---

## Task 14: 통합 확인과 문서

**Files:**
- Modify: `docs/superpowers/specs/2026-09-19-typed-ai-settings-design.md` (구현 중 드러난 차이 반영)
- Modify: `apps/firehub-api/CLAUDE.md`, `apps/firehub-web/CLAUDE.md` (해당되면)

- [ ] **Step 1: 전체 게이트**

```bash
cd apps/firehub-api && ./gradlew test -x generateJooq
cd apps/firehub-web && pnpm vitest run && pnpm typecheck
cd apps/firehub-ai-agent && pnpm vitest run && pnpm typecheck
cd apps/firehub-admin && pnpm typecheck
```
전부 PASS 여야 한다. `pnpm lint` 전체는 main 에서 이미 9건 실패하므로 **변경 파일만** `npx eslint` 로 확인한다.

- [ ] **Step 2: 수동 확인 시나리오**

`pnpm dev:full` 로 띄우고: 테넌트에서 `직접 설정` → `opencode` → 공급자·URL·키 입력 → 모델 불러오기 → 저장 → AI 채팅이 그 공급자로 나가는지 로그로 확인 → 파이프라인 `AI_CLASSIFY` 스텝이 도는지 확인(이것이 이번 작업의 핵심 — 예전에는 조용히 플랫폼 키로 돌았다).

- [ ] **Step 3: 스펙 갱신**

구현 중 스펙과 달라진 결정을 스펙에 반영한다. 달라진 게 없으면 그렇게 보고한다.

- [ ] **Step 4: 커밋**

```bash
git add docs/superpowers/specs/2026-09-19-typed-ai-settings-design.md
git commit --no-verify -m "docs(spec): 구현 결과 반영"
```

---

## 실행 순서와 의존

```
1 → 2 → 3 → 4 → 5        (백엔드 저장·해석·마이그레이션)
        ↘ 6 → 7          (프로브·엔드포인트)
8, 9 는 1~5 와 독립 (에이전트) — 단 4 가 싣는 바디 키와 9 가 읽는 키를 맞춰야 한다
7 → 10 → 11 → 12         (웹)
7 → 13                   (어드민)
14 는 마지막
```

**주의**: Task 4 가 요청 바디에 싣는 키 이름(`providerId`/`baseUrl`/`apiKey`/`reasoningEffort`)과 Task 9 가 읽는 키 이름이 **정확히 같아야 한다.** 둘 중 하나를 먼저 하는 구현자는 상대의 「Interfaces」 블록을 그대로 따른다.

## 범위 밖 (이 계획에서 하지 않는다)

- Claude 계열 추론 강도 — `firehub-ai-agent` 에 전달 경로가 없고 모델마다 형식이 다르다. 별도 이슈.
- `enabled` 플래그, 테넌트 재정의 허용 토글.
- SMTP 후속 3건(`useSmtpSettingsForm.ts:346` 의 `failedLabels` 폐기 등).
- 3단계 `ai.classify_model`.
- 옛 3키 삭제 — 한 릴리스 뒤 별도 마이그레이션.
