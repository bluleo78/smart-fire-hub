# 테넌트별 AI 설정 오버라이드 (Phase A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ai.api_key` / `ai.cli_oauth_token` / `ai.agent_type` 3키를 테넌트가 자기 값으로 덮어쓸 수 있게 하되, 세 키가 원자적으로 해석되어 "테넌트가 고른 실행 형태 + 플랫폼 키"라는 과금 유출이 생기지 않게 한다.

**Architecture:** 해석 배선(`getValue`의 테넌트→플랫폼 상속, `tenant_settings` RLS, 암호화, 비동기 테넌트 컨텍스트)은 이미 전부 있다. 실질 작업은 ① SMTP 연결 번들과 동형의 **AI 자격증명 번들**을 만들고, ② 번들 키의 단일 조회를 막고 묶음 접근자로 이관한 뒤, ③ 그제서야 화이트리스트를 여는 것이다. **순서가 안전 속성이다** — 번들 없이 화이트리스트를 먼저 열면 그 사이에 유출 상태가 배포 가능해진다.

**Tech Stack:** Java 21 / Spring Boot / jOOQ / JUnit 5 + AssertJ, React + TypeScript / Vitest / Playwright

**Spec:** `docs/superpowers/specs/2026-09-18-tenant-scoped-ai-settings-design.md`

## Global Constraints

- **한국어 주석 필수** — 클래스·메서드·주요 로직에 무엇을·왜 설명 (CLAUDE.md).
- **커밋은 각 Task 마지막 단계에서만.** 배포는 하지 않는다.
- **테스트 필수** — backend 변경은 TC, frontend 변경은 Playwright E2E.
- 번들 채움 값은 스펙 결정 E 표를 그대로 따른다: `ai.api_key` → `""`, `ai.cli_oauth_token` → `""`, `ai.agent_type` → `"sdk"`.
- 화이트리스트 편집 시 **테넌트 허용 ⊆ 플랫폼 쓰기 가능** 불변식을 깨지 않는다. 3키 모두 `SettingsService.ALLOWED_AI_KEYS`에 이미 있으므로 유지된다.
- Phase A는 DB 마이그레이션이 없다. 스키마를 건드리는 작업이 나오면 Phase B 소관이므로 멈추고 보고한다.
- 워크트리에 `node_modules`가 없으면 pre-commit 훅이 `turbo: command not found`로 실패한다. **Task 0에서 `pnpm install`을 먼저 돌린다.**

---

### Task 0: 개발 환경 준비

**Files:**
- 없음 (환경 설치만)

**Interfaces:**
- Consumes: 없음
- Produces: `pnpm test` / `pnpm lint` / pre-commit 훅이 동작하는 워크트리

- [ ] **Step 1: 의존성 설치**

```bash
pnpm install
```

- [ ] **Step 2: 훅이 요구하는 명령이 동작하는지 확인**

Run: `pnpm typecheck`
Expected: 통과 (또는 이 변경과 무관한 기존 오류만). `turbo: command not found`가 사라져야 한다.

- [ ] **Step 3: 백엔드 테스트가 도는지 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests '*SettingsOverridePolicyTest*'`
Expected: PASS (3 tests)

설치가 멈추거나 Playwright 브라우저 내려받기에서 정지하면 **거기서 멈추고 보고한다** — 이 저장소에 그 전례가 있다. 백엔드 Task(1~5)는 `node_modules` 없이도 진행 가능하므로, 프론트 Task(6~7) 직전에 다시 시도한다.

---

### Task 1: AI 자격증명 번들 — 원자적 해석

세 키가 함께 움직이게 만든다. 아직 화이트리스트는 열지 않으므로 **동작 변화가 없다** — 규칙만 먼저 세운다.

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/settings/service/SettingsService.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/settings/SettingsResolutionTest.java`

**Interfaces:**
- Consumes: 기존 `SMTP_CONNECTION_KEYS`, `BUNDLE_FILL_VALUES`, `applySmtpConnectionBundle`, `resolveOverridesByPrefix`
- Produces:
  - `static final Set<String> AI_CREDENTIAL_KEYS` (패키지 가시성 — 불변식 테스트가 읽는다)
  - `private static void applyAiCredentialBundle(Map<String,String> overrides)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`SettingsResolutionTest`에 추가한다. 이 테스트는 **결정 E가 막는 과금 유출을 고정한다.**

```java
  @Test
  void AI_자격증명_번들_agent_type만_저장하면_api_key는_플랫폼값이_아니라_빈값이다() {
    // 결정 E: "테넌트가 고른 실행 형태 + 플랫폼 키" 조합이 과금 주체를 섞는다.
    // web 은 바뀐 키만 전송하므로 ADMIN 이 실행 형태만 바꾸면 이 상태가 저절로 만들어진다.
    runInTenantTransaction(
        transactionTemplate, testTenant, () -> tenantSettingsRepository.upsert("ai.agent_type", "sdk", null));

    Map<String, String> resolved =
        runInTenantTransaction(transactionTemplate, testTenant, () -> settingsService.getAsMap("ai"));

    assertThat(resolved).containsEntry("ai.agent_type", "sdk");
    assertThat(resolved)
        .as("플랫폼 API 키가 테넌트의 실행 형태와 섞이면 안 된다")
        .containsEntry("ai.api_key", "");
    assertThat(resolved).containsEntry("ai.cli_oauth_token", "");
  }

  @Test
  void AI_자격증명_번들_api_key만_저장하면_agent_type은_sdk로_채워진다() {
    // agent_type 은 자격증명이 아니라 실행 형태 선택자다. 빈 값은 안전하지도 의미 있지도 않고,
    // sdk 는 API 키·OAuth 어느 쪽으로도 인증되므로 "키만 넣은" 테넌트에 맞는 기본값이다.
    runInTenantTransaction(
        transactionTemplate, testTenant, () -> tenantSettingsRepository.upsert("ai.api_key", "tenant-key", null));

    Map<String, String> resolved =
        runInTenantTransaction(transactionTemplate, testTenant, () -> settingsService.getAsMap("ai"));

    assertThat(resolved).containsEntry("ai.api_key", "tenant-key");
    assertThat(resolved).containsEntry("ai.agent_type", "sdk");
    assertThat(resolved).containsEntry("ai.cli_oauth_token", "");
  }

  @Test
  void AI_자격증명_번들_아무것도_저장하지_않으면_3키_모두_플랫폼_상속이다() {
    // 번들은 "하나라도 있으면" 발동한다. 하나도 없으면 아무것도 하지 않는다 —
    // 미설정 테넌트의 동작이 바뀌지 않아야 한다.
    Map<String, String> resolved =
        runInTenantTransaction(transactionTemplate, testTenant, () -> settingsService.getAsMap("ai"));

    assertThat(resolved).containsEntry("ai.agent_type", "sdk"); // V15 시드 플랫폼 값
    assertThat(resolved.get("ai.api_key")).isNotEqualTo("");
  }
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd apps/firehub-api && ./gradlew test --tests '*SettingsResolutionTest*'`
Expected: 앞의 두 테스트가 FAIL — `ai.api_key`가 빈 값이 아니라 플랫폼 암호문이고, `ai.agent_type`이 채워지지 않는다. (세 번째는 이미 PASS — 현행 동작이다.)

- [ ] **Step 3: 번들 상수와 채움을 구현한다**

`SMTP_CONNECTION_KEYS` 선언 바로 아래에 추가한다:

```java
  /**
   * AI <b>자격증명 번들 3키</b>. {@link #SMTP_CONNECTION_KEYS} 와 같은 이유로 원자적으로 해석한다 —
   * 저쪽이 막은 것이 "테넌트 호스트 + 플랫폼 자격증명"이라면, 이쪽이 막는 것은 <b>"테넌트가 고른
   * 실행 형태 + 플랫폼 키"</b>다. 후자는 과금 주체가 섞이는 형태라 더 직접적이다.
   *
   * <p>구체적 시나리오: 테넌트 ADMIN 이 실행 형태만 {@code sdk} 로 바꿔 저장한다. API 키 필드는
   * 마스킹된 채 채워져 보이므로(플랫폼 값이 폼에 시드된다) 손댈 이유가 없고, web 은 <b>바뀐 키만</b>
   * 전송하므로 {@code ai.api_key} 테넌트 행이 생기지 않는다. 번들이 없으면 그 테넌트의 AI 사용료를
   * 플랫폼이 낸다.
   */
  static final Set<String> AI_CREDENTIAL_KEYS =
      Set.of("ai.api_key", "ai.cli_oauth_token", "ai.agent_type");
```

`BUNDLE_FILL_VALUES` 를 두 항목으로 바꾼다 (기존 `Map.of("smtp.starttls", "true")`):

```java
  private static final Map<String, String> BUNDLE_FILL_VALUES =
      Map.of(
          "smtp.starttls", "true",
          // agent_type 은 자격증명이 아니라 실행 형태 선택자라 빈 값이 안전한 방향이 아니다
          // (빈 값은 AiAgentProxyService 에서 cli-api 분기로 떨어진다 — Task 4 가 고치는 결함).
          // sdk 는 API 키·OAuth 어느 쪽으로도 인증되므로 "키만 넣은" 테넌트에 맞고,
          // 기존 코드 기본값(getOrDefault("ai.agent_type","sdk"))과도 일치한다.
          "ai.agent_type", "sdk");
```

`applySmtpConnectionBundle` 아래에 추가한다:

```java
  /**
   * AI 자격증명 3키를 <b>원자적으로</b> 해석한다. 규칙·근거·주의사항은 전부
   * {@link #applySmtpConnectionBundle} 과 같으므로 그쪽 javadoc 을 함께 읽어야 한다 —
   * 특히 <b>채움이 화이트리스트를 다시 보지 않는다</b>는 점과, 그 대가를
   * {@code SettingsKeyWhitelistInvariantTest} 가 진다는 점이 동일하다.
   */
  private static void applyAiCredentialBundle(Map<String, String> overrides) {
    boolean bundleOverridden = overrides.keySet().stream().anyMatch(AI_CREDENTIAL_KEYS::contains);
    if (!bundleOverridden) return;
    AI_CREDENTIAL_KEYS.forEach(
        key -> overrides.putIfAbsent(key, BUNDLE_FILL_VALUES.getOrDefault(key, "")));
  }
```

`resolveOverridesByPrefix` 에서 SMTP 번들 바로 다음 줄에 호출을 넣는다:

```java
  private Map<String, String> resolveOverridesByPrefix(String prefix) {
    if (TenantContext.get() == null) return Map.of();
    Map<String, String> candidates = tenantSettingsRepository.findByPrefix(prefix);
    candidates.keySet().removeIf(key -> !SettingsOverridePolicy.isTenantOverridable(key));
    applySmtpConnectionBundle(candidates);
    applyAiCredentialBundle(candidates);
    return candidates;
  }
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `cd apps/firehub-api && ./gradlew test --tests '*SettingsResolutionTest*'`
Expected: PASS

주의 — 이 시점에는 3키가 아직 화이트리스트에 없어 `removeIf`가 걸러내므로 **번들이 발동하지 않는다.** 위 테스트는 `tenantSettingsRepository.upsert`로 행을 직접 넣지만 `removeIf`에서 탈락한다. 그래서 **Task 1의 테스트는 Task 3까지 가야 진짜로 통과한다.** 여기서 FAIL이 남으면 Task 3 이후 다시 확인하고, Step 5 커밋은 그대로 진행한다(규칙이 먼저, 개방이 나중이라는 순서가 의도다).

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/settings/service/SettingsService.java \
        apps/firehub-api/src/test/java/com/smartfirehub/settings/SettingsResolutionTest.java
git commit -m "feat(settings): AI 자격증명 3키 원자 해석 번들 추가

테넌트가 ai.agent_type 만 바꾸면 web 이 바뀐 키만 전송하므로 ai.api_key
행이 생기지 않고, 그 결과 테넌트가 고른 실행 형태로 플랫폼 키가 과금된다.
SMTP 연결 번들과 동형의 규칙으로 3키를 함께 해석한다.

아직 화이트리스트를 열지 않았으므로 동작 변화는 없다 — 규칙을 먼저 세우고
개방은 뒤에 한다."
```

---

### Task 2: 번들 키 단일 조회 차단 + 묶음 접근자

`getValue`(키 하나)는 "3키가 함께 움직인다"를 원리적으로 판정할 수 없다. SMTP가 그래서 단일 조회를 거부하고 `getSmtpConfig()`로 강제한다. AI도 같게 만든다.

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/settings/service/SettingsService.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/ai/controller/AiController.java:93-99`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobAsyncRunner.java:125-131`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/ai/service/AiAgentProxyService.java:173-177`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/executor/AiAgentClient.java:82-89`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/settings/SettingsResolutionTest.java`

**Interfaces:**
- Consumes: Task 1의 `AI_CREDENTIAL_KEYS`
- Produces:
  - `public record AiCredentials(String agentType, String apiKey, String cliOauthToken)` — `apiKey`·`cliOauthToken`은 **복호화된** 값이고, 미설정은 `null`이 아니라 **빈 문자열**이다.
  - `public AiCredentials getAiCredentials()` — `@Transactional(readOnly = true)`
  - `getDecryptedApiKey()` / `getDecryptedCliOauthToken()` 는 **제거된다.** 호출부는 전부 `getAiCredentials()` 로 이관한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
  @Test
  void AI_번들키는_단일_조회를_거부한다() {
    // 단일 키 경로는 "3키가 함께 움직인다"를 판정할 수 없다 — SMTP 연결 키와 같은 이유로 막는다.
    // 막지 않으면 getAsMap("ai") 는 ""(안전)를 주는데 getValue 는 플랫폼 암호문(상속)을 주어
    // 두 경로가 서로 다른 답을 낸다.
    assertThatThrownBy(() -> settingsService.getValue("ai.api_key"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("getAiCredentials()");
    assertThatThrownBy(() -> settingsService.getValue("ai.agent_type"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> settingsService.getValue("ai.cli_oauth_token"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void AI_번들이_아닌_키는_단일_조회가_그대로_동작한다() {
    // ai.model 은 번들이 아니라 키 단위 상속이므로 getValue 가 옳은 답을 준다.
    assertThat(settingsService.getValue("ai.model")).isPresent();
  }

  @Test
  void getAiCredentials_는_번들_해석을_따른다() {
    runInTenantTransaction(
        transactionTemplate, testTenant, () -> tenantSettingsRepository.upsert("ai.agent_type", "opencode", null));

    SettingsService.AiCredentials creds =
        runInTenantTransaction(transactionTemplate, testTenant, () -> settingsService.getAiCredentials());

    assertThat(creds.agentType()).isEqualTo("opencode");
    assertThat(creds.apiKey()).as("플랫폼 키가 새면 안 된다").isEmpty();
    assertThat(creds.cliOauthToken()).isEmpty();
  }
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd apps/firehub-api && ./gradlew test --tests '*SettingsResolutionTest*'`
Expected: FAIL — `getValue`가 예외를 던지지 않고, `getAiCredentials` 가 존재하지 않아 컴파일 에러.

- [ ] **Step 3: 차단과 접근자를 구현한다**

`rejectBundleKey` 를 확장한다:

```java
  private static void rejectBundleKey(String key) {
    if (SMTP_CONNECTION_KEYS.contains(key)) {
      throw new IllegalArgumentException(
          "SMTP 연결 설정은 단일 키로 해석할 수 없습니다(연결 5키는 함께 해석된다). getSmtpConfig() 를 쓰세요: " + key);
    }
    if (AI_CREDENTIAL_KEYS.contains(key)) {
      throw new IllegalArgumentException(
          "AI 자격증명은 단일 키로 해석할 수 없습니다(3키는 함께 해석된다). getAiCredentials() 를 쓰세요: " + key);
    }
  }
```

`getDecryptedApiKey()` / `getDecryptedCliOauthToken()` 를 **지우고** 그 자리에 넣는다:

```java
  /**
   * AI 자격증명 3키를 <b>번들 규칙대로</b> 해석해 함께 돌려준다.
   *
   * <p>{@code getDecryptedApiKey()} / {@code getDecryptedCliOauthToken()} 를 대체한다. 그 둘은
   * {@link #getValue} 기반이라 번들 규칙을 지날 수 없었다 — 키를 하나만 보는 경로는 "3키가 함께
   * 움직인다"를 판정할 수 없기 때문이고, 근거는 {@link #rejectBundleKey} 에 있다.
   *
   * <p>미설정은 {@code null} 이 아니라 <b>빈 문자열</b>이다. 번들 채움이 빈 문자열을 쓰므로
   * 두 경로(채움/미설정)가 같은 모양이어야 호출부가 분기를 하나만 갖는다.
   */
  @Transactional(readOnly = true)
  public AiCredentials getAiCredentials() {
    Map<String, String> ai = getAsMap("ai");
    String agentType = ai.getOrDefault("ai.agent_type", "sdk");
    if (agentType.isBlank()) agentType = "sdk"; // 빈 값 정규화 — Task 4 와 같은 이유
    return new AiCredentials(
        agentType,
        decryptOrEmpty(ai.get("ai.api_key")),
        decryptOrEmpty(ai.get("ai.cli_oauth_token")));
  }

  /** 암호문을 복호화하되 미설정(null/빈 값)은 빈 문자열로 통일한다. */
  private String decryptOrEmpty(String encrypted) {
    if (encrypted == null || encrypted.isBlank()) return "";
    return encryptionService.decrypt(encrypted);
  }

  /**
   * AI 자격증명 묶음. 세 값은 <b>항상 같은 평면</b>에서 왔다(번들 규칙) — 섞여 있지 않다는 것이
   * 이 타입이 존재하는 이유다. 빈 문자열은 "설정되지 않음"을 뜻한다.
   */
  public record AiCredentials(String agentType, String apiKey, String cliOauthToken) {
    /** sdk/cli 에서 OAuth 우선 판정에 쓴다. */
    public boolean hasOauthToken() {
      return !cliOauthToken.isBlank();
    }

    /** cli-api/sdk 에서 API 키 존재 판정에 쓴다. */
    public boolean hasApiKey() {
      return !apiKey.isBlank();
    }
  }
```

- [ ] **Step 4: 호출부 4곳을 이관한다**

`AiController.getAuthStatus()`:

```java
    // 테넌트가 자기 AI 를 고를 수 있게 되면서(P#) 이 검증은 **호출자의 테넌트 기준**이 됐다.
    // 자격증명 3키는 번들로 함께 해석되므로 단일 키 조회가 아니라 묶음 접근자를 쓴다.
    SettingsService.AiCredentials creds = settingsService.getAiCredentials();
    boolean useTokenVerification =
        "cli".equals(creds.agentType())
            || ("sdk".equals(creds.agentType()) && creds.hasOauthToken());
```

`ProactiveJobAsyncRunner` (기존 3줄 `apiKey`/`agentType`/`oauthToken` 조회를 대체):

```java
      // 배경 잡이지만 TenantScopedRunner 가 테넌트 컨텍스트를 세워 두므로 **그 잡 소유 테넌트의**
      // 자격증명으로 해석된다. 3키는 번들로 함께 온다 — 섞여 있지 않다.
      SettingsService.AiCredentials creds = settingsService.getAiCredentials();
      String agentType = creds.agentType();
      String apiKey = creds.apiKey();
      // cli 또는 sdk 에서만 구독 OAuth 토큰을 전달한다(sdk 는 OAuth 우선).
      String oauthToken =
          ("cli".equals(agentType) || "sdk".equals(agentType)) && creds.hasOauthToken()
              ? creds.cliOauthToken()
              : null;
```

`AiAgentProxyService` — `agentType`은 이미 `getAsMap("ai")`(프리픽스, 번들 안전)에서 오므로 그대로 두고, 단일 키 조회 두 줄만 바꾼다:

```java
    SettingsService.AiCredentials creds = settingsService.getAiCredentials();
    Optional<String> apiKeyOpt = creds.hasApiKey() ? Optional.of(creds.apiKey()) : Optional.empty();
    Optional<String> cliTokenOpt =
        ("cli".equals(agentType) || "sdk".equals(agentType)) && creds.hasOauthToken()
            ? Optional.of(creds.cliOauthToken())
            : Optional.empty();
```

`AiAgentClient.classify()` — 자격증명 주입 두 줄을 바꾼다. **`agentType` 분기를 두지 않는다**:

```java
      // 자격증명은 번들로 함께 해석된다. agentType 분기를 두지 않는 것이 핵심이다 —
      // 분류는 opencode 로 라우팅되지 않는다(provider-factory.ts 의 createCompletionProvider 는
      // agentType 분기 없이 SDK 경로로 통일한다). 여기서 opencode 라고 키를 빼면 ai-agent 컨테이너의
      // ambient 자격증명으로 떨어져, 이 밴드가 막으려는 과금 혼입이 그대로 재현된다.
      var creds = settingsService.getAiCredentials();
      if (creds.hasApiKey()) body.put("apiKey", creds.apiKey());
      if (creds.hasOauthToken()) body.put("oauthToken", creds.cliOauthToken());
```

> **정정(2026-09-18).** 이 계획의 초판은 여기에 `if (!"opencode".equals(creds.agentType()))` 가드를 두고
> "채팅 경로와 규칙을 맞춘다"고 적었다. **틀렸다.** 채팅(`AiAgentProxyService`)은 실제로 opencode 로
> 라우팅되므로 그쪽 규칙은 옳지만, 분류는 그렇지 않다. 브랜치 전체 코드 리뷰가 Major 로 잡아
> `6b1c6383` 에서 제거했다. 같은 가드를 다시 넣지 말 것 — 되돌리면
> `AiAgentClientTest.classify_opencode_stillSendsCredentialsWhenPresent` 가 붉어진다.

- [ ] **Step 5: 전체 테스트를 돌려 이관 누락을 잡는다**

Run: `cd apps/firehub-api && ./gradlew test`
Expected: PASS. `getDecryptedApiKey` / `getDecryptedCliOauthToken` 를 부르는 곳이 남아 있으면 **컴파일 에러**로 드러난다 — 그것이 이 단계에서 메서드를 남기지 않고 지우는 이유다. `AiAgentClientTest`·`AiAgentProxyServiceTest`·`SettingsServiceCliTokenTest` 의 목(mock) 스텁도 새 접근자로 바꿔야 한다.

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-api/src
git commit -m "refactor(settings): AI 자격증명을 묶음 접근자로 이관

getValue 기반 단일 키 조회는 번들 규칙을 지날 수 없다 — 키 하나만 보는
경로는 '3키가 함께 움직인다'를 판정할 수 없다. SMTP 연결 키와 같은 이유로
단일 조회를 거부하고 getAiCredentials() 로 강제한다.

getDecryptedApiKey/getDecryptedCliOauthToken 을 남기지 않고 지워 이관
누락이 컴파일 에러로 드러나게 한다."
```

---

### Task 3: 화이트리스트 개방

번들 규칙이 자리를 잡은 뒤에야 연다.

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/settings/service/SettingsOverridePolicy.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/settings/SettingsOverridePolicyTest.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/settings/service/SettingsKeyWhitelistInvariantTest.java`

**Interfaces:**
- Consumes: Task 1의 `AI_CREDENTIAL_KEYS`
- Produces: `tenantOverridableKeys()` 가 15키를 돌려준다 (ai 9 + smtp 6)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`SettingsOverridePolicyTest` 의 `오버라이드_허용_키는_12개다` 를 대체한다:

```java
  @Test
  void 오버라이드_허용_키는_15개다() {
    // P7-c1(2026-08-22): smtp.* 6키 재분류. 이번: ai 자격증명·실행형태 3키 개방.
    assertThat(SettingsOverridePolicy.tenantOverridableKeys())
        .containsExactlyInAnyOrder(
            "ai.system_prompt", "ai.model", "ai.temperature",
            "ai.max_turns", "ai.max_tokens", "ai.session_max_tokens",
            "ai.api_key", "ai.cli_oauth_token", "ai.agent_type",
            "smtp.host", "smtp.port", "smtp.username",
            "smtp.password", "smtp.starttls", "smtp.from_address");
  }
```

`플랫폼_잠금_키는_거부된다` 를 고친다 — AI 자격증명은 더 이상 잠금이 아니다:

```java
  @Test
  void 플랫폼_잠금_키는_거부된다() {
    // 이제 플랫폼 잠금은 embedding.* 4키뿐이다. embedding.model 은 벡터 차원을 바꿔 기존
    // 임베딩을 무효화하므로 Phase B 가 차원별 컬럼을 넣을 때까지 잠겨 있다.
    assertThat(SettingsOverridePolicy.isTenantOverridable("embedding.model")).isFalse();
    assertThat(SettingsOverridePolicy.isTenantOverridable("embedding.api_key")).isFalse();
  }
```

`SettingsKeyWhitelistInvariantTest` 에 번들 불변식을 추가한다:

```java
  /**
   * AI 자격증명 번들 3키는 전부 테넌트 오버라이드 허용 키여야 한다. 근거는 바로 위
   * {@code 연결_번들_5키는_...} 와 같다 — 하나라도 플랫폼으로 회수되면 채움이 그 키를 되살려
   * {@code overridden=true} + {@code tenantEditable=false} 라는 모순 조합이 나오고, web 이
   * fail-closed 로 읽어 그룹 전체를 조용히 잠근다.
   */
  @Test
  void AI_자격증명_번들_3키는_전부_테넌트_오버라이드_허용키다() {
    assertThat(SettingsOverridePolicy.tenantOverridableKeys())
        .as("번들 키가 플랫폼으로 회수되면 채움이 그 키를 되살려 그룹 전체가 조용히 잠긴다")
        .containsAll(SettingsService.AI_CREDENTIAL_KEYS);
  }
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd apps/firehub-api && ./gradlew test --tests '*SettingsOverridePolicyTest*' --tests '*SettingsKeyWhitelistInvariantTest*'`
Expected: FAIL — 허용 키가 12개이고 번들 3키가 목록에 없다.

- [ ] **Step 3: 화이트리스트를 연다**

`SettingsOverridePolicy.TENANT_OVERRIDABLE` 의 `"ai.session_max_tokens",` 다음 줄에 추가한다:

```java
          // 테넌트별로 어떤 AI 를 쓰는가가 제품의 핵심 요구다. 과금 주체가 섞이지 않는 것은
          // 화이트리스트가 아니라 SettingsService 의 AI 자격증명 번들이 보장한다 —
          // 세 키는 항상 같은 평면에서 함께 해석된다.
          "ai.api_key",
          "ai.cli_oauth_token",
          "ai.agent_type",
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다 (Task 1 테스트 포함)**

Run: `cd apps/firehub-api && ./gradlew test`
Expected: PASS — **Task 1에서 미뤄둔 번들 테스트 3개가 여기서 비로소 진짜로 통과한다.** 통과하지 않으면 번들 호출이 `resolveOverridesByPrefix` 에 실제로 들어갔는지 확인한다.

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-api/src
git commit -m "feat(settings): AI 자격증명·실행형태 3키를 테넌트 오버라이드로 개방

ai.api_key / ai.cli_oauth_token / ai.agent_type 을 테넌트가 자기 값으로
덮어쓸 수 있게 한다. 과금 주체가 섞이지 않는 것은 앞 커밋의 번들 규칙이
보장하므로, 개방을 그 뒤에 둔다."
```

---

### Task 4: 빈 `ai.agent_type` 정규화 (선재 결함)

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/ai/service/AiAgentProxyService.java:172`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/ai/service/AiAgentProxyServiceTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: 없음 (동작 수정)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`streamChat_sdkWithOauthToken_injectsOauthTokenIntoBody` 바로 아래에 같은 WireMock 패턴으로 추가한다:

```java
  /**
   * 빈 {@code ai.agent_type} 이 sdk 로 정규화되는지 검증한다.
   *
   * <p>{@code getOrDefault} 는 키가 <b>존재하면</b> 빈 문자열도 그대로 준다. 그래서 ""가 저장돼
   * 있으면 sdk 폴백으로 가지 않고 else 분기(cli-api)로 떨어져 API 키를 요구한다. 지금까지는 값이
   * 항상 시드돼 있어 드러나지 않았으나, 테넌트 평면이 열리면서 빈 값 저장 경로가 늘어난다.
   *
   * <p>sdk 로 정규화되면 OAuth 토큰만으로 인증이 성립하므로 ai-agent 호출이 실제로 나간다 —
   * WireMock 이 그 요청을 받는 것으로 정규화를 확인한다(오류 emit 여부보다 직접적이다).
   */
  @Test
  void streamChat_blankAgentType_normalizesToSdk() {
    // given: agent_type 이 빈 문자열, OAuth 토큰만 설정
    when(settingsService.getAsMap("ai"))
        .thenReturn(Map.of("ai.agent_type", "", "ai.model", "claude-sonnet-5"));
    when(settingsService.getAiCredentials())
        .thenReturn(new SettingsService.AiCredentials("", "", "oat-test"));
    wireMock.stubFor(
        post(urlEqualTo("/agent/chat"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody("event: done\ndata: {}\n\n")));

    // when
    SseEmitter emitter = new SseEmitter();
    aiAgentProxyService.streamChat(emitter, "hi", null, List.of(), 1L, null, null);

    // then: cli-api 분기로 떨어졌다면 자격증명 부족으로 호출 자체가 나가지 않는다.
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(() -> wireMock.verify(postRequestedFor(urlEqualTo("/agent/chat"))));
  }
```

**주의:** 이 파일은 `@MockitoBean SettingsService` 를 쓰는 WireMock 통합 테스트다. Task 2에서 `getDecryptedApiKey()` / `getDecryptedCliOauthToken()` 를 지우므로, **이 파일의 기존 스텁 두 줄도 함께 고쳐야 컴파일된다**:

```java
    // 기존 (Task 2 에서 제거됨):
    //   when(settingsService.getDecryptedCliOauthToken()).thenReturn(Optional.of("oat-test"));
    //   when(settingsService.getDecryptedApiKey()).thenReturn(Optional.empty());
    // 대체:
    when(settingsService.getAiCredentials())
        .thenReturn(new SettingsService.AiCredentials("sdk", "", "oat-test"));
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd apps/firehub-api && ./gradlew test --tests '*AiAgentProxyServiceTest*'`
Expected: FAIL — 빈 값이 `cli-api` 분기로 떨어져 "AI API 키가 설정되지 않았습니다" 오류가 emit 된다.

- [ ] **Step 3: 정규화를 넣는다**

```java
    // getOrDefault 는 키가 존재하면 빈 문자열도 그대로 준다 — sdk 폴백으로 가지 않고 else
    // 분기(cli-api)로 떨어져 엉뚱하게 API 키를 요구한다. 테넌트 평면이 열리면서 빈 값 저장
    // 경로가 늘어나므로 여기서 명시적으로 정규화한다.
    String agentType = aiSettings.getOrDefault("ai.agent_type", "sdk");
    if (agentType.isBlank()) agentType = "sdk";
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `cd apps/firehub-api && ./gradlew test --tests '*AiAgentProxyServiceTest*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-api/src
git commit -m "fix(ai): 빈 ai.agent_type 이 cli-api 분기로 떨어지던 문제

getOrDefault 는 키가 존재하면 빈 문자열도 값으로 준다. 지금까지는 값이
항상 시드돼 있어 드러나지 않았으나, 테넌트 평면이 열리면서 빈 값 저장
경로가 늘어난다."
```

---

### Task 5: 낡은 가정 정정

이 변경이 **거짓으로 만드는 문장**들이 주석에 박혀 있다. 동작은 이미 옳게 바뀌었으므로 여기서 고치는 것은 문서다 — 그러나 다음 사람이 그 문장을 믿고 판단하면 사고가 난다.

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobAsyncRunner.java:118-124`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/ai/controller/AiController.java:89-92`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/settings/service/SettingsService.java:28-34`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/settings/service/SettingsOverridePolicy.java` (클래스 javadoc)

**Interfaces:**
- Consumes: 없음
- Produces: 없음 (주석만)

- [ ] **Step 1: `ProactiveJobAsyncRunner` 의 문장을 고친다**

*"게다가 그 키는 플랫폼 잠금이라 테넌트 오버라이드 해석이 원리적으로 결과를 바꾸지 않는다."* 를 지우고:

```java
      // 이 잡은 TenantScopedRunner 가 세운 테넌트 컨텍스트 안에서 돈다. ai.agent_type 과
      // 자격증명이 테넌트 오버라이드 허용으로 바뀌었으므로, **잡 소유 테넌트의 값**으로
      // 해석된다 — 예전 주석은 "플랫폼 잠금이라 오버라이드가 결과를 바꾸지 않는다"고 적고
      // 있었는데 더 이상 참이 아니다. 컨텍스트가 비면 플랫폼 값으로 폴백한다.
```

- [ ] **Step 2: `AiController.getAuthStatus()` 의 문장을 고친다**

```java
    // 자격증명 3키는 번들로 함께 해석되므로 묶음 접근자를 쓴다(단일 키 조회는 거부된다).
    // **의미 변경**: 이 엔드포인트는 이제 호출자의 테넌트 기준으로 검증한다 — 테넌트마다
    // 다른 AI 를 쓸 수 있으므로 "플랫폼 자격증명이 유효한가"가 더 이상 의미 있는 질문이 아니다.
```

- [ ] **Step 3: `SettingsService` 클래스 javadoc 을 고친다**

*"{@code ai.api_key}/{@code ai.agent_type}/{@code ai.cli_oauth_token} 은 여기 있지만 테넌트 화이트리스트에는 없다(과금 주체·실행 형태라 플랫폼이 갖는다)."* 를 대체:

```
   * <p>AI 자격증명 3키는 이제 <b>테넌트 화이트리스트에도 있다</b>. 과금 주체가 섞이지 않는 것은
   * 화이트리스트가 아니라 {@link #AI_CREDENTIAL_KEYS} 번들이 보장한다. 남은 플랫폼 전용은
   * {@code embedding.*} 4키뿐이다(벡터 차원 — Phase B).
```

- [ ] **Step 4: `SettingsOverridePolicy` 클래스 javadoc 의 "여기 없는 7키" 를 고친다**

```
   * <p>여기 없는 <b>4키</b>가 플랫폼 잠금인 이유: {@code embedding.*} 4키는 모델 변경이 벡터
   * 차원을 바꿔 <b>기존 임베딩 전량을 무효화</b>한다. 스키마의 {@code vector(1024)} 고정이 실제
   * 제약이므로, 차원별 컬럼을 넣는 Phase B 에서 함께 연다.
   *
   * <p><b>AI 자격증명 3키는 2026-09-18 에 여기로 재분류됐다.</b> "BYO 키 정책이 정해질 때까지
   * 플랫폼이 갖는다"는 한시적 보류였고, "테넌트별로 어떤 AI 를 쓰는가"가 제품의 핵심 요구로
   * 확정되면서 풀렸다. SMTP 6키(P7-c1)와 같은 경로다.
```

- [ ] **Step 5: 컴파일·테스트 확인 후 커밋**

Run: `cd apps/firehub-api && ./gradlew test`
Expected: PASS

```bash
git add apps/firehub-api/src
git commit -m "docs(settings): 플랫폼 잠금을 전제하던 주석 4곳 정정

ProactiveJobAsyncRunner 와 AiController 는 'ai.agent_type 은 플랫폼
잠금이라 테넌트 오버라이드 해석이 결과를 바꾸지 않는다'고 적고 있었다.
이번 변경이 그 문장을 거짓으로 만든다. /auth-status 는 이제 호출자의
테넌트 기준 검증이라는 의미 변경도 명시한다."
```

---

### Task 6: 프론트 화이트리스트 사본 + 자격증명 필드 노출

**Files:**
- Modify: `apps/firehub-web/src/lib/settings-fields.ts:32-45`
- Modify: `apps/firehub-web/src/lib/settings-fields.test.ts`
- Modify: `apps/firehub-web/src/pages/admin/SettingsPage.tsx:368-375`

**Interfaces:**
- Consumes: 서버가 내리는 `tenantEditable` 플래그 (실제 방어는 이것이고, 상수는 응답에 없는 키의 폴백 판정 전용)
- Produces: `TENANT_EDITABLE_KEYS` 15키

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`settings-fields.test.ts` 의 키 목록 단언을 15키로 바꾸고 추가한다:

```ts
  it('AI 자격증명 3키가 테넌트 편집 허용 목록에 있다', () => {
    // 번들 규칙상 셋이 함께 움직인다 — 하나라도 빠지면 서버의 번들 채움과 어긋나
    // overridden=true + tenantEditable=false 라는 모순 조합이 화면에 나타난다.
    expect(isTenantEditableKey('ai.api_key')).toBe(true);
    expect(isTenantEditableKey('ai.cli_oauth_token')).toBe(true);
    expect(isTenantEditableKey('ai.agent_type')).toBe(true);
  });

  it('embedding 키는 아직 플랫폼 잠금이다', () => {
    expect(isTenantEditableKey('embedding.model')).toBe(false);
  });
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `cd apps/firehub-web && pnpm vitest run src/lib/settings-fields.test.ts`
Expected: FAIL — 세 키가 목록에 없다.

- [ ] **Step 3: 상수를 고친다**

`TENANT_EDITABLE_KEYS` 의 `'ai.session_max_tokens',` 다음에 세 줄을 넣는다:

```ts
  'ai.api_key',
  'ai.cli_oauth_token',
  'ai.agent_type',
```

같은 파일 상단 javadoc 의 "12키"를 **"15키"** 로 고치고 한 문단을 덧붙인다:

```
 * <b>2026-09-18 에 12키 → 15키가 됐다</b>(AI 자격증명 3키 개방). 서버에서 이 세 키는
 * 번들로 함께 해석되므로, 셋 중 하나만 이 목록에 넣으면 폴백 판정이 서버와 어긋난다.
```

- [ ] **Step 4: `SettingsPage.tsx` 의 자격증명 분기를 고친다**

지금은 `ai.agent_type === 'opencode'` 일 때만 안내 문구로 대체하는 구조다. 플랫폼 전용 가정이 남아 있으면 제거하고, 필드는 서버의 `tenantEditable` 플래그(`fieldState`)를 그대로 따르게 둔다 — 이 파일의 다른 AI 필드가 이미 그 패턴이므로 **새 분기를 만들지 말고 기존 `SettingFieldLabel` + `fieldState('ai.api_key')` 패턴을 그대로 쓴다.**

번들이므로 안내를 하나 덧붙인다:

```tsx
{/* 번들 규칙 안내: 세 키는 서버에서 함께 해석된다. 하나만 바꿔 저장하면 나머지는
    플랫폼 값을 상속하는 것이 아니라 빈 값이 된다 — 과금 주체가 섞이지 않게 하려는
    의도이고, 사용자에게는 "키도 같이 넣어야 한다"로 보여야 한다. */}
<p className="text-sm text-muted-foreground">
  에이전트 유형과 인증 정보는 함께 저장됩니다. 하나만 변경하면 나머지는 비워진 것으로
  처리되어 플랫폼 기본값을 상속하지 않습니다.
</p>
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `cd apps/firehub-web && pnpm vitest run src/lib/settings-fields.test.ts && pnpm lint`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-web/src
git commit -m "feat(web): AI 자격증명 3키를 테넌트 설정 화면에 노출

백엔드 화이트리스트 개방에 사본을 맞추고, 번들 규칙(셋이 함께 저장된다)을
화면에 안내한다. 하나만 바꾸면 나머지가 빈 값이 되는 것은 과금 주체가
섞이지 않게 하려는 의도이므로 사용자에게 보여야 한다."
```

---

### Task 7: E2E — 테넌트가 자기 AI 를 고른다

**Files:**
- Create: `apps/firehub-web/e2e/pages/admin/tenant-ai-settings.spec.ts`

**Interfaces:**
- Consumes: Task 3·6의 결과
- Produces: 없음

- [ ] **Step 1: E2E 테스트를 쓴다**

```ts
import { test, expect } from '@playwright/test';

// 테넌트 ADMIN 이 자기 AI 자격증명을 저장하고, 마스킹된 채 다시 조회되는지 확인한다.
// 번들 규칙 때문에 "에이전트 유형만 바꾸면 키가 비워진다"는 것도 화면에서 확인한다.
test.describe('테넌트별 AI 설정', () => {
  test('테넌트 ADMIN 이 자기 API 키를 저장하면 마스킹되어 다시 보인다', async ({ page }) => {
    await page.goto('/admin/settings');
    // 라벨 텍스트는 SettingsPage.tsx 의 SettingFieldLabel 내용과 일치한다
    // (htmlFor="ai-api-key" → "API 키").
    await page.locator('#ai-api-key').fill('sk-tenant-own-key');
    await page.getByRole('button', { name: '저장' }).click();

    await page.reload();
    // 저장된 비밀은 평문으로 돌아오지 않는다(서버가 **** 로 마스킹해 내려준다).
    await expect(page.locator('#ai-api-key')).not.toHaveValue('sk-tenant-own-key');
  });

  test('에이전트 유형이 테넌트 편집 가능으로 보인다', async ({ page }) => {
    await page.goto('/admin/settings');
    // htmlFor="ai-agent-type" → "에이전트 유형". 플랫폼 잠금이던 시절에는
    // disabled={!isEditable('ai.agent_type')} 로 비활성이었다.
    await expect(page.locator('#ai-agent-type')).toBeEnabled();
  });
});
```

`id` 셀렉터를 쓰는 이유: `SettingFieldLabel` 이 `htmlFor` 로 연결하지만 라벨 텍스트("API 키")가 짧아 `getByLabel` 이 다른 필드와 충돌할 수 있다. 실제 id 는 `ai-api-key`, `ai-cli-oauth-token`, `ai-agent-type` 이다(`SettingsPage.tsx:344,379,423`).

로그인·테넌트 준비는 기존 `e2e/pages/admin/settings.spec.ts` 와 `e2e/factories/admin.factory.ts` 패턴을 그대로 따른다 — **새 픽스처를 만들지 말고 그 파일의 `test.beforeEach` 를 복사한다.**

- [ ] **Step 2: 실행한다**

Run: `cd apps/firehub-web && pnpm exec playwright test e2e/pages/admin/tenant-ai-settings.spec.ts`
Expected: PASS

브라우저 설치가 압축 해제 단계에서 멈추면 **거기서 멈추고 보고한다** — 이 환경에 그 전례가 있다. 그 경우 Task 7은 미완으로 남기고 나머지 완료 상태를 명확히 보고한다.

- [ ] **Step 3: 스크린샷을 규약 위치에 남긴다**

`test-results/tc/tenant-ai-settings/` (CLAUDE.md 규약)

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/e2e
git commit -m "test(e2e): 테넌트별 AI 설정 저장·마스킹 검증"
```

---

## 완료 조건

- [ ] `./gradlew test` 전체 통과
- [ ] `pnpm test` / `pnpm lint` 통과
- [ ] 테넌트 A 가 `ai.agent_type=opencode`, 테넌트 B 가 `sdk` 로 각자 해석되는 것이 테스트로 고정됨
- [ ] `ai.agent_type` 만 저장한 테넌트의 `ai.api_key` 가 **빈 값**으로 해석되는 것이 테스트로 고정됨 (과금 유출 방지)
- [ ] `getDecryptedApiKey` / `getDecryptedCliOauthToken` 호출부가 0곳
- [ ] Phase B(임베딩 4키 + 차원별 컬럼)는 **손대지 않음**

## 구현 후 실측 검증

코드만으로 확정할 수 없어 실제 실행이 필요한 것:

1. 프로액티브 배경 잡이 **잡 소유 테넌트의** 자격증명으로 실행되는지 (`TenantScopedRunner` 승계 실측)
2. 파이프라인 `AI_CLASSIFY` 가 실행 사용자 테넌트의 자격증명을 쓰는지
3. 테넌트가 `agent_type` 만 바꿨을 때 화면이 "키가 비워짐"을 이해 가능하게 보여주는지 (UX 실측)
