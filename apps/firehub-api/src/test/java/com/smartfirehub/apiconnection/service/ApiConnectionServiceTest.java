package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.*;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.ApiConnectionSelectableResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.UpdateApiConnectionRequest;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * ApiConnectionService 통합 테스트. Phase 9: baseUrl 정규화, SSRF 검증(로컬 테스트 환경에서 localhost는 차단됨),
 * findSelectable slim 목록, testConnection은 Scheduler Task에서 통합 커버.
 */
class ApiConnectionServiceTest extends IntegrationTestBase {

  @Autowired private ApiConnectionService apiConnectionService;

  @Autowired private ApiConnectionRepository apiConnectionRepository;

  @Autowired private DSLContext dsl;

  /**
   * SSRF 보호 서비스를 mock으로 교체 — 테스트 환경에서 DNS 해석이 불가한 외부 도메인 사용 허용. validateUrl은 아무 동작도 하지 않도록 기본
   * no-op으로 유지.
   */
  @MockitoBean private SsrfProtectionService ssrfProtectionService;

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

  /** 기본 테스트용 유효 요청 빌더 — 외부 URL 사용 (SSRF 검증 통과) */
  private CreateApiConnectionRequest validReq(String baseUrl) {
    return new CreateApiConnectionRequest(
        "Test Conn",
        null,
        "API_KEY",
        Map.of("placement", "header", "headerName", "X-Key", "apiKey", "secret"),
        baseUrl,
        null);
  }

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER_TABLE)
            .set(U_USERNAME, "apiconn_testuser_" + System.nanoTime())
            .set(U_PASSWORD, "password")
            .set(U_NAME, "API Conn Test User")
            .set(U_EMAIL, "apiconn_" + System.nanoTime() + "@example.com")
            .returning(U_ID)
            .fetchOne(r -> r.get(U_ID));
  }

  @AfterEach
  void tearDown() {
    // FK 순서: api_connection → user
    dsl.deleteFrom(API_CONNECTION).where(AC_CREATED_BY.eq(testUserId)).execute();
    dsl.deleteFrom(USER_TABLE).where(U_ID.eq(testUserId)).execute();
  }

  // ── 기존 테스트 (baseUrl 추가) ──────────────────────────────────────────────

  @Test
  void createAndGet_apiKeyConnection() {
    Map<String, String> authConfig =
        Map.of(
            "headerName", "X-API-Key",
            "apiKey", "my-super-secret-key-1234");
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "My API Key Conn",
            "Test API key connection",
            "API_KEY",
            authConfig,
            "https://api.example.com",
            null);

    ApiConnectionResponse created = apiConnectionService.create(req, testUserId);

    assertThat(created.id()).isNotNull();
    assertThat(created.name()).isEqualTo("My API Key Conn");
    assertThat(created.authType()).isEqualTo("API_KEY");
    assertThat(created.baseUrl()).isEqualTo("https://api.example.com");

    // 민감 키 "apiKey"는 마스킹
    assertThat(created.maskedAuthConfig().get("apiKey")).startsWith("****");
    // headerName은 민감하지 않으므로 원본 반환
    assertThat(created.maskedAuthConfig().get("headerName")).isEqualTo("X-API-Key");

    ApiConnectionResponse fetched = apiConnectionService.getById(created.id());
    assertThat(fetched.id()).isEqualTo(created.id());
    assertThat(fetched.name()).isEqualTo("My API Key Conn");
  }

  @Test
  void createAndGet_bearerConnection() {
    Map<String, String> authConfig = Map.of("token", "Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig");
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "Bearer Conn",
            "A bearer token connection",
            "BEARER",
            authConfig,
            "https://auth.example.com",
            "/health");

    ApiConnectionResponse created = apiConnectionService.create(req, testUserId);

    assertThat(created.authType()).isEqualTo("BEARER");
    assertThat(created.healthCheckPath()).isEqualTo("/health");
    // "token" 키는 민감 — 마스킹
    assertThat(created.maskedAuthConfig().get("token")).startsWith("****");
    assertThat(created.maskedAuthConfig().get("token")).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
  }

  @Test
  void getDecryptedAuthConfig_returnsPlaintext() {
    Map<String, String> authConfig = Map.of("token", "plaintext-bearer-token-xyz");
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "Decrypt Test", null, "BEARER", authConfig, "https://svc.example.com", null);
    ApiConnectionResponse created = apiConnectionService.create(req, testUserId);

    Map<String, String> decrypted = apiConnectionService.getDecryptedAuthConfig(created.id());

    assertThat(decrypted.get("token")).isEqualTo("plaintext-bearer-token-xyz");
  }

  @Test
  void update_changesNameAndDescription() {
    Map<String, String> authConfig = Map.of("apiKey", "original-key-value-5678");
    CreateApiConnectionRequest createReq =
        new CreateApiConnectionRequest(
            "Original Name",
            "Original Desc",
            "API_KEY",
            authConfig,
            "https://orig.example.com",
            null);
    ApiConnectionResponse created = apiConnectionService.create(createReq, testUserId);

    // name/description만 변경 — authConfig/baseUrl 미변경
    UpdateApiConnectionRequest updateReq =
        new UpdateApiConnectionRequest("Updated Name", "Updated Desc", null, null, null, null);
    ApiConnectionResponse updated = apiConnectionService.update(created.id(), updateReq);

    assertThat(updated.name()).isEqualTo("Updated Name");
    assertThat(updated.description()).isEqualTo("Updated Desc");

    // 자격증명은 원본 유지
    Map<String, String> decrypted = apiConnectionService.getDecryptedAuthConfig(created.id());
    assertThat(decrypted.get("apiKey")).isEqualTo("original-key-value-5678");
  }

  @Test
  void update_changesAuthConfig() {
    Map<String, String> originalConfig = Map.of("token", "old-token-aaaa");
    CreateApiConnectionRequest createReq =
        new CreateApiConnectionRequest(
            "Token Conn", null, "BEARER", originalConfig, "https://token.example.com", null);
    ApiConnectionResponse created = apiConnectionService.create(createReq, testUserId);

    Map<String, String> newConfig = Map.of("token", "new-token-bbbb");
    UpdateApiConnectionRequest updateReq =
        new UpdateApiConnectionRequest("Token Conn", null, "BEARER", newConfig, null, null);
    apiConnectionService.update(created.id(), updateReq);

    Map<String, String> decrypted = apiConnectionService.getDecryptedAuthConfig(created.id());
    assertThat(decrypted.get("token")).isEqualTo("new-token-bbbb");
  }

  /**
   * (#115) healthCheckPath 를 빈 문자열로 update 하면 DB 의 기존 값이 NULL 로 clear 되어야 한다. 기존에는 service 가 ""→null 로
   * 정규화하고 repository 가 null 을 "미변경"으로 해석해서 clear 가 불가능했다.
   */
  @Test
  void update_emptyHealthCheckPath_clearsToNull() {
    CreateApiConnectionRequest createReq =
        new CreateApiConnectionRequest(
            "HC Clear",
            null,
            "API_KEY",
            Map.of("apiKey", "k"),
            "https://hc-clear.example.com",
            "/health");
    ApiConnectionResponse created = apiConnectionService.create(createReq, testUserId);
    assertThat(created.healthCheckPath()).isEqualTo("/health");

    // 빈 문자열로 update → clear
    UpdateApiConnectionRequest clearReq =
        new UpdateApiConnectionRequest(null, null, null, null, null, "");
    apiConnectionService.update(created.id(), clearReq);

    ApiConnectionResponse afterClear = apiConnectionService.getById(created.id());
    assertThat(afterClear.healthCheckPath()).isNull();
  }

  /** (#115) healthCheckPath 가 null 인 update 는 기존 값을 유지해야 한다 (PATCH semantics). */
  @Test
  void update_nullHealthCheckPath_preservesExisting() {
    CreateApiConnectionRequest createReq =
        new CreateApiConnectionRequest(
            "HC Keep",
            null,
            "API_KEY",
            Map.of("apiKey", "k"),
            "https://hc-keep.example.com",
            "/health");
    ApiConnectionResponse created = apiConnectionService.create(createReq, testUserId);

    // healthCheckPath=null → 미변경
    UpdateApiConnectionRequest keepReq =
        new UpdateApiConnectionRequest("Renamed", null, null, null, null, null);
    apiConnectionService.update(created.id(), keepReq);

    ApiConnectionResponse afterKeep = apiConnectionService.getById(created.id());
    assertThat(afterKeep.name()).isEqualTo("Renamed");
    assertThat(afterKeep.healthCheckPath()).isEqualTo("/health");
  }

  @Test
  void delete_removesConnection() {
    Map<String, String> authConfig = Map.of("apiKey", "delete-me-key-9999");
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "To Delete", null, "API_KEY", authConfig, "https://del.example.com", null);
    ApiConnectionResponse created = apiConnectionService.create(req, testUserId);
    Long id = created.id();

    apiConnectionService.delete(id);

    assertThatThrownBy(() -> apiConnectionService.getById(id))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void getAll_returnsMultiple() {
    Map<String, String> config1 = Map.of("apiKey", "key-one-1111");
    Map<String, String> config2 = Map.of("token", "token-two-2222");

    apiConnectionService.create(
        new CreateApiConnectionRequest(
            "Conn One", null, "API_KEY", config1, "https://one.example.com", null),
        testUserId);
    apiConnectionService.create(
        new CreateApiConnectionRequest(
            "Conn Two", null, "BEARER", config2, "https://two.example.com", null),
        testUserId);

    List<ApiConnectionResponse> all = apiConnectionService.getAll();

    long ownedByTestUser = all.stream().filter(r -> r.createdBy().equals(testUserId)).count();
    assertThat(ownedByTestUser).isGreaterThanOrEqualTo(2);
  }

  // ── Phase 9 신규 테스트 ─────────────────────────────────────────────────────

  @Test
  void create_withBaseUrl_normalizesTrailingSlash() {
    // baseUrl 끝 슬래시는 저장 전 제거되어야 한다
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "Trailing Slash Test",
            null,
            "API_KEY",
            Map.of("placement", "header", "headerName", "X-Key", "apiKey", "secret"),
            "https://api.example.com/",
            "/health");

    ApiConnectionResponse resp = apiConnectionService.create(req, testUserId);

    assertThat(resp.baseUrl()).isEqualTo("https://api.example.com");
    assertThat(resp.healthCheckPath()).isEqualTo("/health");
  }

  @Test
  void create_invalidBaseUrlScheme_throwsException() {
    // ftp:// 스킴은 허용하지 않는다
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "FTP Test",
            null,
            "API_KEY",
            Map.of("placement", "header", "headerName", "X-Key", "apiKey", "secret"),
            "ftp://bad.com",
            null);

    assertThatThrownBy(() -> apiConnectionService.create(req, testUserId))
        .isInstanceOf(ApiConnectionException.class);
  }

  @Test
  void findSelectable_returnsSlim_withoutAuthConfig() {
    // slim DTO에는 authConfig 필드가 없어야 한다 (컴파일 레벨 보장)
    apiConnectionService.create(validReq("https://a.example.com"), testUserId);
    apiConnectionService.create(validReq("https://b.example.com"), testUserId);

    List<ApiConnectionSelectableResponse> list = apiConnectionService.findSelectable();

    long ownedByTestUser =
        list.stream()
            .filter(
                r ->
                    "https://a.example.com".equals(r.baseUrl())
                        || "https://b.example.com".equals(r.baseUrl()))
            .count();
    assertThat(ownedByTestUser).isGreaterThanOrEqualTo(2);

    // id, name, authType, baseUrl 필드만 존재 — authConfig 없음 (컴파일 타임 보장)
    ApiConnectionSelectableResponse first =
        list.stream()
            .filter(r -> "https://a.example.com".equals(r.baseUrl()))
            .findFirst()
            .orElseThrow();
    assertThat(first.id()).isNotNull();
    assertThat(first.name()).isEqualTo("Test Conn");
    assertThat(first.authType()).isEqualTo("API_KEY");
    assertThat(first.baseUrl()).isEqualTo("https://a.example.com");
  }
}
