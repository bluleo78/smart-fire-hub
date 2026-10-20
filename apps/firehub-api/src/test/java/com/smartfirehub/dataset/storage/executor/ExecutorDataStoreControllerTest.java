package com.smartfirehub.dataset.storage.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreProvisionMode;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.platform.repository.PlatformTenantRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@link ExecutorDataStoreController} 테스트(WD-75). HTTP 경로(인증·직렬화·409 코드)는 RANDOM_PORT 통합으로, 토큰 설정
 * 검증(R-token)과 헤더는 컨트롤러를 직접 만들어 본다. 컨텍스트를 새로 만들지 않는다.
 */
class ExecutorDataStoreControllerTest extends IntegrationTestBase {

  @LocalServerPort private int port;

  @Value("${app.executor.internal-token}")
  private String token;

  @Autowired private TenantDataStoreRepository repository;
  @Autowired private PlatformTenantRepository tenantRepository;
  @Autowired private EncryptionService encryption;
  @Autowired private DSLContext dsl;

  private final HttpClient http = HttpClient.newHttpClient();
  private long tenantId;

  @AfterEach
  void cleanUp() {
    if (tenantId > 0) {
      TenantRlsTestSupport.deleteProvisionedTenantCascade(
          dsl, fixtureTransactionTemplate, tenantId);
      tenantId = 0;
    }
  }

  private HttpResponse<String> get(long id, String authorization) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(
            URI.create(
                "http://localhost:" + port + "/internal/executor/tenants/" + id + "/data-store"));
    if (authorization != null) {
      b.header("Authorization", authorization);
    }
    return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private long newTibero(TenantDataStoreStatus status) {
    String slug = "ex-" + UUID.randomUUID().toString().substring(0, 8);
    tenantId = tenantRepository.insertTenant(slug, "기술자 테스트 " + slug);
    repository.insert(
        new TenantDataStoreRow(
            tenantId,
            DataStoreBackend.TIBERO,
            "tibero.example",
            8629,
            "tibero",
            "GENIA_T" + tenantId,
            "TS_T" + tenantId,
            DataStoreProvisionMode.SCRIPT,
            status),
        DataStoreAccountRole.all().stream()
            .map(
                r ->
                    new StoredAccount(
                        r, r.usernameFor(tenantId), encryption.encrypt("plain-" + r.name())))
            .toList());
    return tenantId;
  }

  // ---- HTTP 경로 ----

  @Test
  void 토큰없음_401() throws Exception {
    assertThat(get(DEFAULT_TEST_TENANT_ID, null).statusCode()).isEqualTo(401);
  }

  @Test
  void 잘못된토큰_401() throws Exception {
    assertThat(get(DEFAULT_TEST_TENANT_ID, "Internal wrong-token").statusCode()).isEqualTo(401);
  }

  @Test
  void PG테넌트_200_backend만() throws Exception {
    HttpResponse<String> res = get(DEFAULT_TEST_TENANT_ID, "Internal " + token);

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("\"backend\":\"POSTGRESQL\"").doesNotContain("password");
  }

  @Test
  void Tibero_READY_200_owner와_no_store() throws Exception {
    long id = newTibero(TenantDataStoreStatus.READY);

    HttpResponse<String> res = get(id, "Internal " + token);

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body())
        .contains("\"backend\":\"TIBERO\"")
        .contains("\"username\":\"GENIA_T" + id + "\"")
        .contains("\"password\":\"plain-OWNER\"")
        // RO·슬롯 비밀번호는 내보내지 않는다.
        .doesNotContain("plain-RO")
        .doesNotContain("plain-S1");
  }

  @Test
  void Tibero_PENDING_409_code() throws Exception {
    long id = newTibero(TenantDataStoreStatus.PENDING);

    HttpResponse<String> res = get(id, "Internal " + token);

    assertThat(res.statusCode()).isEqualTo(409);
    assertThat(res.body()).contains("\"code\":\"DATA_STORE_NOT_READY\"");
  }

  // ---- R-token: 직접 생성 ----

  private static ExecutorDataStoreDescriptorService stubService() {
    ExecutorDataStoreDescriptorService service = mock(ExecutorDataStoreDescriptorService.class);
    when(service.describe(anyLong(), anyBoolean()))
        .thenReturn(ExecutorDataStoreDescriptor.postgres(1));
    return service;
  }

  private static void assertNotConfigured(ExecutorDataStoreController c, String header) {
    assertThatThrownBy(() -> c.describe(header, 1))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(e.code()).isEqualTo("EXECUTOR_TOKEN_NOT_CONFIGURED");
            });
  }

  @Test
  void 빈설정토큰_503_service_미호출() {
    ExecutorDataStoreDescriptorService service = stubService();
    ExecutorDataStoreController c = new ExecutorDataStoreController(service, "", false);

    assertNotConfigured(c, "Internal ");
    verify(service, never()).describe(anyLong(), anyBoolean());
  }

  @Test
  void 공백설정토큰_503() {
    assertNotConfigured(
        new ExecutorDataStoreController(stubService(), "   ", true), "Internal    ");
  }

  @Test
  void 기본토큰_플래그없으면_서비스에_비밀불허_전달_PG는_200() {
    ExecutorDataStoreDescriptorService service = stubService();
    for (String insecure : ExecutorDataStoreController.INSECURE_DEFAULT_TOKENS) {
      ResponseEntity<ExecutorDataStoreDescriptor> res =
          new ExecutorDataStoreController(service, insecure, false)
              .describe("Internal " + insecure, 1);
      assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    verify(service, times(2)).describe(1, false);
  }

  @Test
  void 기본토큰_플래그있으면_비밀허용_전달() {
    ExecutorDataStoreDescriptorService service = stubService();

    new ExecutorDataStoreController(service, "dev-executor-token", true)
        .describe("Internal dev-executor-token", 1);

    verify(service).describe(1, true);
  }

  @Test
  void 기본토큰_틀린헤더_401() {
    ExecutorDataStoreDescriptorService service = stubService();

    assertThat(
            new ExecutorDataStoreController(service, "executor-token", false)
                .describe("Internal nope", 1)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    verify(service, never()).describe(anyLong(), anyBoolean());
  }

  /** 기본 토큰 + Tibero 테넌트: 실제 서비스·DB 로 503 이고 본문에 비밀번호가 없다. 플래그를 켜면 200. */
  @Test
  void 기본토큰_Tibero_503_본문에_비밀번호없음_플래그면_200() {
    long id = newTibero(TenantDataStoreStatus.READY);
    ExecutorDataStoreDescriptorService real =
        new ExecutorDataStoreDescriptorService(repository, encryption);

    ExecutorDataStoreController refused =
        new ExecutorDataStoreController(real, "executor-token", false);
    assertThatThrownBy(() -> refused.describe("Internal executor-token", id))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(e.code()).isEqualTo("EXECUTOR_TOKEN_NOT_CONFIGURED");
              assertThat(e.getMessage()).doesNotContain("plain-");
            });

    ResponseEntity<ExecutorDataStoreDescriptor> allowed =
        new ExecutorDataStoreController(real, "executor-token", true)
            .describe("Internal executor-token", id);
    assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(allowed.getBody().owner().password()).isEqualTo("plain-OWNER");
  }

  @Test
  void 커스텀토큰_OK_틀리면_401() {
    ExecutorDataStoreController c =
        new ExecutorDataStoreController(stubService(), "s3cret-x", false);

    assertThat(c.describe("Internal s3cret-x", 1).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(c.describe("Internal nope", 1).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(c.describe(null, 1).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void 성공응답_no_store() {
    ResponseEntity<ExecutorDataStoreDescriptor> res =
        new ExecutorDataStoreController(stubService(), "t", false).describe("Internal t", 1);

    assertThat(res.getHeaders().getCacheControl()).contains("no-store");
  }
}
