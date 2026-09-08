package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.TestConnectionResponse;
import com.smartfirehub.apiconnection.dto.UpdateApiConnectionRequest;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.pipeline.service.executor.SsrfException;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * ApiConnectionService 추가 통합 테스트. 기존 테스트에서 커버되지 않은 분기: - update() 시 baseUrl 변경
 * (validateAndNormalizeBaseUrl 호출) - validateAndNormalizeBaseUrl: null/blank, 호스트 없음,
 * SsrfException, IllegalArgumentException - validateAuthType: 유효하지 않은 authType - update() 시
 * authType 없이 authConfig만 변경 (fetchAuthType 경로)
 */
class ApiConnectionServiceExtTest extends IntegrationTestBase {

  @Autowired private ApiConnectionService apiConnectionService;
  @Autowired private DSLContext dsl;

  /** RLS 가 걸린 테이블을 테스트가 직접 만질 때 쓰는 트랜잭션 경계 — GUC 주입의 유일한 통로다. */
  @Autowired private TransactionTemplate tx;

  @MockitoBean private SsrfProtectionService ssrfProtectionService;

  /** (#532) 헬스체크 타임아웃 재현용 WireMock — 5초 초과 지연 응답을 흉내낸다. */
  static WireMockServer wireMock;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  private Long testUserId;

  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Field<String> U_USERNAME = field(name("user", "username"), String.class);
  private static final Field<String> U_PASSWORD = field(name("user", "password"), String.class);
  private static final Field<String> U_NAME = field(name("user", "name"), String.class);
  private static final Field<String> U_EMAIL = field(name("user", "email"), String.class);

  private static final Table<?> API_CONNECTION = table(name("api_connection"));
  private static final Field<Long> AC_CREATED_BY =
      field(name("api_connection", "created_by"), Long.class);

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER_TABLE)
            .set(U_USERNAME, "apiconn_ext_" + System.nanoTime())
            .set(U_PASSWORD, "password")
            .set(U_NAME, "ApiConn Ext User")
            .set(U_EMAIL, "apiconn_ext_" + System.nanoTime() + "@example.com")
            .returning(U_ID)
            .fetchOne(r -> r.get(U_ID));
  }

  @AfterEach
  void tearDown() {
    // V96 이후 api_connection 은 RLS 대상이라 트랜잭션 밖 삭제는 GUC 부재로 0행이 되고,
    // 뒤이은 user 삭제가 FK 로 터진다. 도메인 정리는 테넌트 트랜잭션 안에서 한다.
    tx.executeWithoutResult(
        s -> dsl.deleteFrom(API_CONNECTION).where(AC_CREATED_BY.eq(testUserId)).execute());
    dsl.deleteFrom(USER_TABLE).where(U_ID.eq(testUserId)).execute();
  }

  private CreateApiConnectionRequest bearerReq(String baseUrl) {
    return new CreateApiConnectionRequest(
        "Ext Test Conn", null, "BEARER", Map.of("token", "test-token"), baseUrl, null);
  }

  // ── validateAndNormalizeBaseUrl: null/blank baseUrl ─────────────────────────

  @Test
  void create_nullBaseUrl_throwsApiConnectionException() {
    // null baseUrl → "baseUrl은 필수입니다"
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "Null URL", null, "BEARER", Map.of("token", "tok"), null, null);

    assertThatThrownBy(() -> apiConnectionService.create(req, testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("baseUrl은 필수입니다");
  }

  @Test
  void create_blankBaseUrl_throwsApiConnectionException() {
    // blank baseUrl → "baseUrl은 필수입니다"
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "Blank URL", null, "BEARER", Map.of("token", "tok"), "   ", null);

    assertThatThrownBy(() -> apiConnectionService.create(req, testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("baseUrl은 필수입니다");
  }

  // ── validateAndNormalizeBaseUrl: 호스트 없음 ────────────────────────────────

  @Test
  void create_baseUrlWithNoHost_throwsApiConnectionException() {
    // "http://" — 호스트가 빈 문자열 → "baseUrl에 호스트가 없습니다"
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "No Host", null, "BEARER", Map.of("token", "tok"), "http://", null);

    assertThatThrownBy(() -> apiConnectionService.create(req, testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("유효하지 않은 baseUrl");
  }

  // ── validateAndNormalizeBaseUrl: SsrfException → ApiConnectionException ────

  @Test
  void create_ssrfViolation_throwsApiConnectionException() {
    // SsrfProtectionService.validateUrl()이 SsrfException을 던지면
    // ApiConnectionException("SSRF 보호: ...")으로 래핑되어야 한다
    doThrow(new SsrfException("private IP blocked"))
        .when(ssrfProtectionService)
        .validateUrl(anyString());

    assertThatThrownBy(
            () -> apiConnectionService.create(bearerReq("https://192.168.1.1"), testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("SSRF 보호");
  }

  // ── validateAndNormalizeBaseUrl: IllegalArgumentException → ApiConnectionException ─

  @Test
  void create_illegalArgumentBaseUrl_throwsApiConnectionException() {
    // URI.create()가 IllegalArgumentException을 던지도록 — 공백 포함 URL
    // "유효하지 않은 baseUrl: ..." 메시지
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "Illegal URL", null, "BEARER", Map.of("token", "tok"), "http://invalid url", null);

    assertThatThrownBy(() -> apiConnectionService.create(req, testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("유효하지 않은 baseUrl");
  }

  // ── validateAuthType: 유효하지 않은 authType ────────────────────────────────

  @Test
  void create_invalidAuthType_throwsApiConnectionException() {
    // "BASIC" — 지원되지 않는 authType → "Unsupported authType" 예외
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "Bad AuthType",
            null,
            "BASIC",
            Map.of("username", "u", "password", "p"),
            "https://svc.example.com",
            null);

    assertThatThrownBy(() -> apiConnectionService.create(req, testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("Unsupported authType");
  }

  @Test
  void create_nullAuthType_throwsApiConnectionException() {
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "Null AuthType", null, null, Map.of("token", "tok"), "https://svc.example.com", null);

    assertThatThrownBy(() -> apiConnectionService.create(req, testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("Unsupported authType");
  }

  // ── update(): baseUrl 변경 경로 (validateAndNormalizeBaseUrl 호출) ───────────

  @Test
  void update_withNewBaseUrl_normalizesAndPersists() {
    // 생성 후 baseUrl 변경 → L121 (normalizedBaseUrl = validateAndNormalizeBaseUrl(...)) 커버
    ApiConnectionResponse created =
        apiConnectionService.create(bearerReq("https://old.example.com"), testUserId);

    UpdateApiConnectionRequest updateReq =
        new UpdateApiConnectionRequest(
            "Updated Conn",
            null,
            "BEARER",
            Map.of("token", "new-token"),
            "https://new.example.com/", // trailing slash → 정규화 후 제거
            null);

    ApiConnectionResponse updated = apiConnectionService.update(created.id(), updateReq);

    assertThat(updated.baseUrl()).isEqualTo("https://new.example.com");
    assertThat(updated.name()).isEqualTo("Updated Conn");
  }

  @Test
  void update_withInvalidBaseUrl_throwsApiConnectionException() {
    // update 시 잘못된 baseUrl → ApiConnectionException
    ApiConnectionResponse created =
        apiConnectionService.create(bearerReq("https://old.example.com"), testUserId);

    UpdateApiConnectionRequest updateReq =
        new UpdateApiConnectionRequest(null, null, null, null, "ftp://bad.scheme.com", null);

    assertThatThrownBy(() -> apiConnectionService.update(created.id(), updateReq))
        .isInstanceOf(ApiConnectionException.class);
  }

  // ── update(): authConfig 변경 시 authType이 null → fetchAuthType 경로 ────────

  @Test
  void update_authConfigWithoutAuthType_fetchesExistingAuthType() {
    // authType을 null로 두고 authConfig만 변경 → fetchAuthType(id) 호출 경로 (L124)
    ApiConnectionResponse created =
        apiConnectionService.create(bearerReq("https://fetch-type.example.com"), testUserId);

    UpdateApiConnectionRequest updateReq =
        new UpdateApiConnectionRequest(
            null,
            null,
            null, // authType = null → fetchAuthType
            Map.of("token", "fetched-type-token"),
            null,
            null);

    ApiConnectionResponse updated = apiConnectionService.update(created.id(), updateReq);

    // authType은 기존 "BEARER" 유지
    assertThat(updated.authType()).isEqualTo("BEARER");
    // 새 token이 반영됐는지 복호화 확인
    Map<String, String> decrypted = apiConnectionService.getDecryptedAuthConfig(created.id());
    assertThat(decrypted.get("token")).isEqualTo("fetched-type-token");
  }

  // ── update(): 존재하지 않는 ID → not found ──────────────────────────────────

  @Test
  void update_nonExistentId_throwsApiConnectionException() {
    UpdateApiConnectionRequest updateReq =
        new UpdateApiConnectionRequest("Name", null, "BEARER", null, null, null);

    assertThatThrownBy(() -> apiConnectionService.update(999999L, updateReq))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("not found");
  }

  // ── getDecryptedAuthConfig(): 존재하지 않는 ID ───────────────────────────────

  @Test
  void getDecryptedAuthConfig_nonExistentId_throwsApiConnectionException() {
    assertThatThrownBy(() -> apiConnectionService.getDecryptedAuthConfig(999999L))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("not found");
  }

  // ── getById(): 존재하지 않는 ID ──────────────────────────────────────────────

  @Test
  void getById_nonExistentId_throwsApiConnectionException() {
    assertThatThrownBy(() -> apiConnectionService.getById(999998L))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("not found");
  }

  // ── delete(): 존재하지 않는 ID ───────────────────────────────────────────────

  @Test
  void delete_nonExistentId_throwsApiConnectionException() {
    assertThatThrownBy(() -> apiConnectionService.delete(999997L))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("not found");
  }

  // ── delete(): 정상 삭제 후 조회 시 not-found ─────────────────────────────────

  @Test
  void delete_existingConnection_removesIt() {
    ApiConnectionResponse created =
        apiConnectionService.create(bearerReq("https://delete-ext.example.com"), testUserId);
    Long id = created.id();

    apiConnectionService.delete(id);

    assertThatThrownBy(() -> apiConnectionService.getById(id))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("not found");
  }

  // ── testConnection(): 연결 불가 URL → DOWN 상태 반환 ────────────────────────

  @Test
  void testConnection_unreachableUrl_returnsDownStatus() {
    // 실제 연결 불가 URL로 테스트 — 5초 타임아웃 후 DOWN 반환
    // SSRF mock은 no-op이므로 검증 통과
    ApiConnectionResponse created =
        apiConnectionService.create(
            new CreateApiConnectionRequest(
                "HC Test Conn",
                null,
                "BEARER",
                Map.of("token", "hc-token"),
                "https://unreachable-test-host-99999.example.com",
                "/health"),
            testUserId);

    // testConnection은 예외를 던지지 않고 TestConnectionResponse 반환
    TestConnectionResponse result = apiConnectionService.testConnection(created.id());

    // 연결 불가이므로 ok=false
    assertThat(result.ok()).isFalse();
    assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
  }

  // ── testConnection(): healthCheckPath 없는 경우 baseUrl만 사용 ────────────────

  @Test
  void testConnection_noHealthCheckPath_usesBaseUrl() {
    // healthCheckPath가 null → baseUrl 자체로 GET
    ApiConnectionResponse created =
        apiConnectionService.create(
            new CreateApiConnectionRequest(
                "No HC Path Conn",
                null,
                "API_KEY",
                Map.of("headerName", "X-API-Key", "apiKey", "test-key"),
                "https://nohcpath-test-99999.example.com",
                null),
            testUserId);

    // 연결 불가이지만 예외 없이 응답 반환 검증
    TestConnectionResponse result = apiConnectionService.testConnection(created.id());
    assertThat(result.ok()).isFalse();
  }

  // ── testConnection(): 5초 초과 지연 응답 → 사람이 읽을 수 있는 타임아웃 메시지 (#532) ────────

  @Test
  void testConnection_timeout_returnsHumanReadableMessage() {
    // WireMock이 5초(.block 타임아웃)보다 긴 지연으로 응답 → Reactor가
    // "Timeout on blocking read for 5000000000 NANOSECONDS" 를 던지는데,
    // 이 원시 메시지가 그대로 errorMessage/감사 로그에 노출되면 안 된다.
    wireMock.stubFor(
        get(urlEqualTo("/health")).willReturn(aResponse().withFixedDelay(6000).withStatus(200)));

    ApiConnectionResponse created =
        apiConnectionService.create(
            new CreateApiConnectionRequest(
                "Timeout Test Conn",
                null,
                "BEARER",
                Map.of("token", "test-token"),
                "http://localhost:" + wireMock.port(),
                "/health"),
            testUserId);

    TestConnectionResponse result = apiConnectionService.testConnection(created.id());

    assertThat(result.ok()).isFalse();
    assertThat(result.errorMessage()).isEqualTo("응답 시간 초과 (5초)");
    assertThat(result.errorMessage()).doesNotContain("NANOSECONDS");

    // 감사 로그/상세 페이지가 실제로 읽는 것은 DB에 저장된 lastErrorMessage다 —
    // DTO 반환값만 확인하면 repository.updateHealthStatus 경로가 안 고쳐진 회귀를 못 잡는다.
    assertThat(apiConnectionService.getById(created.id()).lastErrorMessage())
        .isEqualTo("응답 시간 초과 (5초)");
  }

  // ── refreshAllAsync(): jobId 반환 및 비동기 실행 시작 ─────────────────────────

  @org.junit.jupiter.api.Disabled("async_job FK 제약으로 인해 독립 실행 불가")
  @Test
  void refreshAllAsync_returnsJobId() {
    // refreshAllAsync는 Job을 생성하고 jobId(UUID 문자열)를 반환해야 한다
    String jobId = apiConnectionService.refreshAllAsync(testUserId);

    assertThat(jobId).isNotNull();
    assertThat(jobId).isNotEmpty();
    // UUID 형식 확인 (8-4-4-4-12)
    assertThat(jobId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  // ── validateAndNormalizeBaseUrl: http/https가 아닌 스킴(null scheme 아님) ───────

  @Test
  void create_ftpScheme_throwsApiConnectionException() {
    // ftp:// → "baseUrl은 http 또는 https 스킴이어야 합니다" 분기 커버
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "FTP Conn", null, "BEARER", Map.of("token", "tok"), "ftp://files.example.com", null);

    assertThatThrownBy(() -> apiConnectionService.create(req, testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("http 또는 https");
  }

  // ── update(): authType 검증 — authConfig와 authType 모두 있을 때 validateAuthType ─

  @Test
  void update_withAuthConfigAndInvalidAuthType_throwsApiConnectionException() {
    // authConfig != null && authType != null → validateAuthType 호출(L113)
    ApiConnectionResponse created =
        apiConnectionService.create(bearerReq("https://validate-type.example.com"), testUserId);

    UpdateApiConnectionRequest updateReq =
        new UpdateApiConnectionRequest(
            null, null, "INVALID_TYPE", Map.of("token", "tok"), null, null);

    assertThatThrownBy(() -> apiConnectionService.update(created.id(), updateReq))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("Unsupported authType");
  }
}
