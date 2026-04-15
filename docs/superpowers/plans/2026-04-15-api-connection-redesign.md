# Phase 9 — API 연결 리디자인 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ApiConnection에 Base URL을 필수화하고, 파이프라인 API_CALL 스텝을 path/customUrl로 분리하며, 주기적 헬스체크 + 상태 알림 시스템을 도입한다.

**Architecture:**
- Backend: V49 마이그레이션으로 `api_connection`에 `base_url`, `health_check_path`, `last_*` 컬럼 추가. `ApiConnectionHealthCheckScheduler`가 10분 간격으로 헬스체크 수행, 상태 전환 시 `NotificationService` + 프로액티브 Chat 채널로 알림.
- Frontend: 관리자 페이지에 Base URL/헬스체크 필드 + 상태 배지 + "지금 확인/전체 갱신" 버튼. 파이프라인 API_CALL 스텝은 saved 모드(path) / inline 모드(customUrl) 분기.
- AI Agent: MCP 도구와 `api-connection-manager` 서브에이전트에 baseUrl/healthCheckPath 반영 + `test_api_connection` 도구 추가.

**Tech Stack:** Java/Spring Boot, jOOQ, Flyway, Vitest, TypeScript/React 19, TanStack Query, Playwright, Zod, Vitest (ai-agent), MCP SDK.

**선행 문서:** `docs/superpowers/specs/2026-04-15-api-connection-redesign-design.md`

---

## Layer 1 — Backend Core & AI Agent (병렬)

### Task 9-1-1: V49 마이그레이션 + 엔티티/리포지토리 필드 확장

**Files:**
- Create: `apps/firehub-api/src/main/resources/db/migration/V49__redesign_api_connection.sql`
- Modify: `apps/firehub-api/src/main/resources/application.yml` (baseline-version)
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/repository/ApiConnectionRepository.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/apiconnection/repository/ApiConnectionRepositoryTest.java` (또는 신규)

- [ ] **Step 1: V49 마이그레이션 SQL 작성**

```sql
-- V49__redesign_api_connection.sql
-- Phase 9: API 연결 리디자인 — Base URL 필수화 및 헬스체크 상태 컬럼 추가
-- 기존 레코드가 없는 전제로 base_url NOT NULL 즉시 적용

ALTER TABLE api_connection
  ADD COLUMN base_url VARCHAR(500) NOT NULL,
  ADD COLUMN health_check_path VARCHAR(500),
  ADD COLUMN last_status VARCHAR(16),
  ADD COLUMN last_checked_at TIMESTAMP,
  ADD COLUMN last_latency_ms INT,
  ADD COLUMN last_error_message VARCHAR(1000);

-- 상태 조회 인덱스 (리스트 페이지 정렬용)
CREATE INDEX idx_api_connection_last_status ON api_connection(last_status);
```

- [ ] **Step 2: `baseline-version` 갱신**

`apps/firehub-api/src/main/resources/application.yml`:
```yaml
spring:
  flyway:
    baseline-version: 49
```

- [ ] **Step 3: jOOQ 코드젠 재실행**

```bash
cd apps/firehub-api && ./gradlew generateJooqSchemaSource
```

Expected: `src/main/generated/.../tables/ApiConnection.java`에 신규 컬럼 필드 생성.

- [ ] **Step 4: Repository에 필드 반영**

`ApiConnectionRepository`에 신규 조회/업데이트 메서드 시그니처 추가 (실제 SQL은 jOOQ DSLContext):

```java
/** 헬스체크 상태 갱신 */
public void updateHealthStatus(Long id, String status, Long latencyMs, String errorMessage) {
    dsl.update(AC)
        .set(AC.LAST_STATUS, status)
        .set(AC.LAST_CHECKED_AT, LocalDateTime.now())
        .set(AC.LAST_LATENCY_MS, latencyMs != null ? latencyMs.intValue() : null)
        .set(AC.LAST_ERROR_MESSAGE, errorMessage)
        .where(AC.ID.eq(id))
        .execute();
}

/** 헬스체크 대상 조회 (healthCheckPath가 있는 활성 레코드) */
public List<Record> findHealthCheckable() {
    return dsl.selectFrom(AC)
        .where(AC.HEALTH_CHECK_PATH.isNotNull())
        .fetch().into(Record.class);
}
```

기존 `create`/`update`/`findAll`/`findById` 메서드 시그니처에 `baseUrl`, `healthCheckPath` 매개변수 추가.

- [ ] **Step 5: Repository 테스트 작성 (실패 확인)**

신규/수정 테스트:
```java
@Test
void create_withBaseUrl_persistsAllFields() {
    long id = repo.create("Test API", "desc", "API_KEY", "{\"k\":\"v\"}",
                          "https://api.example.com", "/health", 1L);
    Record r = repo.findById(id).orElseThrow();
    assertEquals("https://api.example.com", r.get(AC.BASE_URL));
    assertEquals("/health", r.get(AC.HEALTH_CHECK_PATH));
}

@Test
void updateHealthStatus_setsLastStatusFields() {
    long id = repo.create(...);
    repo.updateHealthStatus(id, "UP", 120L, null);
    Record r = repo.findById(id).orElseThrow();
    assertEquals("UP", r.get(AC.LAST_STATUS));
    assertEquals(120, r.get(AC.LAST_LATENCY_MS));
}
```

Run: `./gradlew test --tests "*.ApiConnectionRepositoryTest"`
Expected: FAIL (필드 아직 없음/코드 미구현).

- [ ] **Step 6: Repository 구현 완료 + 테스트 통과**

jOOQ 코드젠 결과물을 참조해 실제 DSLContext 쿼리 작성. 타입 매핑(`Integer ↔ INT`) 주의.

Run: `./gradlew test --tests "*.ApiConnectionRepositoryTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/firehub-api/src/main/resources/db/migration/V49__redesign_api_connection.sql \
        apps/firehub-api/src/main/resources/application.yml \
        apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/repository/ApiConnectionRepository.java \
        apps/firehub-api/src/test/java/com/smartfirehub/apiconnection/repository/ApiConnectionRepositoryTest.java
git commit -m "feat(api): V49 api_connection baseUrl/헬스체크 컬럼 추가"
```

---

### Task 9-1-2: DTO 확장 + Slim DTO 신설

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/dto/CreateApiConnectionRequest.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/dto/UpdateApiConnectionRequest.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/dto/ApiConnectionResponse.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/dto/ApiConnectionSelectableResponse.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/dto/TestConnectionResponse.java`

- [ ] **Step 1: CreateApiConnectionRequest 수정**

```java
package com.smartfirehub.apiconnection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * API 연결 생성 요청 DTO.
 * baseUrl은 필수, healthCheckPath는 선택.
 */
public record CreateApiConnectionRequest(
    @NotBlank @Size(max = 100) String name,
    String description,
    @NotBlank String authType,
    Map<String, String> authConfig,
    @NotBlank
    @Pattern(regexp = "^https?://.+", message = "http:// 또는 https://로 시작하는 URL이어야 합니다")
    @Size(max = 500)
    String baseUrl,
    @Pattern(regexp = "^/.*", message = "경로는 /로 시작해야 합니다")
    @Size(max = 500)
    String healthCheckPath
) {}
```

- [ ] **Step 2: UpdateApiConnectionRequest 수정**

동일 필드 추가, 단 수정 시 일부 필드만 보낼 수 있으므로 `@NotBlank`는 제외하되 값 있을 때만 패턴 검증.

- [ ] **Step 3: ApiConnectionResponse 수정**

```java
public record ApiConnectionResponse(
    Long id,
    String name,
    String description,
    String authType,
    Map<String, String> maskedAuthConfig,
    String baseUrl,
    String healthCheckPath,
    String lastStatus,           // UP | DOWN | null
    java.time.Instant lastCheckedAt,
    Integer lastLatencyMs,
    String lastErrorMessage,
    Long createdBy,
    java.time.Instant createdAt,
    java.time.Instant updatedAt
) {}
```

- [ ] **Step 4: ApiConnectionSelectableResponse 신설**

```java
package com.smartfirehub.apiconnection.dto;

/**
 * 일반 사용자용 slim DTO.
 * 파이프라인 스텝 드롭다운에 노출. 민감 필드(authConfig, healthCheckPath, last*) 제외.
 */
public record ApiConnectionSelectableResponse(
    Long id,
    String name,
    String authType,
    String baseUrl
) {}
```

- [ ] **Step 5: TestConnectionResponse 신설**

```java
package com.smartfirehub.apiconnection.dto;

/**
 * 연결 테스트(헬스체크) 결과.
 */
public record TestConnectionResponse(
    boolean ok,
    Integer status,
    Long latencyMs,
    String errorMessage
) {}
```

- [ ] **Step 6: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: PASS. 기존 코드에서 DTO 생성자 호출 부분은 다음 Task에서 서비스 수정과 함께 해결.

- [ ] **Step 7: Commit**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/dto/
git commit -m "feat(api): ApiConnection DTO에 baseUrl/헬스체크/상태 필드 추가"
```

---

### Task 9-1-3: Service 확장 (생성/수정/테스트/selectable)

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/service/ApiConnectionService.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/apiconnection/service/ApiConnectionServiceTest.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/service/UrlUtils.java` (또는 기존 util에 추가)

- [ ] **Step 1: UrlUtils.joinUrl 작성 (TDD)**

테스트 먼저:
```java
class UrlUtilsTest {
    @Test void joinUrl_noTrailingSlash_noLeadingSlash() {
        assertEquals("https://a.com/b", UrlUtils.joinUrl("https://a.com", "/b"));
        assertEquals("https://a.com/b", UrlUtils.joinUrl("https://a.com/", "/b"));
        assertEquals("https://a.com/b", UrlUtils.joinUrl("https://a.com", "b"));
        assertEquals("https://a.com/b", UrlUtils.joinUrl("https://a.com/", "b"));
    }
    @Test void joinUrl_nullPath_returnsBaseUrl() {
        assertEquals("https://a.com", UrlUtils.joinUrl("https://a.com", null));
        assertEquals("https://a.com", UrlUtils.joinUrl("https://a.com", ""));
    }
}
```

Run: `./gradlew test --tests "*.UrlUtilsTest"` → FAIL.

구현:
```java
public final class UrlUtils {
    private UrlUtils() {}
    public static String joinUrl(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;
        if (path == null || path.isBlank()) return base;
        String p = path.startsWith("/") ? path : "/" + path;
        return base + p;
    }
}
```

Run → PASS.

- [ ] **Step 2: normalize baseUrl + 검증 로직 TDD**

테스트:
```java
@Test void create_normalizesBaseUrl_andValidatesHost() {
    CreateApiConnectionRequest req = new CreateApiConnectionRequest(
        "Test", null, "API_KEY", Map.of("headerName","X-Key","apiKey","secret"),
        "https://api.example.com/", "/health");
    ApiConnectionResponse resp = service.create(req, 1L);
    assertEquals("https://api.example.com", resp.baseUrl()); // trailing slash 제거
}

@Test void create_invalidBaseUrlScheme_throwsException() {
    CreateApiConnectionRequest req = new CreateApiConnectionRequest(
        "Test", null, "API_KEY", Map.of("headerName","X-Key","apiKey","secret"),
        "ftp://bad.com", "/health");
    assertThrows(ApiConnectionException.class, () -> service.create(req, 1L));
}

@Test void create_privateIpBaseUrl_rejectedBySsrf() {
    CreateApiConnectionRequest req = new CreateApiConnectionRequest(
        "Test", null, "API_KEY", Map.of(...),
        "http://192.168.1.1", null);
    assertThrows(ApiConnectionException.class, () -> service.create(req, 1L));
}
```

Run → FAIL.

서비스에 로직 추가:
```java
private String normalizeBaseUrl(String raw) {
    try {
        URI uri = URI.create(raw);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
            throw new ApiConnectionException("baseUrl must use http(s) scheme");
        ssrfProtectionService.validateHost(uri.getHost()); // 기존 서비스 재사용
        String s = raw.endsWith("/") ? raw.substring(0, raw.length()-1) : raw;
        return s;
    } catch (IllegalArgumentException e) {
        throw new ApiConnectionException("Invalid baseUrl: " + e.getMessage());
    }
}
```

`create`/`update`에서 `normalizeBaseUrl` 호출. Repository 호출에도 신규 파라미터 전달.

Run → PASS.

- [ ] **Step 3: testConnection(id) 메서드 TDD**

테스트 (WireMock 활용):
```java
@Test void testConnection_2xx_returnsOkAndUpdatesStatus() {
    wireMockServer.stubFor(get("/health").willReturn(ok().withStatus(200)));
    long id = createWithHealthCheck(wireMockBaseUrl(), "/health");

    TestConnectionResponse result = service.testConnection(id);

    assertTrue(result.ok());
    assertEquals(200, result.status());
    // DB에도 반영됐는지 확인
    ApiConnectionResponse reloaded = service.findById(id);
    assertEquals("UP", reloaded.lastStatus());
}

@Test void testConnection_5xx_returnsNotOkAndStoresError() {
    wireMockServer.stubFor(get("/health").willReturn(aResponse().withStatus(503)));
    long id = createWithHealthCheck(...);

    TestConnectionResponse result = service.testConnection(id);

    assertFalse(result.ok());
    assertEquals(503, result.status());
    assertEquals("DOWN", service.findById(id).lastStatus());
}

@Test void testConnection_noHealthCheckPath_pingsBaseUrl() {
    wireMockServer.stubFor(get("/").willReturn(ok()));
    long id = createWithHealthCheck(wireMockBaseUrl(), null);
    assertTrue(service.testConnection(id).ok());
}

@Test void testConnection_timeout_returnsNotOk() { /* 5s 타임아웃 stub */ }
```

Run → FAIL.

구현:
```java
public TestConnectionResponse testConnection(Long id) {
    ApiConnection conn = repo.findById(id).orElseThrow(() -> new ApiConnectionException("not found"));
    String url = UrlUtils.joinUrl(conn.baseUrl(), conn.healthCheckPath());
    Map<String,String> authHeaders = decryptAndBuildAuthHeaders(conn);

    long start = System.currentTimeMillis();
    try {
        ClientResponse resp = webClient.get().uri(url)
            .headers(h -> authHeaders.forEach(h::set))
            .exchangeToMono(Mono::just)
            .block(Duration.ofSeconds(5));
        long latency = System.currentTimeMillis() - start;
        boolean ok = resp != null && resp.statusCode().is2xxSuccessful();
        Integer status = resp != null ? resp.statusCode().value() : null;
        String err = ok ? null : ("HTTP " + status);
        repo.updateHealthStatus(id, ok ? "UP" : "DOWN", latency, err);
        return new TestConnectionResponse(ok, status, latency, err);
    } catch (Exception e) {
        long latency = System.currentTimeMillis() - start;
        repo.updateHealthStatus(id, "DOWN", latency, e.getMessage());
        return new TestConnectionResponse(false, null, latency, e.getMessage());
    }
}
```

Run → PASS.

- [ ] **Step 4: findSelectable() TDD**

```java
@Test void findSelectable_returnsSlimDto_withoutAuthConfig() {
    long id = service.create(req, 1L).id();
    List<ApiConnectionSelectableResponse> list = service.findSelectable();
    assertEquals(1, list.size());
    ApiConnectionSelectableResponse slim = list.get(0);
    assertEquals(id, slim.id());
    assertEquals("https://api.example.com", slim.baseUrl());
    // 컴파일 레벨에서 authConfig 필드 자체 없음
}
```

구현:
```java
public List<ApiConnectionSelectableResponse> findSelectable() {
    return repo.findAll().stream()
        .map(r -> new ApiConnectionSelectableResponse(
            r.get(AC.ID), r.get(AC.NAME), r.get(AC.AUTH_TYPE), r.get(AC.BASE_URL)))
        .toList();
}
```

Run → PASS.

- [ ] **Step 5: 전체 테스트 실행 + Commit**

```bash
./gradlew test --tests "*.ApiConnectionServiceTest" "*.UrlUtilsTest"
git add apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/service/ \
        apps/firehub-api/src/test/java/com/smartfirehub/apiconnection/
git commit -m "feat(api): ApiConnectionService baseUrl 정규화, testConnection, selectable 추가"
```

---

### Task 9-1-4: Controller 엔드포인트 추가

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/controller/ApiConnectionController.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/apiconnection/controller/ApiConnectionControllerTest.java`
- Modify: `apps/firehub-api/src/main/resources/db/migration/V49__redesign_api_connection.sql` (권한 seed 추가 여부에 따라)

- [ ] **Step 1: `api_connection:*` 권한 코드 확인**

기존 권한이 이미 있는지 확인:
```bash
docker exec smart-fire-hub-db-1 psql -U app -d smartfirehub -c \
  "SELECT code FROM permission WHERE code LIKE 'api_connection%';"
```

없으면 V49 마이그레이션에 추가:
```sql
INSERT INTO permission(code, description) VALUES
  ('api_connection:read', 'API 연결 조회'),
  ('api_connection:write', 'API 연결 생성/수정/삭제/테스트')
ON CONFLICT (code) DO NOTHING;

-- ADMIN 역할에 부여
INSERT INTO role_permission(role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'ADMIN' AND p.code IN ('api_connection:read','api_connection:write')
ON CONFLICT DO NOTHING;
```

- [ ] **Step 2: Controller 테스트 작성 (selectable + test)**

```java
@Test
@WithMockUser(roles = "USER")
void getSelectable_returnsSlimList_forNonAdmin() throws Exception {
    mockMvc.perform(get("/api/v1/api-connections/selectable"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].authConfig").doesNotExist());
}

@Test
@WithMockUser(roles = "ADMIN")
void postTest_triggersHealthCheck_returnsResult() throws Exception {
    long id = seedConnection();
    mockMvc.perform(post("/api/v1/api-connections/" + id + "/test"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").isBoolean());
}

@Test
@WithMockUser(roles = "USER")
void postTest_nonAdmin_forbidden() throws Exception {
    mockMvc.perform(post("/api/v1/api-connections/1/test"))
        .andExpect(status().isForbidden());
}
```

Run → FAIL.

- [ ] **Step 3: Controller에 엔드포인트 추가**

```java
@GetMapping("/selectable")
@RequirePermission("dataset:read") // 로그인 사용자면 족함 → 기존 권한 재사용 또는 @PreAuthorize("isAuthenticated()")
public List<ApiConnectionSelectableResponse> getSelectable() {
    return service.findSelectable();
}

@PostMapping("/{id}/test")
@RequirePermission("api_connection:write")
public TestConnectionResponse testConnection(@PathVariable Long id) {
    return service.testConnection(id);
}

@PostMapping("/refresh-all")
@RequirePermission("api_connection:write")
public Map<String, UUID> refreshAll() {
    UUID jobId = service.refreshAllAsync(); // Task 9-1-6에서 구현, 일단 stub
    return Map.of("jobId", jobId);
}
```

selectable은 로그인 사용자 누구나 → `@PreAuthorize("isAuthenticated()")` 사용 (또는 권한 없이 SecurityConfig의 authenticated 매처로 처리).

Run → PASS.

- [ ] **Step 4: Commit**

```bash
./gradlew test --tests "*.ApiConnectionControllerTest"
git add apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/controller/ \
        apps/firehub-api/src/test/java/com/smartfirehub/apiconnection/controller/ \
        apps/firehub-api/src/main/resources/db/migration/V49__redesign_api_connection.sql
git commit -m "feat(api): /api-connections/selectable, /{id}/test 엔드포인트 추가"
```

---

### Task 9-1-5: 파이프라인 ApiCallExecutor/PreviewService 재작성

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/executor/ApiCallExecutor.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/ApiCallPreviewService.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/pipeline/service/ApiCallPreviewServiceTest.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/pipeline/service/PipelineExecutionServiceTest.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/dto/PipelineStepRequest.java` (검증 강화 시)

- [ ] **Step 1: 테스트 — saved 모드 경로 조립**

```java
@Test void apiCall_savedMode_joinsBaseUrlAndPath() {
    wireMockServer.stubFor(get("/v1/data").willReturn(okJson("[]")));
    long connId = seedConnection(wireMockBaseUrl(), Map.of("headerName","X-Key","apiKey","v"));
    Map<String,Object> apiConfig = Map.of(
        "method", "GET",
        "path", "/v1/data",
        "dataPath", "$",
        "fieldMappings", List.of()
    );
    executor.execute(pipelineStep(apiConfig, connId), ctx);
    wireMockServer.verify(getRequestedFor(urlEqualTo("/v1/data")).withHeader("X-Key", equalTo("v")));
}

@Test void apiCall_savedMode_missingPath_throwsValidation() {
    long connId = seedConnection(...);
    Map<String,Object> apiConfig = Map.of("method","GET","dataPath","$");
    assertThrows(PipelineExecutionException.class, () -> executor.execute(pipelineStep(apiConfig, connId), ctx));
}

@Test void apiCall_inlineMode_usesCustomUrl() {
    wireMockServer.stubFor(get("/raw").willReturn(okJson("[]")));
    Map<String,Object> apiConfig = Map.of(
        "method","GET",
        "customUrl", wireMockBaseUrl() + "/raw",
        "dataPath","$",
        "fieldMappings", List.of()
    );
    executor.execute(pipelineStep(apiConfig, null), ctx);
    wireMockServer.verify(getRequestedFor(urlEqualTo("/raw")));
}

@Test void apiCall_inlineMode_missingCustomUrl_throwsValidation() { ... }
```

Run → FAIL.

- [ ] **Step 2: Executor 리팩터링**

기존 `apiConfig.get("url")` 참조 전부 제거. URL 계산 로직:
```java
private String resolveTargetUrl(Map<String,Object> apiConfig, ApiConnection conn) {
    if (conn != null) {
        String path = (String) apiConfig.get("path");
        if (path == null || path.isBlank())
            throw new PipelineExecutionException("API_CALL: path is required when apiConnectionId is set");
        return UrlUtils.joinUrl(conn.baseUrl(), path);
    }
    String customUrl = (String) apiConfig.get("customUrl");
    if (customUrl == null || customUrl.isBlank())
        throw new PipelineExecutionException("API_CALL: customUrl is required when apiConnectionId is null");
    return customUrl;
}
```

Preview 서비스도 동일 로직 적용. Run → PASS.

- [ ] **Step 3: PipelineStepRequest 검증 강화 (선택)**

API_CALL 스텝 저장 시 path/customUrl 중 하나는 반드시 있어야 함. Controller/Service 레벨에서 검증 추가.

- [ ] **Step 4: 관련 테스트 전체 재실행 + Commit**

```bash
./gradlew test --tests "*.ApiCall*" "*.PipelineExecutionServiceTest"
git add apps/firehub-api/src/main/java/com/smartfirehub/pipeline/ \
        apps/firehub-api/src/test/java/com/smartfirehub/pipeline/
git commit -m "refactor(api): API_CALL 스텝 url → path/customUrl 분리"
```

---

### Task 9-1-6: 헬스체크 스케줄러 + 알림 디스패치 + refresh-all Job

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/service/ApiConnectionHealthCheckScheduler.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/service/ApiConnectionNotifier.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/service/ApiConnectionService.java` (refreshAllAsync)
- Create: `apps/firehub-api/src/test/java/com/smartfirehub/apiconnection/service/ApiConnectionHealthCheckSchedulerTest.java`
- Create: `apps/firehub-api/src/test/java/com/smartfirehub/apiconnection/service/ApiConnectionNotifierTest.java`

- [ ] **Step 1: ApiConnectionNotifier TDD**

```java
class ApiConnectionNotifierTest {
    @MockitoBean NotificationService notificationService;
    @MockitoBean ProactiveJobService proactiveJobService;
    @Autowired ApiConnectionNotifier notifier;

    @Test void notifyStatusChange_upToDown_sendsDashboardAndChat() {
        notifier.notifyStatusChange(1L, "API-X", "UP", "DOWN", "HTTP 500");
        verify(notificationService).broadcastToAdmins(argThat(e ->
            e.type().equals("API_CONNECTION_DOWN") && e.message().contains("API-X")));
        verify(proactiveJobService).pushMessage(anyString(), contains("API-X"));
    }

    @Test void notifyStatusChange_downToUp_sendsRecovery() {
        notifier.notifyStatusChange(1L, "API-X", "DOWN", "UP", null);
        verify(notificationService).broadcastToAdmins(argThat(e ->
            e.type().equals("API_CONNECTION_UP")));
    }

    @Test void notifyStatusChange_firstCheck_noNotification() {
        notifier.notifyStatusChange(1L, "API-X", null, "UP", null);
        verifyNoInteractions(notificationService);
        verifyNoInteractions(proactiveJobService);
    }

    @Test void notifyStatusChange_sameStatus_noNotification() {
        notifier.notifyStatusChange(1L, "API-X", "UP", "UP", null);
        verifyNoInteractions(notificationService);
    }
}
```

Run → FAIL.

구현 (실제 `NotificationService`, `ProactiveJobService` 시그니처에 맞춰 조정):
```java
@Service
public class ApiConnectionNotifier {
    private final NotificationService notificationService;
    private final ProactiveJobService proactiveJobService;
    private final AuditLogService auditLogService;

    public void notifyStatusChange(Long id, String name, String prev, String curr, String errorMessage) {
        if (prev == null || Objects.equals(prev, curr)) return;
        boolean isDown = "DOWN".equals(curr);
        String type = isDown ? "API_CONNECTION_DOWN" : "API_CONNECTION_UP";
        String msg = isDown
            ? String.format("API 연결 '%s'이(가) 응답하지 않습니다 (%s)", name, errorMessage)
            : String.format("API 연결 '%s'이(가) 복구되었습니다", name);
        notificationService.broadcastToAdmins(new NotificationEvent(type, msg, Map.of("id", id)));
        proactiveJobService.pushMessage("admin", msg + " — /admin/api-connections/" + id);
        auditLogService.log("api_connection", id, "status_change",
            Map.of("from", prev, "to", curr, "errorMessage", errorMessage));
    }
}
```

Run → PASS.

- [ ] **Step 2: Scheduler TDD**

```java
@SpringBootTest
class ApiConnectionHealthCheckSchedulerTest extends IntegrationTestBase {
    @Autowired ApiConnectionHealthCheckScheduler scheduler;
    @MockitoBean ApiConnectionNotifier notifier;

    @Test void runOnce_pingsHealthCheckableConnections_updatesStatus() {
        wireMockServer.stubFor(get("/ping").willReturn(ok()));
        long id = seedConnection(wireMockBaseUrl(), "/ping");

        scheduler.runOnce();

        Record r = repo.findById(id).orElseThrow();
        assertEquals("UP", r.get(AC.LAST_STATUS));
        verifyNoInteractions(notifier); // 최초 체크, 알림 없음
    }

    @Test void runOnce_statusChangedUpToDown_callsNotifier() {
        // 기존 레코드 UP 상태로 seed
        long id = seedConnection(wireMockBaseUrl(), "/ping");
        repo.updateHealthStatus(id, "UP", 100L, null);
        wireMockServer.stubFor(get("/ping").willReturn(serverError()));

        scheduler.runOnce();

        verify(notifier).notifyStatusChange(eq(id), anyString(), eq("UP"), eq("DOWN"), anyString());
    }

    @Test void runOnce_skipsConnectionsWithoutHealthCheckPath() {
        long id = seedConnection(wireMockBaseUrl(), null);
        scheduler.runOnce();
        assertNull(repo.findById(id).get().get(AC.LAST_STATUS));
    }
}
```

Run → FAIL.

구현:
```java
@Component
@RequiredArgsConstructor
public class ApiConnectionHealthCheckScheduler {
    private final ApiConnectionRepository repo;
    private final ApiConnectionService connectionService;
    private final ApiConnectionNotifier notifier;

    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void runOnce() {
        List<Record> targets = repo.findHealthCheckable();
        for (Record r : targets) {
            Long id = r.get(AC.ID);
            String name = r.get(AC.NAME);
            String prevStatus = r.get(AC.LAST_STATUS);
            TestConnectionResponse result = connectionService.testConnection(id);
            String newStatus = result.ok() ? "UP" : "DOWN";
            notifier.notifyStatusChange(id, name, prevStatus, newStatus, result.errorMessage());
        }
    }
}
```

참고: `testConnection`이 이미 DB를 업데이트하므로 scheduler는 상태 전환 비교와 알림 호출만 담당.

Run → PASS.

- [ ] **Step 3: refreshAllAsync TDD**

```java
@Test void refreshAllAsync_returnsJobId_andRunsAsynchronously() throws Exception {
    seedConnection(...); seedConnection(...);
    UUID jobId = service.refreshAllAsync();
    assertNotNull(jobId);
    // AsyncJobService로 추적 가능한지 확인
    await().atMost(5, SECONDS).until(() -> jobService.isCompleted(jobId));
}
```

구현: `AsyncJobService` + `pipelineExecutor`로 비동기 dispatch:
```java
public UUID refreshAllAsync() {
    UUID jobId = asyncJobService.createJob("api_connection_refresh_all");
    pipelineExecutor.execute(() -> {
        try {
            List<Record> all = repo.findHealthCheckable();
            for (Record r : all) {
                Long id = r.get(AC.ID);
                testConnection(id);
                asyncJobService.reportProgress(jobId, ...);
            }
            asyncJobService.complete(jobId);
        } catch (Exception e) {
            asyncJobService.fail(jobId, e.getMessage());
        }
    });
    return jobId;
}
```

Run → PASS.

- [ ] **Step 4: Commit**

```bash
./gradlew test --tests "*.ApiConnection*"
git add apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/ \
        apps/firehub-api/src/test/java/com/smartfirehub/apiconnection/
git commit -m "feat(api): API 연결 10분 헬스체크 스케줄러 + 상태 변화 알림 + refresh-all Job"
```

---

### Task 9-2: AI Agent MCP 도구 및 서브에이전트 갱신 (Layer 1 병렬)

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/tools/api-connection-tools.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/tools/pipeline-tools.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/api-client/connection-api.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts` (도구 등록)
- Modify: `apps/firehub-ai-agent/src/mcp/api-client.test.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.test.ts`
- Modify: `apps/firehub-ai-agent/src/agent/subagents/api-connection-manager/rules.md`
- Modify: `apps/firehub-ai-agent/src/agent/subagents/api-connection-manager/examples.md`
- Modify: `apps/firehub-ai-agent/src/agent/subagents/pipeline-builder/step-types.md`
- Modify: `apps/firehub-ai-agent/src/agent/subagents/pipeline-builder/rules.md`
- Modify: `apps/firehub-ai-agent/src/agent/system-prompt.ts`

- [ ] **Step 1: connection-api 클라이언트 확장 (TDD)**

`api-client.test.ts`에 신규 필드 포함 테스트:
```typescript
test('createApiConnection sends baseUrl and healthCheckPath', async () => {
  const spy = mockPost('/api-connections', { id: 1, name: 'X', baseUrl: 'https://a.com', ... });
  await client.createApiConnection({
    name: 'X', authType: 'API_KEY',
    authConfig: { headerName: 'X-Key', apiKey: 'v' },
    baseUrl: 'https://a.com', healthCheckPath: '/health',
  });
  expect(spy.lastBody).toMatchObject({ baseUrl: 'https://a.com', healthCheckPath: '/health' });
});

test('testApiConnection hits /test endpoint', async () => {
  mockPost('/api-connections/1/test', { ok: true, status: 200, latencyMs: 42 });
  const r = await client.testApiConnection(1);
  expect(r.ok).toBe(true);
});
```

Run: `pnpm --filter firehub-ai-agent test` → FAIL.

`connection-api.ts`:
```typescript
export interface CreateApiConnectionInput {
  name: string;
  description?: string;
  authType: 'API_KEY' | 'BEARER' | 'OAUTH2';
  authConfig: Record<string, string>;
  baseUrl: string;
  healthCheckPath?: string;
}
export interface TestConnectionResponse {
  ok: boolean;
  status: number | null;
  latencyMs: number;
  errorMessage: string | null;
}
export const testApiConnection = (id: number) =>
  apiClient.post<TestConnectionResponse>(`/api-connections/${id}/test`).then(r => r.data);
```

Run → PASS.

- [ ] **Step 2: MCP 도구 스키마 업데이트**

`api-connection-tools.ts`:
```typescript
safeTool(
  'create_api_connection',
  '새 API 연결을 생성합니다. baseUrl은 필수, healthCheckPath는 선택. 인증 정보는 암호화되어 저장됩니다.',
  {
    name: z.string(),
    description: z.string().optional(),
    authType: z.enum(['API_KEY','BEARER','OAUTH2']),
    authConfig: z.record(z.string()),
    baseUrl: z.string().url().describe('예: https://api.make.com/v2 (trailing slash 없이)'),
    healthCheckPath: z.string().regex(/^\//).optional().describe('예: /health'),
  },
  async (args) => { /* ... */ },
),
safeTool(
  'test_api_connection',
  '저장된 API 연결을 즉시 테스트 호출하고 상태를 반환합니다. healthCheckPath로 GET 요청.',
  { id: z.number() },
  async ({ id }) => JSON.stringify(await connectionApi.testApiConnection(id)),
),
```

`update_api_connection`도 baseUrl/healthCheckPath 선택 필드 추가.

- [ ] **Step 3: pipeline-tools.ts API_CALL 스키마 변경**

```typescript
// 변경 전: url: z.string()
// 변경 후:
path: z.string().regex(/^\//).optional().describe('saved 모드에서 사용 — baseUrl에 이어붙임'),
customUrl: z.string().url().optional().describe('inline 모드(apiConnectionId 없음)에서 사용하는 full URL'),
// 유효성: apiConnectionId가 있으면 path 필수, 없으면 customUrl 필수 → 검증 로직
```

모든 API_CALL 스텝 정의 위치에 동일 변경. 툴 설명 갱신.

- [ ] **Step 4: firehub-mcp-server 도구 등록 + 테스트**

`firehub-mcp-server.test.ts`에 `test_api_connection` 도구가 등록됐는지 확인하는 테스트 추가.

Run: `pnpm --filter firehub-ai-agent test` → PASS.

- [ ] **Step 5: 서브에이전트 문서 갱신**

`api-connection-manager/rules.md`:
- "Base URL 필수" 섹션 신설
- 생성 워크플로: 1) 이름 → 2) Base URL → 3) 인증 유형 → 4) authConfig → 5) 헬스체크 경로(선택)
- `test_api_connection` 활용 예제
- URL 정규화 규칙(trailing slash 제거) 명시

`api-connection-manager/examples.md`:
- 예제 대화 수정: baseUrl 포함 생성, 헬스체크 설정, 테스트 호출 흐름

`pipeline-builder/step-types.md`:
- API_CALL 섹션에서 `url` → `path`(saved 모드) / `customUrl`(inline 모드) 구분 명시
- 코드 예제 업데이트

`pipeline-builder/rules.md`: apiConnectionId 있으면 path, 없으면 customUrl 규칙 추가.

`system-prompt.ts`:
```
[API 연결]
- 모든 API 연결에는 baseUrl이 필수입니다 (예: https://api.make.com)
- healthCheckPath를 설정하면 10분마다 자동 헬스체크가 수행됩니다
- 파이프라인 API_CALL 스텝에서 저장된 연결을 선택하면 path만 입력
```

- [ ] **Step 6: 서브에이전트 로더 테스트 업데이트**

`subagent-loader.test.ts`:
```typescript
expect(agents['api-connection-manager'].description).toContain('baseUrl');
```

Run → PASS.

- [ ] **Step 7: Commit**

```bash
pnpm --filter firehub-ai-agent test && pnpm --filter firehub-ai-agent typecheck
git add apps/firehub-ai-agent/
git commit -m "feat(ai-agent): MCP 도구와 서브에이전트에 baseUrl/헬스체크 반영"
```

---

## Layer 2 — Frontend (Layer 1 완료 후)

### Task 9-3: 프론트엔드 타입 & API 훅

**Files:**
- Modify: `apps/firehub-web/src/types/api-connection.ts`
- Modify: `apps/firehub-web/src/api/api-connections.ts`
- Modify: `apps/firehub-web/src/hooks/queries/useApiConnections.ts`

- [ ] **Step 1: 타입 확장**

```typescript
// src/types/api-connection.ts
export interface ApiConnection {
  id: number;
  name: string;
  description: string | null;
  authType: 'API_KEY' | 'BEARER' | 'OAUTH2';
  maskedAuthConfig: Record<string, string>;
  baseUrl: string;
  healthCheckPath: string | null;
  lastStatus: 'UP' | 'DOWN' | null;
  lastCheckedAt: string | null;
  lastLatencyMs: number | null;
  lastErrorMessage: string | null;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}
export interface ApiConnectionSelectable {
  id: number;
  name: string;
  authType: string;
  baseUrl: string;
}
export interface CreateApiConnectionRequest {
  name: string;
  description?: string;
  authType: 'API_KEY' | 'BEARER';
  authConfig: Record<string, string>;
  baseUrl: string;
  healthCheckPath?: string;
}
export interface TestConnectionResponse {
  ok: boolean;
  status: number | null;
  latencyMs: number;
  errorMessage: string | null;
}
```

- [ ] **Step 2: API 모듈 확장**

```typescript
// src/api/api-connections.ts
export const testConnection = (id: number) =>
  client.post<TestConnectionResponse>(`/api-connections/${id}/test`).then(r => r.data);

export const refreshAllConnections = () =>
  client.post<{ jobId: string }>(`/api-connections/refresh-all`).then(r => r.data);

export const listSelectable = () =>
  client.get<ApiConnectionSelectable[]>(`/api-connections/selectable`).then(r => r.data);
```

- [ ] **Step 3: 쿼리 훅 확장**

```typescript
// src/hooks/queries/useApiConnections.ts
export const useTestApiConnection = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.testConnection(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['api-connections'] }),
  });
};

export const useRefreshAllApiConnections = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.refreshAllConnections(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['api-connections'] }),
  });
};

export const useApiConnectionsSelectable = () =>
  useQuery({
    queryKey: ['api-connections','selectable'],
    queryFn: () => api.listSelectable(),
    staleTime: 60_000,
  });
```

- [ ] **Step 4: typecheck + Commit**

```bash
pnpm --filter firehub-web typecheck
git add apps/firehub-web/src/types/api-connection.ts \
        apps/firehub-web/src/api/api-connections.ts \
        apps/firehub-web/src/hooks/queries/useApiConnections.ts
git commit -m "feat(web): API 연결 타입/훅에 baseUrl/헬스체크/테스트 추가"
```

---

### Task 9-4: ApiConnectionListPage 개선

**Files:**
- Modify: `apps/firehub-web/src/pages/admin/ApiConnectionListPage.tsx`
- Create/Modify: `apps/firehub-web/e2e/factories/admin.factory.ts` (connection factory 신규 필드)
- Create/Modify: `apps/firehub-web/e2e/fixtures/admin.fixture.ts` (selectable/test 모킹)

- [ ] **Step 1: 생성 다이얼로그에 필드 추가**

```tsx
<div className="space-y-2">
  <Label>Base URL *</Label>
  <Input placeholder="https://api.example.com" value={baseUrl}
         onChange={(e) => setBaseUrl(e.target.value)} />
</div>
<div className="space-y-2">
  <Label>헬스체크 경로 (선택)</Label>
  <Input placeholder="/health" value={healthCheckPath}
         onChange={(e) => setHealthCheckPath(e.target.value)} />
  <p className="text-xs text-muted-foreground">10분마다 자동 상태 점검. 비워두면 점검 안 함.</p>
</div>
```

클라이언트 검증: `/^https?:\/\/.+/` 및 경로 `/^\/.*/`.

- [ ] **Step 2: 테이블에 Base URL + 상태 배지 컬럼 추가**

```tsx
<TableHead>Base URL</TableHead>
<TableHead>상태</TableHead>
...
<TableCell className="font-mono text-xs">{conn.baseUrl}</TableCell>
<TableCell>
  <StatusBadge status={conn.lastStatus} checkedAt={conn.lastCheckedAt} />
</TableCell>
```

`StatusBadge` 컴포넌트 신설 (간단):
```tsx
function StatusBadge({ status, checkedAt }: { status: 'UP'|'DOWN'|null; checkedAt: string | null }) {
  if (!status) return <Badge variant="outline">미확인</Badge>;
  const v = status === 'UP' ? 'success' : 'destructive';
  return (
    <Badge variant={v} title={checkedAt ? `${formatDateTime(checkedAt)} 확인` : undefined}>
      {status === 'UP' ? '정상' : '이상'}
    </Badge>
  );
}
```

- [ ] **Step 3: "전체 갱신" 버튼 추가**

상단 "새 연결" 옆에 버튼. `useRefreshAllApiConnections` 호출 → 토스트 + invalidate.

- [ ] **Step 4: E2E 테스트 작성**

`e2e/pages/admin/api-connections.spec.ts`에 케이스 추가:
- 생성 시 baseUrl 누락 → 검증 에러 토스트
- payload에 baseUrl/healthCheckPath 포함 확인 (`route.request().postDataJSON()`)
- 리스트에 Base URL 컬럼 렌더링, 상태 배지 표시
- 전체 갱신 버튼 클릭 → POST `/refresh-all` 호출 확인

Run: `pnpm --filter firehub-web test:e2e --grep "api-connections"`

- [ ] **Step 5: Commit**

```bash
git add apps/firehub-web/src/pages/admin/ApiConnectionListPage.tsx \
        apps/firehub-web/src/components/ \
        apps/firehub-web/e2e/
git commit -m "feat(web): API 연결 목록에 baseUrl/상태 배지/전체 갱신 추가"
```

---

### Task 9-5: ApiConnectionDetailPage + 지금 확인 버튼

**Files:**
- Modify: `apps/firehub-web/src/pages/admin/ApiConnectionDetailPage.tsx`

- [ ] **Step 1: 편집 폼에 baseUrl/healthCheckPath 필드 추가**

List와 동일한 입력 컨트롤. PUT payload에 포함.

- [ ] **Step 2: 상태 카드 + "지금 확인" 버튼**

```tsx
<Card>
  <CardHeader>
    <CardTitle>연결 상태</CardTitle>
  </CardHeader>
  <CardContent className="space-y-2">
    <StatusBadge status={conn.lastStatus} checkedAt={conn.lastCheckedAt} />
    {conn.lastLatencyMs && <p className="text-sm">지연: {conn.lastLatencyMs}ms</p>}
    {conn.lastErrorMessage && <p className="text-sm text-destructive">{conn.lastErrorMessage}</p>}
    <Button onClick={() => testMutation.mutate(conn.id)} disabled={testMutation.isPending}>
      {testMutation.isPending ? '확인 중...' : '지금 확인'}
    </Button>
  </CardContent>
</Card>
```

- [ ] **Step 3: E2E 케이스 추가**

- 지금 확인 버튼 클릭 → POST `/{id}/test` 호출 + 결과 반영 확인

- [ ] **Step 4: Commit**

```bash
git add apps/firehub-web/src/pages/admin/ApiConnectionDetailPage.tsx \
        apps/firehub-web/e2e/
git commit -m "feat(web): API 연결 상세에 Base URL 편집 + 지금 확인 버튼"
```

---

### Task 9-6: 파이프라인 ApiCallStepConfig 재작성

**Files:**
- Modify: `apps/firehub-web/src/pages/pipeline/components/ApiCallStepConfig.tsx`
- Modify: `apps/firehub-web/e2e/pages/pipeline/` (신규 스펙)

- [ ] **Step 1: saved 모드 UI**

`apiConnectionId`가 설정되면:
- 커넥션의 `baseUrl`을 읽기전용 prefix 박스로 표시 (`useApiConnectionsSelectable` 결과에서 조회)
- URL 입력란 라벨 "경로(Path)", 플레이스홀더 `/v1/scenarios/123/run`
- onChange → `update('path', value)` (기존 `update('url', ...)` 제거)

```tsx
{authMode === 'saved' && selectedConn && (
  <div className="space-y-1">
    <Label>경로(Path)</Label>
    <div className="flex items-center gap-1">
      <span className="text-xs font-mono text-muted-foreground px-2 py-1 bg-muted rounded">
        {selectedConn.baseUrl}
      </span>
      <Input placeholder="/v1/data" value={path}
             onChange={(e) => update('path', e.target.value)} />
    </div>
  </div>
)}
```

- [ ] **Step 2: inline 모드 UI**

```tsx
{authMode === 'inline' && (
  <div className="space-y-1">
    <Label>URL</Label>
    <Input placeholder="https://api.example.com/v1/data" value={customUrl}
           onChange={(e) => update('customUrl', e.target.value)} />
  </div>
)}
```

- [ ] **Step 3: 모드 전환 시 값 초기화**

```tsx
const handleConnectionChange = (id: number | null) => {
  onConnectionChange(id);
  // 기존 url 제거, path/customUrl 초기화
  const cleaned = { ...apiConfig };
  delete cleaned.url;
  delete cleaned.path;
  delete cleaned.customUrl;
  onChange(cleaned);
};
```

- [ ] **Step 4: selectable 훅 사용**

`useApiConnections()` 대신 `useApiConnectionsSelectable()` 사용 (일반 사용자 권한).

- [ ] **Step 5: E2E 테스트 추가**

`e2e/pages/pipeline/api-call-step.spec.ts`:
- saved 모드 선택 → Base URL 표시 + path 입력 + 저장 payload 검증
- inline 모드 → customUrl 입력 + 저장 payload 검증
- 모드 전환 시 이전 값 초기화

- [ ] **Step 6: Commit**

```bash
pnpm --filter firehub-web typecheck
git add apps/firehub-web/src/pages/pipeline/components/ApiCallStepConfig.tsx \
        apps/firehub-web/e2e/
git commit -m "feat(web): API_CALL 스텝 saved(path) / inline(customUrl) 모드 분리"
```

---

## Layer 3 — Integration & E2E

### Task 9-7: 통합 E2E 회귀 & 알림 검증

**Files:**
- Create: `apps/firehub-web/e2e/flows/api-connection-lifecycle.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/admin/api-connections.spec.ts`

- [ ] **Step 1: 라이프사이클 플로우 테스트**

해피 패스 시나리오 한 파일:
1. 관리자 로그인 → `/admin/api-connections`
2. 새 연결 생성(baseUrl, healthCheckPath, API_KEY)
3. 테이블에 등장 확인
4. 상세 진입 → "지금 확인" 클릭 → 모킹된 `/test` 응답으로 UP 상태 반영
5. 목록 복귀 → 상태 배지 "정상"
6. 파이프라인 스텝 config로 이동 → saved 모드 선택 → baseUrl prefix 표시 → path 입력
7. 삭제

- [ ] **Step 2: 알림 채널 검증(백엔드 단위 테스트로 이미 커버, E2E는 UI만)**

전체 갱신 버튼 → `/refresh-all` 호출 + 상태 변동 후 목록 배지 업데이트 확인(모킹된 SSE).

- [ ] **Step 3: 관련 테스트 일체 실행**

```bash
pnpm --filter firehub-web test:e2e --grep "api-connection"
./gradlew test --tests "*.ApiConnection*" "*.ApiCall*"
pnpm --filter firehub-ai-agent test
```

Expected: 모두 PASS.

- [ ] **Step 4: Commit**

```bash
git add apps/firehub-web/e2e/
git commit -m "test(web): API 연결 라이프사이클 E2E + 파이프라인 통합 검증"
```

---

### Task 9-8: ROADMAP 업데이트 및 최종 검증

**Files:**
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: Phase 9 섹션 추가**

```markdown
## Phase 9: API 연결 리디자인 ✅

> Base URL 필수화, 파이프라인 API_CALL 스텝 path/customUrl 분리, 10분 헬스체크 + 상태 알림.

| # | 작업 | 상태 | 범위 | 의존 | 검증 |
|---|------|------|------|------|------|
| 9-1-1 | V49 마이그레이션 + Repository | ✅ | Backend | - | Repo test |
| 9-1-2 | DTO 확장 + Slim DTO | ✅ | Backend | 9-1-1 | compile |
| 9-1-3 | Service 확장 | ✅ | Backend | 9-1-2 | Service test |
| 9-1-4 | Controller 엔드포인트 | ✅ | Backend | 9-1-3 | Controller test |
| 9-1-5 | Pipeline Executor 리팩터 | ✅ | Backend | 9-1-3 | ApiCall test |
| 9-1-6 | 헬스체크 스케줄러 + 알림 | ✅ | Backend | 9-1-3 | Scheduler/Notifier test |
| 9-2 | AI Agent MCP/서브에이전트 | ✅ | AI Agent | 9-1-4 | Vitest |
| 9-3 | 프론트 타입/훅 | ✅ | Frontend | 9-1-4 | typecheck |
| 9-4 | List 페이지 | ✅ | Frontend | 9-3 | E2E |
| 9-5 | Detail 페이지 | ✅ | Frontend | 9-3 | E2E |
| 9-6 | ApiCallStepConfig | ✅ | Frontend | 9-3 | E2E |
| 9-7 | 통합 E2E | ✅ | Frontend | 9-4~9-6 | E2E |
```

진행 현황 요약 표에 Phase 9 행 추가.

- [ ] **Step 2: 최종 전체 테스트**

```bash
pnpm test        # 전체
pnpm build       # 전체
pnpm --filter firehub-web test:e2e
./gradlew test
```

Expected: 전체 PASS.

- [ ] **Step 3: Commit**

```bash
git add docs/ROADMAP.md
git commit -m "docs(roadmap): Phase 9 API 연결 리디자인 완료 기록"
```

---

## 부록 A — 자주 참조할 파일

- 스펙: `docs/superpowers/specs/2026-04-15-api-connection-redesign-design.md`
- 기존 API 연결 코드: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/`
- 파이프라인 실행기: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/executor/ApiCallExecutor.java`
- 알림 시스템: `apps/firehub-api/src/main/java/com/smartfirehub/notification/`
- 프로액티브 Chat: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/`
- SSRF 보호: `SsrfProtectionService` (전체 그렙해서 위치 파악)
- AI 서브에이전트: `apps/firehub-ai-agent/src/agent/subagents/api-connection-manager/`
- 프론트 관리자 페이지: `apps/firehub-web/src/pages/admin/ApiConnectionListPage.tsx`, `ApiConnectionDetailPage.tsx`
- 파이프라인 스텝 config: `apps/firehub-web/src/pages/pipeline/components/ApiCallStepConfig.tsx`

## 부록 B — 테스트 명령어 치트시트

```bash
# Backend
./gradlew test --tests "*.ApiConnection*"
./gradlew test --tests "*.ApiCall*"
./gradlew test --tests "*.UrlUtilsTest"

# AI Agent
pnpm --filter firehub-ai-agent test
pnpm --filter firehub-ai-agent typecheck

# Frontend
pnpm --filter firehub-web typecheck
pnpm --filter firehub-web test:e2e --grep "api-connection"
pnpm --filter firehub-web test:e2e --grep "api-call"

# 전체
pnpm test && pnpm build
```

## 부록 C — 롤백 시나리오

V49 마이그레이션 실패 시:
1. `baseline-version`을 48로 되돌림
2. `ALTER TABLE api_connection DROP COLUMN base_url, DROP COLUMN health_check_path, DROP COLUMN last_status, DROP COLUMN last_checked_at, DROP COLUMN last_latency_ms, DROP COLUMN last_error_message;`
3. Flyway history에서 V49 레코드 수동 삭제
4. `git revert` 커밋 범위
