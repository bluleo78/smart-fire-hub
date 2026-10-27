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

  /** Python 실행기 토큰 — 저장소 종류만 받는다. */
  @Value("${app.executor.internal-token}")
  private String executorToken;

  /** db-executor 토큰 — 전체 기술자(OWNER·RO)를 받는다. */
  @Value("${app.db-executor.internal-token}")
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
    return get(id, authorization, "");
  }

  /** query 는 {@code ?include=ro} 처럼 물음표를 포함해 넘긴다(없으면 빈 문자열). */
  private HttpResponse<String> get(long id, String authorization, String query) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(
            URI.create(
                "http://localhost:"
                    + port
                    + "/internal/executor/tenants/"
                    + id
                    + "/data-store"
                    + query));
    if (authorization != null) {
      b.header("Authorization", authorization);
    }
    return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  /** 세대 창구는 전체 기술자와 같은 내부 토큰을 요구하지만 비밀번호는 반환하지 않는다. */
  private HttpResponse<String> getEpoch(long id, String authorization) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port
        + "/internal/executor/tenants/" + id + "/script-token-epoch"));
    if (authorization != null) {
      b.header("Authorization", authorization);
    }
    return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void scriptTokenEpoch_requiresDbExecutorTokenAndReturnsOnlyEpoch() throws Exception {
    long id = newTibero(TenantDataStoreStatus.READY);
    repository.incrementScriptTokenEpoch(id);

    assertThat(getEpoch(id, null).statusCode()).isEqualTo(401);
    assertThat(getEpoch(id, "Internal " + executorToken).statusCode()).isEqualTo(401);
    HttpResponse<String> res = getEpoch(id, "Internal " + token);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).isEqualTo("{\"scriptTokenEpoch\":1}");
    assertThat(res.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
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

  // ---- include=ro (WD-76) ----

  @Test
  void describe_includeRo_addsDecryptedRoAccount() throws Exception {
    long id = newTibero(TenantDataStoreStatus.READY);

    HttpResponse<String> res = get(id, "Internal " + token, "?include=ro");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body())
        .contains("\"ro\":{\"username\":\"GENIA_T" + id + "_RO\",\"password\":\"plain-RO\"}")
        // 소유 계정은 그대로, 슬롯 비밀번호는 여전히 내지 않는다.
        .contains("\"password\":\"plain-OWNER\"")
        .doesNotContain("plain-S1");
  }

  @Test
  void describe_withoutInclude_hasNoRoField() throws Exception {
    long id = newTibero(TenantDataStoreStatus.READY);

    // Python 실행기 계약: include 가 없거나 ro 가 아니면 ro 키 자체가 없다.
    assertThat(get(id, "Internal " + token).body()).doesNotContain("\"ro\"");
    assertThat(get(id, "Internal " + token, "?include=other").body()).doesNotContain("\"ro\"");
    assertThat(get(id, "Internal " + token, "?include=RO").body()).doesNotContain("\"ro\"");
  }

  @Test
  void describe_includeRo_pgTenant_noRo() throws Exception {
    HttpResponse<String> res = get(DEFAULT_TEST_TENANT_ID, "Internal " + token, "?include=ro");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("\"backend\":\"POSTGRESQL\"").doesNotContain("\"ro\"");
  }

  @Test
  void describe_includeRo_missingRoRow_omitsRo() throws Exception {
    long id = newTibero(TenantDataStoreStatus.READY);
    // RO 행만 지운다 — 실패 안내는 db-executor 가 한다.
    dsl.deleteFrom(org.jooq.impl.DSL.table("tenant_data_store_account"))
        .where(org.jooq.impl.DSL.field("tenant_id").eq(id))
        .and(org.jooq.impl.DSL.field("account_role").eq("RO"))
        .execute();

    HttpResponse<String> res = get(id, "Internal " + token, "?include=ro");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).doesNotContain("\"ro\"").contains("\"password\":\"plain-OWNER\"");
  }

  // ---- include=slots (WD-78) ----

  /** db-executor 토큰 + include=ro,slots: S1..S10 을 복호화해 번호순으로 싣는다. */
  @Test
  void describe_includeSlots_dbExecutorToken_slotsDecrypted() throws Exception {
    long id = newTibero(TenantDataStoreStatus.READY);

    HttpResponse<String> res = get(id, "Internal " + token, "?include=ro,slots");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body())
        .contains("{\"slot\":1,\"username\":\"GENIA_T" + id + "_S1\",\"password\":\"plain-S1\"}")
        .contains("{\"slot\":10,\"username\":\"GENIA_T" + id + "_S10\",\"password\":\"plain-S10\"}")
        .contains("\"password\":\"plain-RO\"")
        .contains("\"scriptTokenEpoch\":");
  }

  /** Python 실행기 토큰은 include=slots 여도 슬롯·비밀이 없다. */
  @Test
  void describe_includeSlots_executorToken_noSecrets() throws Exception {
    long id = newTibero(TenantDataStoreStatus.READY);

    HttpResponse<String> res = get(id, "Internal " + executorToken, "?include=ro,slots");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body())
        .doesNotContain("slots")
        .doesNotContain("plain-")
        .doesNotContain("password");
  }

  @Test
  void Tibero_PENDING_409_code() throws Exception {
    long id = newTibero(TenantDataStoreStatus.PENDING);

    HttpResponse<String> res = get(id, "Internal " + token);

    assertThat(res.statusCode()).isEqualTo(409);
    assertThat(res.body()).contains("\"code\":\"DATA_STORE_NOT_READY\"");
  }

  // ---- 토큰 분리(스펙 §3.4): Python 실행기 토큰은 저장소 종류만 받는다 ----

  @Test
  void 실행기토큰_Tibero_종류와_테넌트만_비밀_접속정보_없음() throws Exception {
    long id = newTibero(TenantDataStoreStatus.READY);

    HttpResponse<String> res = get(id, "Internal " + executorToken, "?include=ro");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body())
        .contains("\"backend\":\"TIBERO\"")
        .contains("\"tenantId\":" + id)
        .doesNotContain("owner")
        .doesNotContain("\"ro\"")
        .doesNotContain("host")
        .doesNotContain("password")
        .doesNotContain("plain-");
  }

  @Test
  void 실행기토큰_PG테넌트_기존과_같은_응답() throws Exception {
    HttpResponse<String> res = get(DEFAULT_TEST_TENANT_ID, "Internal " + executorToken);

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("\"backend\":\"POSTGRESQL\"").doesNotContain("password");
  }

  @Test
  void db실행기토큰_Tibero_전체기술자() throws Exception {
    long id = newTibero(TenantDataStoreStatus.READY);

    HttpResponse<String> res = get(id, "Internal " + token, "?include=ro");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body())
        .contains("\"owner\":{\"username\":\"GENIA_T" + id + "\"")
        .contains("\"password\":\"plain-OWNER\"")
        .contains("\"password\":\"plain-RO\"")
        .contains("\"host\":\"tibero.example\"");
  }

  @Test
  void 두토큰이_같으면_기동_실패() {
    ExecutorDataStoreDescriptorService service = stubService();

    assertThatThrownBy(() -> new ExecutorDataStoreController(service, "same", "same", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.executor.internal-token")
        .hasMessageContaining("app.db-executor.internal-token");
  }

  @Test
  void 실행기토큰_service는_종류만_조회() {
    ExecutorDataStoreDescriptorService service = stubService();
    when(service.describeBackendOnly(anyLong()))
        .thenReturn(ExecutorDataStoreDescriptor.postgres(1));

    new ExecutorDataStoreController(service, "exec-t", "db-t", false)
        .describe("Internal exec-t", 1, "ro");

    verify(service).describeBackendOnly(1);
    verify(service, never()).describe(anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  // ---- R-token: 직접 생성 ----

  private static ExecutorDataStoreDescriptorService stubService() {
    ExecutorDataStoreDescriptorService service = mock(ExecutorDataStoreDescriptorService.class);
    when(service.describe(anyLong(), anyBoolean(), anyBoolean(), anyBoolean()))
        .thenReturn(ExecutorDataStoreDescriptor.postgres(1));
    return service;
  }

  private static void assertNotConfigured(ExecutorDataStoreController c, String header) {
    assertThatThrownBy(() -> c.describe(header, 1, null))
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
    ExecutorDataStoreController c = new ExecutorDataStoreController(service, "", "", false);

    assertNotConfigured(c, "Internal ");
    verify(service, never()).describe(anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  @Test
  void 공백설정토큰_503() {
    assertNotConfigured(
        new ExecutorDataStoreController(stubService(), "   ", "  ", true), "Internal    ");
  }

  @Test
  void 기본토큰_플래그없으면_서비스에_비밀불허_전달_PG는_200() {
    ExecutorDataStoreDescriptorService service = stubService();
    for (String insecure : ExecutorDataStoreController.INSECURE_DEFAULT_TOKENS) {
      ResponseEntity<ExecutorDataStoreDescriptor> res =
          new ExecutorDataStoreController(service, "exec-x", insecure, false)
              .describe("Internal " + insecure, 1, null);
      assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    verify(service, times(ExecutorDataStoreController.INSECURE_DEFAULT_TOKENS.size()))
        .describe(1, false, false, false);
  }

  /**
   * 최종 리뷰 I2: {@code .env.example} 자리표시자({@code change-me…})를 그대로 옮긴 db-executor 토큰도 알려진 기본값처럼 비밀을
   * 내지 않는다(Tibero 는 서비스가 503). 허용 플래그(로컬·테스트)가 켜져 있으면 기존 기본값과 같이 통과한다.
   */
  @Test
  void change_me_자리표시자토큰_플래그없으면_비밀불허_있으면_허용() {
    ExecutorDataStoreDescriptorService service = stubService();
    String placeholder = "change-me-db-executor-internal-token";

    new ExecutorDataStoreController(service, "exec-x", placeholder, false)
        .describe("Internal " + placeholder, 1, null);
    verify(service).describe(1, false, false, false);

    new ExecutorDataStoreController(service, "exec-x", placeholder, true)
        .describe("Internal " + placeholder, 1, null);
    verify(service).describe(1, true, false, false);
  }

  @Test
  void 기본토큰_플래그있으면_비밀허용_전달() {
    ExecutorDataStoreDescriptorService service = stubService();

    new ExecutorDataStoreController(service, "exec-x", "dev-db-executor-token", true)
        .describe("Internal dev-db-executor-token", 1, null);

    verify(service).describe(1, true, false, false);
  }

  /**
   * include 는 쉼표로 나눈 정확한 값(ro·slots)만 받는다. 모르는 값이 하나라도 섞이면 아무것도 싣지 않는다(fail-closed — 오타가 비밀을 더 내게
   * 하지 않는다). 대소문자도 정확히 맞아야 한다.
   */
  @Test
  void include_ro와_slots만_정확히_전달() {
    ExecutorDataStoreDescriptorService service = stubService();
    ExecutorDataStoreController c = new ExecutorDataStoreController(service, "exec-t", "t", false);

    c.describe("Internal t", 1, "ro");
    verify(service).describe(1, true, true, false);
    c.describe("Internal t", 2, "RO");
    c.describe("Internal t", 3, "ro,x");
    verify(service).describe(2, true, false, false);
    verify(service).describe(3, true, false, false);
    c.describe("Internal t", 4, "slots");
    verify(service).describe(4, true, false, true);
    c.describe("Internal t", 5, "ro,slots");
    verify(service).describe(5, true, true, true);
    c.describe("Internal t", 6, "slots,ro");
    verify(service).describe(6, true, true, true);
    for (long id : new long[] {7, 8, 9, 10}) {
      c.describe("Internal t", id, new String[] {"", "SLOTS", "slots,", "slots, ro"}[(int) id - 7]);
      verify(service).describe(id, true, false, false);
    }
  }

  /** Python 실행기 토큰은 include=slots 여도 종류만 — 슬롯 비밀번호를 복호화하지 않는다(Task 5 경계 유지). */
  @Test
  void 실행기토큰_include_slots_무시() {
    ExecutorDataStoreDescriptorService service = stubService();
    when(service.describeBackendOnly(anyLong()))
        .thenReturn(ExecutorDataStoreDescriptor.postgres(1));

    new ExecutorDataStoreController(service, "exec-t", "db-t", false)
        .describe("Internal exec-t", 1, "ro,slots");

    verify(service).describeBackendOnly(1);
    verify(service, never()).describe(anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  /** 기본 토큰 + include=ro + Tibero: OWNER 와 같이 503 이고 RO 비밀번호도 새지 않는다. */
  @Test
  void describe_includeRo_defaultToken_503() {
    long id = newTibero(TenantDataStoreStatus.READY);
    ExecutorDataStoreDescriptorService real =
        new ExecutorDataStoreDescriptorService(repository, encryption);

    assertThatThrownBy(
            () ->
                new ExecutorDataStoreController(real, "exec-x", "executor-token", false)
                    .describe("Internal executor-token", id, "ro"))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(e.code()).isEqualTo("EXECUTOR_TOKEN_NOT_CONFIGURED");
              assertThat(e.getMessage()).doesNotContain("plain-");
            });
  }

  /** 기본 토큰 + include=slots + Tibero: OWNER 와 같이 503 이고 슬롯 비밀번호도 새지 않는다(복호화 전에 거부). */
  @Test
  void describe_includeSlots_defaultToken_503() {
    long id = newTibero(TenantDataStoreStatus.READY);
    ExecutorDataStoreDescriptorService real =
        new ExecutorDataStoreDescriptorService(repository, encryption);

    for (String include : new String[] {"slots", "ro,slots"}) {
      assertThatThrownBy(
              () ->
                  new ExecutorDataStoreController(real, "exec-x", "executor-token", false)
                      .describe("Internal executor-token", id, include))
          .isInstanceOfSatisfying(
              CodedApiException.class,
              e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(e.code()).isEqualTo("EXECUTOR_TOKEN_NOT_CONFIGURED");
                assertThat(e.getMessage()).doesNotContain("plain-");
              });
    }
  }

  @Test
  void 기본토큰_틀린헤더_401() {
    ExecutorDataStoreDescriptorService service = stubService();

    assertThat(
            new ExecutorDataStoreController(service, "exec-x", "executor-token", false)
                .describe("Internal nope", 1, null)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    verify(service, never()).describe(anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  /** 기본 토큰 + Tibero 테넌트: 실제 서비스·DB 로 503 이고 본문에 비밀번호가 없다. 플래그를 켜면 200. */
  @Test
  void 기본토큰_Tibero_503_본문에_비밀번호없음_플래그면_200() {
    long id = newTibero(TenantDataStoreStatus.READY);
    ExecutorDataStoreDescriptorService real =
        new ExecutorDataStoreDescriptorService(repository, encryption);

    ExecutorDataStoreController refused =
        new ExecutorDataStoreController(real, "exec-x", "executor-token", false);
    assertThatThrownBy(() -> refused.describe("Internal executor-token", id, null))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(e.code()).isEqualTo("EXECUTOR_TOKEN_NOT_CONFIGURED");
              assertThat(e.getMessage()).doesNotContain("plain-");
            });

    ResponseEntity<ExecutorDataStoreDescriptor> allowed =
        new ExecutorDataStoreController(real, "exec-x", "executor-token", true)
            .describe("Internal executor-token", id, null);
    assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(allowed.getBody().owner().password()).isEqualTo("plain-OWNER");
  }

  @Test
  void 커스텀토큰_OK_틀리면_401() {
    ExecutorDataStoreController c =
        new ExecutorDataStoreController(stubService(), "exec-s3cret", "s3cret-x", false);

    assertThat(c.describe("Internal s3cret-x", 1, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(c.describe("Internal nope", 1, null).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(c.describe(null, 1, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void 성공응답_no_store() {
    ResponseEntity<ExecutorDataStoreDescriptor> res =
        new ExecutorDataStoreController(stubService(), "exec-t", "t", false)
            .describe("Internal t", 1, null);

    assertThat(res.getHeaders().getCacheControl()).contains("no-store");
  }
}
