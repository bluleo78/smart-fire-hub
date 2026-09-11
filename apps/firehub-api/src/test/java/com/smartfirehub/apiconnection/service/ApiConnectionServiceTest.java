package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.*;

import com.smartfirehub.apiconnection.dto.ApiConnectionReferencesResponse;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ApiConnectionService 통합 테스트. Phase 9: baseUrl 정규화, SSRF 검증(로컬 테스트 환경에서 localhost는 차단됨),
 * findSelectable slim 목록, testConnection은 Scheduler Task에서 통합 커버.
 */
class ApiConnectionServiceTest extends IntegrationTestBase {

  @Autowired private ApiConnectionService apiConnectionService;

  @Autowired private ApiConnectionRepository apiConnectionRepository;

  @Autowired private DSLContext dsl;

  /** RLS 가 걸린 테이블을 테스트가 직접 만질 때 쓰는 트랜잭션 경계 — GUC 주입의 유일한 통로다. */
  @Autowired private TransactionTemplate tx;

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
    return validReq(baseUrl, "Test Conn");
  }

  /**
   * 이름을 지정할 수 있는 오버로드. (#647) 이름 중복 검증이 추가된 이후로는 같은 테스트 안에서 커넥션을 여러 개
   * 만들 때 이름이 겹치면 안 되므로, 겹칠 가능성이 있는 호출부는 이 오버로드로 서로 다른 이름을 지정한다.
   */
  private CreateApiConnectionRequest validReq(String baseUrl, String name) {
    return new CreateApiConnectionRequest(
        name,
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
    // V96 이후 api_connection 에는 RLS 정책이 걸려 있다 — 트랜잭션 밖에서 지우면 GUC 가 없어
    // 정책이 전 행을 차단하고 0행 삭제로 조용히 끝난 뒤, 이어지는 user 삭제가 FK 로 터진다.
    // 그래서 도메인 정리는 반드시 테넌트 트랜잭션 안에서 한다("user" 는 전역 테이블이라 무관).
    tx.executeWithoutResult(
        s -> dsl.deleteFrom(API_CONNECTION).where(AC_CREATED_BY.eq(testUserId)).execute());
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
   * (#115) healthCheckPath 를 빈 문자열로 update 하면 DB 의 기존 값이 NULL 로 clear 되어야 한다. 기존에는 service 가
   * ""→null 로 정규화하고 repository 가 null 을 "미변경"으로 해석해서 clear 가 불가능했다.
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
    // (#647) 이름 중복 검증이 추가되어 서로 다른 이름을 지정해야 한다.
    apiConnectionService.create(validReq("https://a.example.com", "Slim Test A"), testUserId);
    apiConnectionService.create(validReq("https://b.example.com", "Slim Test B"), testUserId);

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
    assertThat(first.name()).isEqualTo("Slim Test A");
    assertThat(first.authType()).isEqualTo("API_KEY");
    assertThat(first.baseUrl()).isEqualTo("https://a.example.com");
  }

  // ── #605: getReferences (삭제 전 참조 파이프라인 확인) ─────────────────────────

  private static final Table<?> PIPELINE = table(name("pipeline"));
  private static final Table<?> PIPELINE_STEP = table(name("pipeline_step"));

  /**
   * 테스트용 파이프라인 + API_CALL 스텝(주어진 apiConnectionId 참조)을 직접 INSERT 한다. 실제 서비스 경로(PipelineService)를
   * 거치지 않고 최소 컬럼만 채워, getReferences가 보는 FK 관계(pipeline_step.api_connection_id)만 재현한다.
   */
  private Long createPipelineWithApiCallStep(String pipelineName, Long apiConnectionId) {
    // pipeline/pipeline_step 모두 RLS(V96) 대상 — GUC는 트랜잭션 시작 시점에만 주입되므로
    // 별도 트랜잭션 경계(tx.executeWithoutResult) 안에서 INSERT 해야 한다.
    return tx.execute(
        s ->
            dsl.insertInto(PIPELINE)
                .set(field(name("pipeline", "name"), String.class), pipelineName)
                .set(field(name("pipeline", "created_by"), Long.class), testUserId)
                .returning(field(name("pipeline", "id"), Long.class))
                .fetchOne(
                    r -> {
                      Long pipelineId = r.get(field(name("pipeline", "id"), Long.class));
                      dsl.insertInto(PIPELINE_STEP)
                          .set(field(name("pipeline_step", "pipeline_id"), Long.class), pipelineId)
                          .set(field(name("pipeline_step", "name"), String.class), "call-step")
                          .set(field(name("pipeline_step", "script_type"), String.class), "API_CALL")
                          .set(field(name("pipeline_step", "step_order"), Integer.class), 1)
                          .set(
                              field(name("pipeline_step", "api_connection_id"), Long.class),
                              apiConnectionId)
                          .execute();
                      return pipelineId;
                    }));
  }

  private void deletePipeline(Long pipelineId) {
    tx.executeWithoutResult(
        s -> {
          dsl.deleteFrom(PIPELINE_STEP)
              .where(field(name("pipeline_step", "pipeline_id"), Long.class).eq(pipelineId))
              .execute();
          dsl.deleteFrom(PIPELINE)
              .where(field(name("pipeline", "id"), Long.class).eq(pipelineId))
              .execute();
        });
  }

  @Test
  void getReferences_noReferences_returnsEmpty() {
    ApiConnectionResponse created = apiConnectionService.create(validReq("https://noref.example.com"), testUserId);

    ApiConnectionReferencesResponse refs = apiConnectionService.getReferences(created.id());

    assertThat(refs.apiConnectionId()).isEqualTo(created.id());
    assertThat(refs.pipelines()).isEmpty();
    assertThat(refs.totalCount()).isZero();
  }

  @Test
  void getReferences_withReferencingPipeline_returnsPipeline() {
    ApiConnectionResponse created = apiConnectionService.create(validReq("https://ref.example.com"), testUserId);
    Long pipelineId = createPipelineWithApiCallStep("inspector-fk-test-" + System.nanoTime(), created.id());

    try {
      ApiConnectionReferencesResponse refs = apiConnectionService.getReferences(created.id());

      assertThat(refs.totalCount()).isEqualTo(1);
      assertThat(refs.pipelines()).hasSize(1);
      assertThat(refs.pipelines().get(0).id()).isEqualTo(pipelineId);
    } finally {
      deletePipeline(pipelineId);
    }
  }

  @Test
  void getReferences_nonExistentConnection_throwsException() {
    assertThatThrownBy(() -> apiConnectionService.getReferences(-1L))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("not found");
  }

  // ── #647: 이름 중복 검증 ─────────────────────────────────────────────────────

  /** 동일 테넌트 내 같은 이름으로 두 번째 연결을 생성하면 409에 대응하는 예외로 거부되어야 한다. */
  @Test
  void create_duplicateName_throwsApiConnectionNameAlreadyExistsException() {
    String dupName = "Duplicate Name Test " + System.nanoTime();
    apiConnectionService.create(validReq("https://dup-a.example.com", dupName), testUserId);

    assertThatThrownBy(
            () ->
                apiConnectionService.create(
                    validReq("https://dup-b.example.com", dupName), testUserId))
        .isInstanceOf(
            com.smartfirehub.apiconnection.exception.ApiConnectionNameAlreadyExistsException
                .class)
        .hasMessageContaining(dupName);
  }

  /** 서로 다른 이름이면 baseUrl이 같아도 정상 생성되어야 한다 (이름만 검사 대상). */
  @Test
  void create_differentNames_sameBaseUrl_succeeds() {
    String baseUrl = "https://same-base.example.com";
    ApiConnectionResponse first =
        apiConnectionService.create(validReq(baseUrl, "Name A " + System.nanoTime()), testUserId);
    ApiConnectionResponse second =
        apiConnectionService.create(validReq(baseUrl, "Name B " + System.nanoTime()), testUserId);

    assertThat(first.id()).isNotEqualTo(second.id());
  }

  /** update()로 이름을 다른 기존 연결과 같은 이름으로 바꾸려 하면 거부되어야 한다. */
  @Test
  void update_toExistingName_throwsApiConnectionNameAlreadyExistsException() {
    String nameA = "Update Dup A " + System.nanoTime();
    String nameB = "Update Dup B " + System.nanoTime();
    apiConnectionService.create(validReq("https://update-dup-a.example.com", nameA), testUserId);
    ApiConnectionResponse connB =
        apiConnectionService.create(
            validReq("https://update-dup-b.example.com", nameB), testUserId);

    UpdateApiConnectionRequest updateReq =
        new UpdateApiConnectionRequest(nameA, null, null, null, null, null);

    assertThatThrownBy(() -> apiConnectionService.update(connB.id(), updateReq))
        .isInstanceOf(
            com.smartfirehub.apiconnection.exception.ApiConnectionNameAlreadyExistsException
                .class)
        .hasMessageContaining(nameA);
  }

  /** update()로 이름을 바꾸지 않고 다른 필드만 수정하면 자기 자신과의 "중복" 오탐 없이 성공해야 한다. */
  @Test
  void update_sameNameUnchanged_doesNotThrow() {
    String name = "Update Self " + System.nanoTime();
    ApiConnectionResponse created =
        apiConnectionService.create(validReq("https://update-self.example.com", name), testUserId);

    UpdateApiConnectionRequest updateReq =
        new UpdateApiConnectionRequest(name, "새 설명", null, null, null, null);
    ApiConnectionResponse updated = apiConnectionService.update(created.id(), updateReq);

    assertThat(updated.name()).isEqualTo(name);
    assertThat(updated.description()).isEqualTo("새 설명");
  }
}
