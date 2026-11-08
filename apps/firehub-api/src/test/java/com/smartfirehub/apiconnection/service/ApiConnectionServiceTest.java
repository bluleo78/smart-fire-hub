package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.dto.ApiConnectionReferencesResponse;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.ApiConnectionSelectableResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.UpdateApiConnectionRequest;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.apiconnection.exception.ApiConnectionNotFoundException;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

  @Autowired private ObjectMapper objectMapper;

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
   * 이름을 지정할 수 있는 오버로드. (#647) 이름 중복 검증이 추가된 이후로는 같은 테스트 안에서 커넥션을 여러 개 만들 때 이름이 겹치면 안 되므로, 겹칠 가능성이
   * 있는 호출부는 이 오버로드로 서로 다른 이름을 지정한다.
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
        s -> {
          dsl.deleteFrom(API_CONNECTION).where(AC_CREATED_BY.eq(testUserId)).execute();
          // 생성·수정·삭제 감사(WD-181)가 이 사용자를 행위자로 남긴다 — audit_log.user_id 는 "user" FK 라 먼저 지운다(RLS 대상이라
          // 테넌트 트랜잭션 안에서).
          dsl.execute("delete from audit_log where user_id = ?", testUserId);
        });
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

  // WD-588 — 새 연결에 비밀 필드가 없으면 500(null)·비밀 없는 저장({}) 대신 400 으로 거절한다.

  private CreateApiConnectionRequest createRequest(String authType, Map<String, String> config) {
    return new CreateApiConnectionRequest(
        "WD588 " + authType + " " + System.nanoTime(),
        null,
        authType,
        config,
        "https://a.example.com",
        null);
  }

  @Test
  void create_bearerWithNullAuthConfig_rejected400() {
    assertThatThrownBy(() -> apiConnectionService.create(createRequest("BEARER", null), testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessage("Bearer 토큰을 입력하세요 (authConfig.token)");
  }

  @Test
  void create_bearerWithBlankToken_rejected400() {
    assertThatThrownBy(
            () ->
                apiConnectionService.create(
                    createRequest("BEARER", Map.of("token", "  ")), testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessage("Bearer 토큰을 입력하세요 (authConfig.token)");
  }

  @Test
  void create_apiKeyWithEmptyAuthConfig_rejected400() {
    assertThatThrownBy(
            () -> apiConnectionService.create(createRequest("API_KEY", Map.of()), testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessage("API 키를 입력하세요 (authConfig.apiKey)");
  }

  @Test
  void create_apiKeyWithNullAuthConfig_rejected400() {
    assertThatThrownBy(
            () -> apiConnectionService.create(createRequest("API_KEY", null), testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessage("API 키를 입력하세요 (authConfig.apiKey)");
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
    ApiConnectionResponse updated =
        apiConnectionService.update(created.id(), updateReq, testUserId);

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
    apiConnectionService.update(created.id(), updateReq, testUserId);

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
    apiConnectionService.update(created.id(), clearReq, testUserId);

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
    apiConnectionService.update(created.id(), keepReq, testUserId);

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

    apiConnectionService.delete(id, testUserId);

    assertThatThrownBy(() -> apiConnectionService.getById(id))
        .isInstanceOf(ApiConnectionNotFoundException.class)
        .hasMessage("API 연결을 찾을 수 없습니다: " + id);
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
   * 테스트용 파이프라인 + API_CALL 스텝(주어진 apiConnectionId 참조)을 직접 INSERT 한다. 실제 서비스 경로(PipelineService)를 거치지
   * 않고 최소 컬럼만 채워, getReferences가 보는 FK 관계(pipeline_step.api_connection_id)만 재현한다.
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
                          .set(
                              field(name("pipeline_step", "script_type"), String.class), "API_CALL")
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
    ApiConnectionResponse created =
        apiConnectionService.create(validReq("https://noref.example.com"), testUserId);

    ApiConnectionReferencesResponse refs = apiConnectionService.getReferences(created.id());

    assertThat(refs.apiConnectionId()).isEqualTo(created.id());
    assertThat(refs.pipelines()).isEmpty();
    assertThat(refs.totalCount()).isZero();
  }

  @Test
  void getReferences_withReferencingPipeline_returnsPipeline() {
    ApiConnectionResponse created =
        apiConnectionService.create(validReq("https://ref.example.com"), testUserId);
    String pipelineName = "inspector-fk-test-" + System.nanoTime();
    Long pipelineId = createPipelineWithApiCallStep(pipelineName, created.id());

    try {
      ApiConnectionReferencesResponse refs = apiConnectionService.getReferences(created.id());

      assertThat(refs.totalCount()).isEqualTo(1);
      assertThat(refs.pipelines()).hasSize(1);
      assertThat(refs.pipelines().get(0).id()).isEqualTo(pipelineId);
      // 리포지토리가 돌려주는 참조 파이프라인 행(ReferencingPipeline, WD-485)의 이름도 그대로 옮겨진다
      assertThat(refs.pipelines().get(0).name()).isEqualTo(pipelineName);
    } finally {
      deletePipeline(pipelineId);
    }
  }

  @Test
  void getReferences_nonExistentConnection_throwsException() {
    assertThatThrownBy(() -> apiConnectionService.getReferences(-1L))
        .isInstanceOf(ApiConnectionNotFoundException.class)
        .hasMessage("API 연결을 찾을 수 없습니다: -1");
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
            com.smartfirehub.apiconnection.exception.ApiConnectionNameAlreadyExistsException.class)
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

    assertThatThrownBy(() -> apiConnectionService.update(connB.id(), updateReq, testUserId))
        .isInstanceOf(
            com.smartfirehub.apiconnection.exception.ApiConnectionNameAlreadyExistsException.class)
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
    ApiConnectionResponse updated =
        apiConnectionService.update(created.id(), updateReq, testUserId);

    assertThat(updated.name()).isEqualTo(name);
    assertThat(updated.description()).isEqualTo("새 설명");
  }

  // ── WD-179: 가린 인증정보(****…)를 수정·생성 요청에 그대로 실어 보낸 경우 ─────────────────────────

  /**
   * 조회 결과의 maskedAuthConfig 를 그대로 수정 요청에 실어 보내면 저장된 토큰이 바뀌지 않아야 한다(WD-179). 예전에는 {@code ****3456} 이
   * 그대로 암호화 저장돼 이후 호출이 모두 인증 실패했다.
   */
  @Test
  void update_echoedMaskedAuthConfig_keepsStoredSecret() {
    ApiConnectionResponse created =
        apiConnectionService.create(
            new CreateApiConnectionRequest(
                "Masked Echo " + System.nanoTime(),
                null,
                "BEARER",
                Map.of("token", "real-bearer-token-3456"),
                "https://masked-echo.example.com",
                null),
            testUserId);
    Map<String, String> masked = apiConnectionService.getById(created.id()).maskedAuthConfig();
    assertThat(masked.get("token")).isEqualTo("****3456");

    ApiConnectionResponse updated =
        apiConnectionService.update(
            created.id(),
            new UpdateApiConnectionRequest(null, null, "BEARER", masked, null, null),
            testUserId);

    assertThat(apiConnectionService.getDecryptedAuthConfig(created.id()).get("token"))
        .isEqualTo("real-bearer-token-3456");
    assertThat(updated.maskedAuthConfig().get("token")).isEqualTo("****3456");
  }

  /** 저장값의 가린 형태와 다른 가린 값(****0000)은 400(IllegalArgumentException)으로 거절되고 저장값은 그대로여야 한다. */
  @Test
  void update_foreignMaskedValue_rejectedAndStoredSecretUnchanged() {
    ApiConnectionResponse created =
        apiConnectionService.create(
            new CreateApiConnectionRequest(
                "Masked Foreign " + System.nanoTime(),
                null,
                "BEARER",
                Map.of("token", "real-bearer-token-3456"),
                "https://masked-foreign.example.com",
                null),
            testUserId);

    assertThatThrownBy(
            () ->
                apiConnectionService.update(
                    created.id(),
                    new UpdateApiConnectionRequest(
                        null, null, null, Map.of("token", "****0000"), null, null),
                    testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authConfig.token")
        .hasMessageContaining("가려진 값")
        // 거절 문구에 저장된 원문(이나 그 끝 4자)이 실리면 안 된다
        .satisfies(
            e ->
                assertThat(e.getMessage())
                    .doesNotContain("real-bearer-token")
                    .doesNotContain("3456"));

    assertThat(apiConnectionService.getDecryptedAuthConfig(created.id()).get("token"))
        .isEqualTo("real-bearer-token-3456");
  }

  /** 가린 apiKey 는 그대로 둔 채 비밀이 아닌 필드(paramName)와 이름만 바꾸면 — 비밀은 유지되고 다른 필드는 반영돼야 한다. */
  @Test
  void update_maskedSecretWithOtherFieldChanges_keepsSecretAndAppliesOthers() {
    ApiConnectionResponse created =
        apiConnectionService.create(
            new CreateApiConnectionRequest(
                "Masked Query " + System.nanoTime(),
                null,
                "API_KEY",
                Map.of("placement", "query", "paramName", "serviceKey", "apiKey", "query-key-9876"),
                "https://masked-query.example.com",
                null),
            testUserId);
    Map<String, String> edited =
        new java.util.HashMap<>(apiConnectionService.getById(created.id()).maskedAuthConfig());
    edited.put("paramName", "authKey");
    String newName = "Masked Query Renamed " + System.nanoTime();

    ApiConnectionResponse updated =
        apiConnectionService.update(
            created.id(),
            new UpdateApiConnectionRequest(newName, null, null, edited, null, null),
            testUserId);

    Map<String, String> decrypted = apiConnectionService.getDecryptedAuthConfig(created.id());
    assertThat(decrypted.get("apiKey")).isEqualTo("query-key-9876");
    assertThat(decrypted.get("paramName")).isEqualTo("authKey");
    assertThat(decrypted.get("placement")).isEqualTo("query");
    assertThat(updated.name()).isEqualTo(newName);
  }

  /**
   * authType 이 바뀌면 같은 이름의 필드라도 저장값을 쓰지 않는다 — 끝 4자가 맞는 가린 값이라도 거절된다. 가린 값은 새 방식의 비밀로 쓸 수 없으므로 일반 가린
   * 값 문구보다 WD-180 문구(새 방식의 인증 정보를 입력하라)가 먼저다(WD-623).
   */
  @Test
  void update_maskedValueWithChangedAuthType_rejected() {
    ApiConnectionResponse created =
        apiConnectionService.create(
            new CreateApiConnectionRequest(
                "Masked AuthType " + System.nanoTime(),
                null,
                "API_KEY",
                Map.of("headerName", "X-Key", "apiKey", "token-like-key-1111", "token", "t-1111"),
                "https://masked-authtype.example.com",
                null),
            testUserId);

    assertThatThrownBy(
            () ->
                apiConnectionService.update(
                    created.id(),
                    new UpdateApiConnectionRequest(
                        null, null, "BEARER", Map.of("token", "****1111"), null, null),
                    testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("인증 방식을 BEARER(으)로 바꾸려면 새 방식의 인증 정보(authConfig.token)를 함께 입력하세요");

    assertThat(apiConnectionService.getById(created.id()).authType()).isEqualTo("API_KEY");
  }

  /** 새 실제 값을 보내면 정상 교체된다(회귀) — 가린 값 처리가 일반 교체를 막지 않는다. */
  @Test
  void update_newRealSecret_replacesStoredSecret() {
    ApiConnectionResponse created =
        apiConnectionService.create(
            new CreateApiConnectionRequest(
                "Masked Replace " + System.nanoTime(),
                null,
                "BEARER",
                Map.of("token", "old-bearer-token-3456"),
                "https://masked-replace.example.com",
                null),
            testUserId);

    apiConnectionService.update(
        created.id(),
        new UpdateApiConnectionRequest(
            null, null, "BEARER", Map.of("token", "new-bearer-token-7777"), null, null),
        testUserId);

    assertThat(apiConnectionService.getDecryptedAuthConfig(created.id()).get("token"))
        .isEqualTo("new-bearer-token-7777");
  }

  /** 생성은 저장값이 없으니 가린 값을 유지할 것도 없다 — 400(IllegalArgumentException)으로 거절되고 행이 생기지 않아야 한다. */
  @Test
  void create_maskedSecret_rejected() {
    String name = "Masked Create " + System.nanoTime();
    assertThatThrownBy(
            () ->
                apiConnectionService.create(
                    new CreateApiConnectionRequest(
                        name,
                        null,
                        "API_KEY",
                        Map.of("headerName", "X-Key", "apiKey", "****1234"),
                        "https://masked-create.example.com",
                        null),
                    testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authConfig.apiKey")
        .hasMessageContaining("가려진 값");

    assertThat(apiConnectionService.getAll()).noneMatch(c -> name.equals(c.name()));
  }

  /** 저장 전 테스트도 가린 값을 인증 값으로 보내지 않는다 — 외부 호출 전에 400 으로 거절된다. */
  @Test
  void testConnectionPayload_maskedSecret_rejected() {
    assertThatThrownBy(
            () ->
                apiConnectionService.testConnectionPayload(
                    new CreateApiConnectionRequest(
                        "Masked Test",
                        null,
                        "BEARER",
                        Map.of("token", "****1234"),
                        "https://masked-test.example.com",
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authConfig.token");
  }

  // ── WD-180: 인증 방식 변경 검증 ─────────────────────────────────────────────

  /** 저장된 인증 방식·비밀이 그대로인지 확인한다 — 거절된 수정이 아무것도 바꾸지 않았음을 DB 에서 다시 읽어 증명한다. */
  private void assertAuthUnchanged(Long id, String authType, String secretKey, String secret) {
    assertThat(apiConnectionService.getById(id).authType()).isEqualTo(authType);
    assertThat(apiConnectionService.getDecryptedAuthConfig(id).get(secretKey)).isEqualTo(secret);
  }

  /**
   * authConfig 없이 authType 만 BEARER 로 바꾸면 400 으로 거절된다. 예전에는 검증 없이 저장돼 토큰 없는 BEARER 가 되었고 인증 헤더 없이
   * 호출되면서도 오류가 없었다.
   */
  @Test
  void update_authTypeOnlyToBearer_withoutAuthConfig_rejectedAndUnchanged() {
    ApiConnectionResponse created =
        apiConnectionService.create(validReq("https://wd180-only.example.com"), testUserId);

    assertThatThrownBy(
            () ->
                apiConnectionService.update(
                    created.id(),
                    new UpdateApiConnectionRequest(null, null, "BEARER", null, null, null),
                    testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("인증 방식을 BEARER(으)로 바꾸려면 새 방식의 인증 정보(authConfig.token)를 함께 입력하세요");

    assertAuthUnchanged(created.id(), "API_KEY", "apiKey", "secret");
  }

  /**
   * 방식을 바꾸면서 새 방식의 비밀 필드가 없는 authConfig({@code {}}·옛 방식 필드만)도 같은 이유로 거절한다 — null 만 막으면 신고된 「인증 헤더
   * 없이 호출」이 그대로 재현된다.
   */
  @Test
  void update_authTypeChangedWithoutNewSecretField_rejectedAndUnchanged() {
    ApiConnectionResponse created =
        apiConnectionService.create(validReq("https://wd180-empty.example.com"), testUserId);

    for (Map<String, String> config :
        List.of(Map.<String, String>of(), Map.of("apiKey", "new-key"), Map.of("token", "  "))) {
      assertThatThrownBy(
              () ->
                  apiConnectionService.update(
                      created.id(),
                      new UpdateApiConnectionRequest(null, null, "BEARER", config, null, null),
                      testUserId))
          .as("authConfig=%s", config)
          .isExactlyInstanceOf(ApiConnectionException.class)
          .hasMessageContaining("authConfig.token");
    }

    assertAuthUnchanged(created.id(), "API_KEY", "apiKey", "secret");
  }

  /**
   * 지원 목록 밖의 authType 은 authConfig 가 없어도 수정에서 400 한국어로 거절된다. 예전에는 OAUTH2 가 200 으로 저장됐고 FOO 는 DB
   * CHECK 제약에 걸려 409 일반 문구가 됐다.
   */
  @ParameterizedTest
  @ValueSource(strings = {"OAUTH2", "BASIC", "FOO"})
  void update_unsupportedAuthType_rejectedInKoreanAndUnchanged(String authType) {
    ApiConnectionResponse created =
        apiConnectionService.create(
            validReq("https://wd180-upd-" + authType.toLowerCase() + ".example.com"), testUserId);

    assertThatThrownBy(
            () ->
                apiConnectionService.update(
                    created.id(),
                    new UpdateApiConnectionRequest(null, null, authType, null, null, null),
                    testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("지원하지 않는 인증 방식입니다: " + authType + " (사용 가능: API_KEY, BEARER)");

    assertAuthUnchanged(created.id(), "API_KEY", "apiKey", "secret");
  }

  /** 생성도 지원 목록 밖의 authType 을 400 한국어로 거절하고 행을 만들지 않는다. */
  @ParameterizedTest
  @ValueSource(strings = {"OAUTH2", "BASIC", "FOO"})
  void create_unsupportedAuthType_rejectedInKorean(String authType) {
    String name = "WD180 create " + authType + " " + System.nanoTime();
    assertThatThrownBy(
            () ->
                apiConnectionService.create(
                    new CreateApiConnectionRequest(
                        name,
                        null,
                        authType,
                        Map.of("token", "tok"),
                        "https://wd180-create.example.com",
                        null),
                    testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("지원하지 않는 인증 방식입니다: " + authType + " (사용 가능: API_KEY, BEARER)");

    assertThat(apiConnectionService.getAll()).noneMatch(c -> name.equals(c.name()));
  }

  /** 새 방식에 맞는 authConfig 를 함께 보내면 방식이 정상으로 바뀐다(회귀) — 옛 방식의 비밀은 남지 않는다. */
  @Test
  void update_authTypeChangedWithMatchingAuthConfig_succeeds() {
    ApiConnectionResponse created =
        apiConnectionService.create(validReq("https://wd180-ok.example.com"), testUserId);

    ApiConnectionResponse updated =
        apiConnectionService.update(
            created.id(),
            new UpdateApiConnectionRequest(
                null, null, "BEARER", Map.of("token", "wd180-new-token"), null, null),
            testUserId);

    assertThat(updated.authType()).isEqualTo("BEARER");
    Map<String, String> decrypted = apiConnectionService.getDecryptedAuthConfig(created.id());
    assertThat(decrypted.get("token")).isEqualTo("wd180-new-token");
    assertThat(decrypted).doesNotContainKey("apiKey");
  }

  /** authType 이 그대로이고 authConfig 가 없으면 저장된 비밀을 유지한다(현행 유지 회귀). */
  @Test
  void update_sameAuthTypeWithoutAuthConfig_keepsStoredSecret() {
    ApiConnectionResponse created =
        apiConnectionService.create(validReq("https://wd180-same.example.com"), testUserId);
    String newName = "WD180 renamed " + System.nanoTime();

    ApiConnectionResponse updated =
        apiConnectionService.update(
            created.id(),
            new UpdateApiConnectionRequest(newName, null, "API_KEY", null, null, null),
            testUserId);

    assertThat(updated.name()).isEqualTo(newName);
    assertAuthUnchanged(created.id(), "API_KEY", "apiKey", "secret");
  }

  // ── WD-623: 비밀 필드가 비면(정규화 뒤 포함) 수정·생성·저장 전 테스트 모두 400 ──────────────────────

  /** BEARER 연결을 하나 만든다 — 저장 토큰은 {@code wd623-stored-token}. */
  private Long createBearer(String label) {
    return apiConnectionService
        .create(
            new CreateApiConnectionRequest(
                "WD623 " + label + " " + System.nanoTime(),
                null,
                "BEARER",
                Map.of("token", "wd623-stored-token"),
                "https://wd623.example.com",
                null),
            testUserId)
        .id();
  }

  /**
   * 인증 방식을 그대로 둔 수정에서 authConfig 의 토큰이 비거나({@code {}}·빈 문자열) 접두어만 있으면(「Bearer 」) 400 으로 거절하고 저장된
   * 토큰을 그대로 둔다. 예전에는 방식이 바뀔 때만 비밀 필드를 봐서 200 으로 저장된 토큰을 빈 값(또는 「Bearer」)으로 덮었다.
   */
  @Test
  void update_sameAuthTypeWithBlankToken_rejectedAndStoredTokenUnchanged() {
    Long id = createBearer("blank-token");

    for (Map<String, String> config :
        List.of(
            Map.<String, String>of(),
            Map.of("token", ""),
            Map.of("token", "   "),
            Map.of("token", "Bearer   "),
            Map.of("token", "bearer"))) {
      for (String authType : Arrays.asList("BEARER", null)) {
        assertThatThrownBy(
                () ->
                    apiConnectionService.update(
                        id,
                        new UpdateApiConnectionRequest(null, null, authType, config, null, null),
                        testUserId))
            .as("authType=%s authConfig=%s", authType, config)
            .isExactlyInstanceOf(ApiConnectionException.class)
            .hasMessage("Bearer 토큰을 입력하세요 (authConfig.token)");
      }
    }

    assertAuthUnchanged(id, "BEARER", "token", "wd623-stored-token");
  }

  /** API_KEY 방식 그대로 키 값을 공백·빈 값으로 보내도 400 이고 저장된 키는 그대로다. */
  @Test
  void update_sameAuthTypeWithBlankApiKey_rejectedAndStoredKeyUnchanged() {
    ApiConnectionResponse created =
        apiConnectionService.create(validReq("https://wd623-key.example.com"), testUserId);

    for (Map<String, String> config :
        List.of(
            Map.of("placement", "header", "headerName", "X-Key", "apiKey", "  "),
            Map.of("placement", "header", "headerName", "X-Key"))) {
      assertThatThrownBy(
              () ->
                  apiConnectionService.update(
                      created.id(),
                      new UpdateApiConnectionRequest(null, null, "API_KEY", config, null, null),
                      testUserId))
          .as("authConfig=%s", config)
          .isExactlyInstanceOf(ApiConnectionException.class)
          .hasMessage("API 키를 입력하세요 (authConfig.apiKey)");
    }

    assertAuthUnchanged(created.id(), "API_KEY", "apiKey", "secret");
  }

  /** 비밀 유지 경로는 막히지 않는다(회귀) — authConfig 미전송·가린 값 그대로는 유지, 새 토큰은 교체(접두어는 뗀다). */
  @Test
  void update_secretPreservingPaths_stillAllowed() {
    Long id = createBearer("preserve");
    Map<String, String> masked = apiConnectionService.getById(id).maskedAuthConfig();

    apiConnectionService.update(
        id,
        new UpdateApiConnectionRequest(
            "WD623 renamed " + System.nanoTime(), null, null, null, null, null),
        testUserId);
    assertAuthUnchanged(id, "BEARER", "token", "wd623-stored-token");

    apiConnectionService.update(
        id, new UpdateApiConnectionRequest(null, null, "BEARER", masked, null, null), testUserId);
    assertAuthUnchanged(id, "BEARER", "token", "wd623-stored-token");

    apiConnectionService.update(
        id,
        new UpdateApiConnectionRequest(
            null, null, null, Map.of("token", "Bearer wd623-new-token"), null, null),
        testUserId);
    assertAuthUnchanged(id, "BEARER", "token", "wd623-new-token");
  }

  /** 인증 방식을 바꾸면서 새 방식 토큰이 접두어뿐이면 정규화 뒤 빈 값이라 WD-180 문구로 거절한다(예전에는 「Bearer」 로 저장됐다). */
  @Test
  void update_authTypeChangedWithPrefixOnlyToken_rejectedWithWd180Message() {
    ApiConnectionResponse created =
        apiConnectionService.create(validReq("https://wd623-change.example.com"), testUserId);

    assertThatThrownBy(
            () ->
                apiConnectionService.update(
                    created.id(),
                    new UpdateApiConnectionRequest(
                        null, null, "BEARER", Map.of("token", "Bearer   "), null, null),
                    testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("인증 방식을 BEARER(으)로 바꾸려면 새 방식의 인증 정보(authConfig.token)를 함께 입력하세요");

    assertAuthUnchanged(created.id(), "API_KEY", "apiKey", "secret");
  }

  /** 생성도 정규화 뒤 값으로 본다 — 접두어만 있는 토큰은 400 이고 행이 생기지 않는다(WD-588 검사의 구멍). */
  @ParameterizedTest
  @ValueSource(strings = {"Bearer   ", "bearer", "BEARER"})
  void create_prefixOnlyToken_rejected(String token) {
    CreateApiConnectionRequest req = createRequest("BEARER", Map.of("token", token));

    assertThatThrownBy(() -> apiConnectionService.create(req, testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("Bearer 토큰을 입력하세요 (authConfig.token)");

    assertThat(apiConnectionService.getAll()).noneMatch(c -> req.name().equals(c.name()));
  }

  /** 접두어 + 토큰은 접두어만 떼고, 「bearer」 로 시작하는 단어 토큰은 그대로 저장한다(회귀). */
  @Test
  void create_bearerPrefixStrippedButBearerLikeWordKept() {
    ApiConnectionResponse prefixed =
        apiConnectionService.create(
            createRequest("BEARER", Map.of("token", "Bearer abc")), testUserId);
    ApiConnectionResponse word =
        apiConnectionService.create(
            createRequest("BEARER", Map.of("token", "bearerXYZ")), testUserId);

    assertThat(apiConnectionService.getDecryptedAuthConfig(prefixed.id()))
        .containsEntry("token", "abc");
    assertThat(apiConnectionService.getDecryptedAuthConfig(word.id()))
        .containsEntry("token", "bearerXYZ");
  }

  /**
   * 저장 전 테스트도 비밀 필드가 비면 대상 서버를 부르지 않고 400 으로 안내한다. 예전에는 빈 인증으로 호출해 대상 서버의 401/403 만 보였다. 주소
   * 검사(SSRF) 보다 앞에서 거절되므로 그 검사도 불리지 않는다 — 호출 전 단계라는 증거.
   */
  @Test
  void testConnectionPayload_blankSecret_rejectedWithoutCall() {
    List<Map<String, String>> configs = new ArrayList<>();
    configs.add(null);
    configs.add(Map.of());
    configs.add(Map.of("token", ""));
    configs.add(Map.of("token", "Bearer   "));

    for (Map<String, String> config : configs) {
      assertThatThrownBy(
              () ->
                  apiConnectionService.testConnectionPayload(
                      new CreateApiConnectionRequest(
                          "WD623 test",
                          null,
                          "BEARER",
                          config,
                          "https://wd623-test.example.com",
                          null)))
          .as("authConfig=%s", config)
          .isExactlyInstanceOf(ApiConnectionException.class)
          .hasMessage("Bearer 토큰을 입력하세요 (authConfig.token)");
    }
    assertThatThrownBy(
            () ->
                apiConnectionService.testConnectionPayload(
                    new CreateApiConnectionRequest(
                        "WD623 test key",
                        null,
                        "API_KEY",
                        Map.of("placement", "query", "paramName", "serviceKey"),
                        "https://wd623-test.example.com",
                        null)))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("API 키를 입력하세요 (authConfig.apiKey)");

    verify(ssrfProtectionService, never()).validateUrl(anyString());
  }

  // ── WD-623 코드리뷰: 오류 우선순위·저장된 빈 비밀·입력 검증 순서 ──────────────────────

  /** 검사 없이(예전 버전에서) 저장된 연결을 흉내 낸다 — 서비스 검사를 거치지 않고 암호화한 authConfig 를 바로 저장한다. */
  private Long saveLegacy(String authType, Map<String, String> authConfig) throws Exception {
    String encrypted = encryptionService.encrypt(objectMapper.writeValueAsString(authConfig));
    return tx.execute(
        s ->
            apiConnectionRepository.save(
                "WD623 legacy " + System.nanoTime(),
                null,
                authType,
                encrypted,
                testUserId,
                "https://wd623-legacy.example.com",
                null));
  }

  /**
   * WD-647 — 복호화 authConfig 의 authType 은 연결의 auth_type 컬럼이 이긴다. 예전에는 putIfAbsent 라 JSON 안의 다른
   * authType 키가 남아, 빈 비밀 검사(컬럼 기준)와 실제 호출(JSON 기준)이 다른 방식으로 판정했다.
   */
  @Test
  void getDecryptedAuthConfig_columnAuthTypeOverridesJsonAuthType() throws Exception {
    Long id = saveLegacy("BEARER", Map.of("authType", "API_KEY", "token", "t647"));

    assertThat(apiConnectionService.getDecryptedAuthConfig(id))
        .containsEntry("authType", "BEARER")
        .containsEntry("token", "t647");
  }

  /** 비밀이 빠졌으면 헤더 이름·파라미터 이름 등 다른 필드 오류보다 「… 입력하세요」 가 먼저다 — 생성·수정·저장 전 테스트 모두. */
  @Test
  void missingSecret_takesPrecedenceOverOtherFieldErrors() {
    Map<String, String> badHeaderNoKey = Map.of("placement", "header", "headerName", "X API Key");
    Map<String, String> blankParamNoKey = Map.of("placement", "query", "paramName", "");

    for (Map<String, String> config : List.of(badHeaderNoKey, blankParamNoKey)) {
      assertThatThrownBy(
              () -> apiConnectionService.create(createRequest("API_KEY", config), testUserId))
          .as("create %s", config)
          .isExactlyInstanceOf(ApiConnectionException.class)
          .hasMessage("API 키를 입력하세요 (authConfig.apiKey)");
      assertThatThrownBy(
              () -> apiConnectionService.testConnectionPayload(createRequest("API_KEY", config)))
          .as("test %s", config)
          .isExactlyInstanceOf(ApiConnectionException.class)
          .hasMessage("API 키를 입력하세요 (authConfig.apiKey)");
    }

    ApiConnectionResponse created =
        apiConnectionService.create(validReq("https://wd623-prec.example.com"), testUserId);
    assertThatThrownBy(
            () ->
                apiConnectionService.update(
                    created.id(),
                    new UpdateApiConnectionRequest(null, null, null, badHeaderNoKey, null, null),
                    testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("API 키를 입력하세요 (authConfig.apiKey)");
    // 방식 변경이면 WD-180 문구가 헤더 이름 오류보다 먼저다
    Long bearerId = createBearer("prec-change");
    assertThatThrownBy(
            () ->
                apiConnectionService.update(
                    bearerId,
                    new UpdateApiConnectionRequest(
                        null, null, "API_KEY", badHeaderNoKey, null, null),
                    testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("인증 방식을 API_KEY(으)로 바꾸려면 새 방식의 인증 정보(authConfig.apiKey)를 함께 입력하세요");
    assertAuthUnchanged(created.id(), "API_KEY", "apiKey", "secret");
    assertAuthUnchanged(bearerId, "BEARER", "token", "wd623-stored-token");
  }

  /**
   * 빈 비밀로 저장된 옛 연결은 연결 테스트(·주기 점검)에서 대상 서버를 부르지 않고 무엇을 채워야 하는지 보인다. 예전에는 빈 인증으로 호출해 대상의 401/403 만
   * 보였다. 주소 검사(SSRF)도 불리지 않는다 — 호출 전 단계라는 증거.
   */
  @Test
  void testConnection_storedEmptySecret_reportsMissingSecretWithoutCall() throws Exception {
    Long bearerId = saveLegacy("BEARER", Map.of("token", ""));
    Long prefixOnlyId = saveLegacy("BEARER", Map.of("token", "Bearer"));
    Long keyId = saveLegacy("API_KEY", Map.of("placement", "header", "headerName", "X-Key"));

    var bearer = apiConnectionService.testConnection(bearerId);
    var prefixOnly = apiConnectionService.testConnection(prefixOnlyId);
    var key = apiConnectionService.testConnection(keyId);

    assertThat(bearer.ok()).isFalse();
    assertThat(bearer.errorMessage()).isEqualTo("Bearer 토큰을 입력하세요 (authConfig.token)");
    assertThat(prefixOnly.errorMessage()).isEqualTo("Bearer 토큰을 입력하세요 (authConfig.token)");
    assertThat(key.ok()).isFalse();
    assertThat(key.errorMessage()).isEqualTo("API 키를 입력하세요 (authConfig.apiKey)");
    // 결과는 상태(DOWN)·사유로 저장된다 — 목록 화면이 같은 사유를 보인다
    assertThat(apiConnectionService.getById(bearerId).lastStatus()).isEqualTo("DOWN");
    verify(ssrfProtectionService, never()).validateUrl(anyString());
  }

  /**
   * 빈 비밀로 저장된 옛 연결을 같은 방식으로 수정하면서 조회한 가린 값({@code ****})을 그대로 보내면, 일반 문구 대신 저장된 비밀이 비었다고 짚어 400 이다.
   */
  @Test
  void update_maskedEchoOfEmptyStoredSecret_rejectedWithSpecificMessage() throws Exception {
    Long bearerId = saveLegacy("BEARER", Map.of("token", ""));
    Map<String, String> masked = apiConnectionService.getById(bearerId).maskedAuthConfig();
    assertThat(masked).containsEntry("token", "****");

    for (String authType : Arrays.asList("BEARER", null)) {
      assertThatThrownBy(
              () ->
                  apiConnectionService.update(
                      bearerId,
                      new UpdateApiConnectionRequest(null, null, authType, masked, null, null),
                      testUserId))
          .as("authType=%s", authType)
          .isExactlyInstanceOf(ApiConnectionException.class)
          .hasMessage("저장된 Bearer 토큰이 비어 있습니다 — 새 토큰을 입력하세요 (authConfig.token)");
    }

    Long keyId =
        saveLegacy("API_KEY", Map.of("placement", "header", "headerName", "X-Key", "apiKey", ""));
    Map<String, String> maskedKey = apiConnectionService.getById(keyId).maskedAuthConfig();
    assertThatThrownBy(
            () ->
                apiConnectionService.update(
                    keyId,
                    new UpdateApiConnectionRequest(null, null, null, maskedKey, null, null),
                    testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("저장된 API 키가 비어 있습니다 — 새 키를 입력하세요 (authConfig.apiKey)");

    // 새 토큰을 넣으면 고쳐진다(회귀)
    apiConnectionService.update(
        bearerId,
        new UpdateApiConnectionRequest(null, null, null, Map.of("token", "fresh"), null, null),
        testUserId);
    assertAuthUnchanged(bearerId, "BEARER", "token", "fresh");
  }

  /** 입력 자체가 틀린 요청(기준 URL 형식)은 이름 중복(409)보다 먼저 400 이다 — 생성·수정. */
  @Test
  void invalidBaseUrl_takesPrecedenceOverDuplicateName() {
    String taken = "WD623 taken " + System.nanoTime();
    apiConnectionService.create(validReq("https://wd623-dup.example.com", taken), testUserId);
    ApiConnectionResponse other =
        apiConnectionService.create(
            validReq("https://wd623-dup2.example.com", taken + " other"), testUserId);

    assertThatThrownBy(
            () ->
                apiConnectionService.create(validReq("ftp://wd623.example.com", taken), testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("baseUrl은 http 또는 https 스킴이어야 합니다");
    assertThatThrownBy(
            () ->
                apiConnectionService.update(
                    other.id(),
                    new UpdateApiConnectionRequest(
                        taken, null, null, null, "ftp://wd623.example.com", null),
                    testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("baseUrl은 http 또는 https 스킴이어야 합니다");
  }

  // ── 생성·수정·삭제 감사 로그(WD-181) ───────────────────────────────────────────

  /** 이 연결의 api_connection 감사 행(id 순). audit_log 는 RLS 대상이라 테넌트 트랜잭션 안에서 읽는다(서비스 호출은 이미 커밋됨). */
  private List<Record> connectionAudits(String action, Long connectionId) {
    return tx.execute(
        s ->
            dsl.fetch(
                "select user_id, username, tenant_id, description, result, metadata::text as metadata"
                    + " from audit_log where resource = 'api_connection' and action_type = ?"
                    + " and resource_id = ? order by id",
                action,
                String.valueOf(connectionId)));
  }

  /** 이 연결의 모든 감사 행 본문(description + metadata)을 한 문자열로 — 비밀이 어디에도 없는지 한 번에 본다. */
  private String allAuditText(Long connectionId) {
    return tx.execute(
        s ->
            String.join(
                "\n",
                dsl.fetch(
                        "select coalesce(description, '') || ' ' || coalesce(metadata::text, '')"
                            + " || ' ' || coalesce(error_message, '') as t from audit_log"
                            + " where resource = 'api_connection' and resource_id = ?",
                        String.valueOf(connectionId))
                    .getValues("t", String.class)));
  }

  /** 행위자·테넌트·결과 공통 단언 — 행위자는 서비스에 넘긴 사용자, 테넌트는 호출 시점 워크스페이스(기본 테스트 테넌트)다. */
  private void assertAuditActor(Record row) {
    String username =
        dsl.select(U_USERNAME).from(USER_TABLE).where(U_ID.eq(testUserId)).fetchOne(U_USERNAME);
    assertThat(row.get("user_id", Long.class)).isEqualTo(testUserId);
    assertThat(row.get("username", String.class)).isEqualTo(username);
    assertThat(row.get("tenant_id", Long.class)).isEqualTo(DEFAULT_TEST_TENANT_ID);
    assertThat(row.get("result", String.class)).isEqualTo("SUCCESS");
  }

  private JsonNode auditMetadata(Record row) throws Exception {
    return objectMapper.readTree(row.get("metadata", String.class));
  }

  private static List<String> jsonTexts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }

  /** 생성 감사 — 이름·인증 방식·기준 URL(쿼리 제거)만 남고 비밀은 어디에도 없다. */
  @Test
  void create_writesCreateAudit_withoutSecrets() throws Exception {
    String secret = "wd181-create-secret-key-4821";
    ApiConnectionResponse created =
        apiConnectionService.create(
            new CreateApiConnectionRequest(
                "WD181 Create",
                null,
                "API_KEY",
                Map.of("placement", "header", "headerName", "X-Key", "apiKey", secret),
                "https://wd181-create.example.com/v1?apikey=wd181-url-secret",
                null),
            testUserId);

    List<Record> rows = connectionAudits("CREATE", created.id());
    assertThat(rows).hasSize(1);
    assertAuditActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class)).isEqualTo("API 연결 생성: WD181 Create");
    JsonNode meta = auditMetadata(rows.get(0));
    assertThat(meta.get("name").asText()).isEqualTo("WD181 Create");
    assertThat(meta.get("authType").asText()).isEqualTo("API_KEY");
    assertThat(meta.get("baseUrl").asText()).isEqualTo("https://wd181-create.example.com/v1");

    String text = allAuditText(created.id());
    assertThat(text)
        .doesNotContain(secret)
        .doesNotContain("wd181-url-secret")
        .doesNotContain("****");
  }

  /** 인증 정보를 바꾼 수정 — 바뀐 항목·credentialsChanged=true 가 남고, 옛 비밀·새 비밀은 남지 않는다. */
  @Test
  void update_credentialsChanged_writesUpdateAuditWithFlagTrue() throws Exception {
    String oldSecret = "wd181-old-bearer-token-1357";
    String newSecret = "wd181-new-bearer-token-2468";
    ApiConnectionResponse created =
        apiConnectionService.create(
            new CreateApiConnectionRequest(
                "WD181 Cred",
                null,
                "BEARER",
                Map.of("token", oldSecret),
                "https://wd181-cred.example.com",
                null),
            testUserId);

    apiConnectionService.update(
        created.id(),
        new UpdateApiConnectionRequest(
            "WD181 Cred Renamed",
            null,
            null,
            Map.of("token", newSecret),
            "https://wd181-cred-new.example.com",
            null),
        testUserId);

    List<Record> rows = connectionAudits("UPDATE", created.id());
    assertThat(rows).hasSize(1);
    assertAuditActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class))
        .isEqualTo("API 연결 수정: WD181 Cred Renamed (바뀐 항목: 이름·기준 URL·인증 정보)");
    JsonNode meta = auditMetadata(rows.get(0));
    assertThat(jsonTexts(meta.get("changedFields")))
        .containsExactly("name", "baseUrl", "authConfig");
    assertThat(meta.get("credentialsChanged").asBoolean()).isTrue();
    assertThat(meta.get("before").get("name").asText()).isEqualTo("WD181 Cred");
    assertThat(meta.get("after").get("name").asText()).isEqualTo("WD181 Cred Renamed");
    assertThat(meta.get("after").get("baseUrl").asText())
        .isEqualTo("https://wd181-cred-new.example.com");

    String text = allAuditText(created.id());
    assertThat(text).doesNotContain(oldSecret).doesNotContain(newSecret).doesNotContain("****");
  }

  /**
   * 조회한 가린 값(****…)을 그대로 되돌려 보낸 수정(WD-179 — 비밀 유지)은 인증 정보 변경이 아니다. credentialsChanged=false 이고, 입력에
   * 실제로 있던 가린 값·저장 비밀·헬스체크 경로 쿼리의 값은 감사 어디에도 없다.
   */
  @Test
  void update_maskedSecretResent_writesUpdateAuditWithFlagFalse() throws Exception {
    String secret = "wd181-keep-secret-key-9753";
    ApiConnectionResponse created =
        apiConnectionService.create(
            new CreateApiConnectionRequest(
                "WD181 Keep",
                null,
                "API_KEY",
                Map.of("placement", "header", "headerName", "X-Key", "apiKey", secret),
                "https://wd181-keep.example.com",
                null),
            testUserId);
    Map<String, String> masked = apiConnectionService.getById(created.id()).maskedAuthConfig();
    assertThat(masked.get("apiKey")).as("입력에 가린 값이 실제로 실린다").startsWith("****");

    apiConnectionService.update(
        created.id(),
        new UpdateApiConnectionRequest(
            "WD181 Keep Renamed",
            null,
            "API_KEY",
            masked,
            null,
            "/status?apikey=wd181-path-secret"),
        testUserId);

    // 비밀은 그대로 유지됐다(WD-179) — 그래서 「인증 정보 변경」이 아니다.
    assertThat(apiConnectionService.getDecryptedAuthConfig(created.id()).get("apiKey"))
        .isEqualTo(secret);
    List<Record> rows = connectionAudits("UPDATE", created.id());
    assertThat(rows).hasSize(1);
    assertAuditActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class))
        .isEqualTo("API 연결 수정: WD181 Keep Renamed (바뀐 항목: 이름·헬스체크 경로)");
    JsonNode meta = auditMetadata(rows.get(0));
    assertThat(jsonTexts(meta.get("changedFields"))).containsExactly("name", "healthCheckPath");
    assertThat(meta.get("credentialsChanged").asBoolean()).isFalse();
    assertThat(meta.get("after").get("healthCheckPath").asText()).isEqualTo("/status");

    String text = allAuditText(created.id());
    assertThat(text)
        .doesNotContain(secret)
        .doesNotContain(masked.get("apiKey"))
        .doesNotContain("****")
        .doesNotContain("wd181-path-secret");
  }

  /** 바뀐 것이 없는 저장(같은 값 재전송)은 감사를 남기지 않는다 — ROLE_UPDATE 와 같은 규칙. */
  @Test
  void update_noActualChange_writesNoUpdateAudit() {
    ApiConnectionResponse created =
        apiConnectionService.create(
            validReq("https://wd181-same.example.com", "WD181 Same"), testUserId);
    Map<String, String> masked = apiConnectionService.getById(created.id()).maskedAuthConfig();

    apiConnectionService.update(
        created.id(),
        new UpdateApiConnectionRequest(
            "WD181 Same", null, "API_KEY", masked, "https://wd181-same.example.com", null),
        testUserId);

    assertThat(connectionAudits("UPDATE", created.id())).isEmpty();
  }

  /** 삭제 감사 — 지우기 전에 담아 둔 이름·인증 방식이 남는다. */
  @Test
  void delete_writesDeleteAudit() throws Exception {
    String secret = "wd181-delete-secret-key-8642";
    ApiConnectionResponse created =
        apiConnectionService.create(
            new CreateApiConnectionRequest(
                "WD181 Delete",
                null,
                "BEARER",
                Map.of("token", secret),
                "https://wd181-delete.example.com",
                null),
            testUserId);

    apiConnectionService.delete(created.id(), testUserId);

    List<Record> rows = connectionAudits("DELETE", created.id());
    assertThat(rows).hasSize(1);
    assertAuditActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class)).isEqualTo("API 연결 삭제: WD181 Delete");
    JsonNode meta = auditMetadata(rows.get(0));
    assertThat(meta.get("name").asText()).isEqualTo("WD181 Delete");
    assertThat(meta.get("authType").asText()).isEqualTo("BEARER");
    assertThat(allAuditText(created.id())).doesNotContain(secret).doesNotContain("****");
  }

  // ── WD-392: 인증 필드 검사 ──────────────────────────────────────────────

  @Autowired private EncryptionService encryptionService;

  private CreateApiConnectionRequest apiKeyReq(String name, Map<String, String> authConfig) {
    return new CreateApiConnectionRequest(
        name, null, "API_KEY", authConfig, "https://api.example.com", null);
  }

  /** 헤더 이름으로 쓸 수 없는 값(띄어쓰기·콜론·빈 값·예약 헤더)은 저장하지 않고 한국어 사유로 400 이다. */
  @ParameterizedTest
  @ValueSource(strings = {"X API Key", "X-Key:evil", " ", "Host", "content-length"})
  void create_invalidHeaderName_isRejected(String headerName) {
    assertThatThrownBy(
            () ->
                apiConnectionService.create(
                    apiKeyReq(
                        "WD392 " + headerName.hashCode(),
                        Map.of("placement", "header", "headerName", headerName, "apiKey", "abc")),
                    testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("헤더 이름");
  }

  /** 키 값에 줄바꿈이 있거나 쿼리 파라미터 이름이 비면 거부한다 — 문구에 키 원문은 싣지 않는다. */
  @Test
  void create_lineBreakInKeyOrBlankParamName_isRejected() {
    assertThatThrownBy(
            () ->
                apiConnectionService.create(
                    apiKeyReq(
                        "WD392 newline",
                        Map.of(
                            "placement",
                            "header",
                            "headerName",
                            "X-API-Key",
                            "apiKey",
                            "abc\r\nX-Evil: 1")),
                    testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("줄바꿈")
        .hasMessageNotContaining("abc");
    assertThatThrownBy(
            () ->
                apiConnectionService.create(
                    apiKeyReq(
                        "WD392 query",
                        Map.of("placement", "query", "paramName", "", "apiKey", "abc")),
                    testUserId))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageContaining("쿼리 파라미터 이름");
  }

  /** 정상 헤더 이름은 그대로 저장되고, Bearer 토큰 앞에 붙여 넣은 「Bearer 」 는 떼어 저장한다. */
  @Test
  void create_validHeaderAndBearerPrefix_areSavedNormalized() {
    ApiConnectionResponse key =
        apiConnectionService.create(
            apiKeyReq(
                "WD392 ok",
                Map.of("placement", "header", "headerName", "X-API-Key", "apiKey", "abc")),
            testUserId);
    assertThat(apiConnectionService.getDecryptedAuthConfig(key.id()))
        .containsEntry("headerName", "X-API-Key");

    ApiConnectionResponse bearer =
        apiConnectionService.create(
            new CreateApiConnectionRequest(
                "WD392 bearer",
                null,
                "BEARER",
                Map.of("token", "Bearer abc123"),
                "https://api.example.com",
                null),
            testUserId);
    assertThat(apiConnectionService.getDecryptedAuthConfig(bearer.id()))
        .containsEntry("token", "abc123");
  }

  /** 검사 전에 저장된 잘못된 헤더 이름으로 테스트하면 호출하지 않고 「연결하지 못했습니다」 대신 실제 원인을 보인다. */
  @Test
  void testConnection_storedInvalidHeaderName_reportsRealCause() throws Exception {
    String encrypted =
        encryptionService.encrypt(
            objectMapper.writeValueAsString(
                Map.of("placement", "header", "headerName", "X API Key", "apiKey", "abc")));
    Long id =
        tx.execute(
            s ->
                apiConnectionRepository.save(
                    "WD392 legacy",
                    null,
                    "API_KEY",
                    encrypted,
                    testUserId,
                    "https://api.example.com",
                    null));

    var result = apiConnectionService.testConnection(id);

    assertThat(result.ok()).isFalse();
    assertThat(result.errorMessage()).startsWith("헤더 이름이 올바르지 않습니다").doesNotContain("연결하지 못했습니다");
  }

  // ── WD-639: 가린 값 판정 한 규칙(「Bearer 」·앞뒤 공백)·credentialsChanged·검사 순서 ──────────────────────

  /**
   * 조회한 가린 값에 「Bearer 」 접두어나 앞뒤 공백이 붙어 되돌아와도 가린 값으로 알아보고 저장 원문을 유지한다. 예전에는 원값 그대로 가린 값 판정을 해 놓치고,
   * 뒤이은 정리(「Bearer 」 떼기·strip)가 {@code ****oken} 을 만들어 실제 토큰을 가린 문자열로 덮었다(main 에도 있던 경로).
   */
  @Test
  void update_bearerPrefixedOrPaddedMaskedEcho_keepsStoredToken() {
    Long id = createBearer("wd639-echo");
    String masked = apiConnectionService.getById(id).maskedAuthConfig().get("token");
    assertThat(masked).isEqualTo("****oken");

    for (String authType : Arrays.asList("BEARER", null)) {
      for (String echo :
          List.of(
              "Bearer " + masked,
              "  bearer   " + masked + "  ",
              "Bearer Bearer " + masked,
              " " + masked)) {
        apiConnectionService.update(
            id,
            new UpdateApiConnectionRequest(null, null, authType, Map.of("token", echo), null, null),
            testUserId);
        assertThat(apiConnectionService.getDecryptedAuthConfig(id).get("token"))
            .as("authType=%s echo=%s", authType, echo)
            .isEqualTo("wd623-stored-token");
      }
    }
    // 비밀이 그대로이므로 「인증 정보 변경」 감사도 없다
    assertThat(connectionAudits("UPDATE", id)).isEmpty();
  }

  /** 「Bearer 」 가 붙었어도 저장값의 가린 형태와 다른 가린 값은 일반 가린 값처럼 거절하고 저장 토큰은 그대로다. */
  @Test
  void update_bearerPrefixedMaskedValueNotMatchingStored_rejected() {
    Long id = createBearer("wd639-mismatch");

    assertThatThrownBy(
            () ->
                apiConnectionService.update(
                    id,
                    new UpdateApiConnectionRequest(
                        null, null, null, Map.of("token", "Bearer ****zzzz"), null, null),
                    testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authConfig.token");

    assertAuthUnchanged(id, "BEARER", "token", "wd623-stored-token");
  }

  /** API_KEY 도 앞뒤 공백이 붙은 가린 값을 알아보고 저장 키를 유지한다 — 예전에는 앞 공백이면 「 ****cret」 이 키로 저장됐다(뒤 공백은 400). */
  @Test
  void update_apiKeyPaddedMaskedEcho_keepsStoredKey() {
    ApiConnectionResponse created =
        apiConnectionService.create(validReq("https://wd639-key.example.com"), testUserId);
    String masked = apiConnectionService.getById(created.id()).maskedAuthConfig().get("apiKey");

    for (String echo : List.of(" " + masked, masked + " ", "\t" + masked + " ")) {
      apiConnectionService.update(
          created.id(),
          new UpdateApiConnectionRequest(
              null,
              null,
              "API_KEY",
              Map.of("placement", "header", "headerName", "X-Key", "apiKey", echo),
              null,
              null),
          testUserId);
      assertThat(apiConnectionService.getDecryptedAuthConfig(created.id()).get("apiKey"))
          .as("echo=[%s]", echo)
          .isEqualTo("secret");
    }
  }

  /**
   * 인증 방식을 바꾸며 앞 공백 붙은 가린 값을 보내면 새 방식의 비밀이 없는 것으로 보고 WD-180 문구로 거절한다(예전에는 「 ****abcd」 가 키로 저장됐다).
   */
  @Test
  void update_authTypeChangedWithPaddedMaskedApiKey_rejectedWithWd180Message() {
    Long id = createBearer("wd639-change");

    assertThatThrownBy(
            () ->
                apiConnectionService.update(
                    id,
                    new UpdateApiConnectionRequest(
                        null,
                        null,
                        "API_KEY",
                        Map.of("placement", "header", "headerName", "X-Key", "apiKey", " ****abcd"),
                        null,
                        null),
                    testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("인증 방식을 API_KEY(으)로 바꾸려면 새 방식의 인증 정보(authConfig.apiKey)를 함께 입력하세요");

    assertAuthUnchanged(id, "BEARER", "token", "wd623-stored-token");
  }

  /** 생성·저장 전 테스트에도 같은 판정이다 — 「Bearer ****…」·「 ****…」 는 유지할 저장값이 없으니 거절하고 대상 서버를 부르지 않는다. */
  @Test
  void createAndTestPayload_prefixedOrPaddedMaskedValue_rejected() {
    List<Map.Entry<String, Map<String, String>>> cases =
        List.of(
            Map.entry("BEARER", Map.of("token", "Bearer ****abcd")),
            Map.entry("BEARER", Map.of("token", " ****abcd")),
            Map.entry(
                "API_KEY",
                Map.of("placement", "header", "headerName", "X-Key", "apiKey", " ****abcd")));

    for (Map.Entry<String, Map<String, String>> c : cases) {
      String field = "BEARER".equals(c.getKey()) ? "authConfig.token" : "authConfig.apiKey";
      CreateApiConnectionRequest req = createRequest(c.getKey(), c.getValue());
      assertThatThrownBy(() -> apiConnectionService.create(req, testUserId))
          .as("create %s", c)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(field);
      assertThat(apiConnectionService.getAll()).noneMatch(r -> req.name().equals(r.name()));
      assertThatThrownBy(() -> apiConnectionService.testConnectionPayload(req))
          .as("test %s", c)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(field);
    }
    verify(ssrfProtectionService, never()).validateUrl(anyString());
  }

  /**
   * 화면은 저장할 때 placement·headerName 기본값과 authType 을 authConfig 에 함께 싣는다. 그 기본값 없이 저장된 연결(AI 챗·API 로
   * 만든 연결)에 가린 값을 그대로 되돌려 보내면 뜻이 같으므로 「인증 정보 변경」이 아니다 — 예전에는 키 구성이 달라 credentialsChanged=true 감사가
   * 남았다.
   */
  @Test
  void update_maskedEchoWithExplicitDefaults_isNotCredentialsChange() {
    ApiConnectionResponse created =
        apiConnectionService.create(
            createRequest("API_KEY", Map.of("apiKey", "wd639-key-1234")), testUserId);
    String maskedKey = apiConnectionService.getById(created.id()).maskedAuthConfig().get("apiKey");

    apiConnectionService.update(
        created.id(),
        new UpdateApiConnectionRequest(
            null,
            null,
            "API_KEY",
            Map.of(
                "authType",
                "API_KEY",
                "placement",
                "header",
                "headerName",
                "X-API-Key",
                "apiKey",
                maskedKey),
            null,
            null),
        testUserId);

    assertThat(apiConnectionService.getDecryptedAuthConfig(created.id()).get("apiKey"))
        .isEqualTo("wd639-key-1234");
    assertThat(connectionAudits("UPDATE", created.id())).isEmpty();
  }

  /**
   * 정리 규칙(「Bearer 」 떼기) 전에 저장된 옛 토큰({@code Bearer wd639-legacy-token})에 가린 값을 그대로 되돌려 보내면, 저장값은 정리된
   * 형태로 다시 쓰이지만 사용자가 비밀을 바꾼 것은 아니다 — 양쪽을 같은 정리를 거쳐 비교하므로 credentialsChanged 감사가 남지 않는다.
   */
  @Test
  void update_maskedEchoOfLegacyUnnormalizedToken_isNotCredentialsChange() throws Exception {
    Long id = saveLegacy("BEARER", Map.of("token", "Bearer wd639-legacy-token"));
    Map<String, String> masked = apiConnectionService.getById(id).maskedAuthConfig();

    apiConnectionService.update(
        id, new UpdateApiConnectionRequest(null, null, "BEARER", masked, null, null), testUserId);

    assertThat(apiConnectionService.getDecryptedAuthConfig(id).get("token"))
        .isEqualTo("wd639-legacy-token");
    assertThat(connectionAudits("UPDATE", id)).isEmpty();
  }

  /** 실제로 새 토큰을 넣으면 여전히 「인증 정보 변경」이다(회귀) — 비교 정리가 진짜 변경을 가리지 않는다. */
  @Test
  void update_realTokenChange_stillCredentialsChange() throws Exception {
    Long id = createBearer("wd639-real-change");

    apiConnectionService.update(
        id,
        new UpdateApiConnectionRequest(
            null, null, null, Map.of("token", "Bearer wd639-brand-new"), null, null),
        testUserId);

    List<Record> rows = connectionAudits("UPDATE", id);
    assertThat(rows).hasSize(1);
    assertThat(auditMetadata(rows.get(0)).get("credentialsChanged").asBoolean()).isTrue();
  }

  /**
   * 입력 검증(빈 비밀·가린 값, 400)은 이름 중복(409)보다 먼저다 — 생성·수정 모두. WD-623 이 순서를 이미 맞췄는지 확인하는 회귀 TC(기준 URL 은 기존
   * TC 가 본다).
   */
  @Test
  void invalidAuthConfig_takesPrecedenceOverDuplicateName() {
    String taken = "WD639 taken " + System.nanoTime();
    apiConnectionService.create(validReq("https://wd639-dup.example.com", taken), testUserId);
    Long other = createBearer("wd639-dup-other");

    assertThatThrownBy(
            () ->
                apiConnectionService.create(
                    new CreateApiConnectionRequest(
                        taken,
                        null,
                        "BEARER",
                        Map.of("token", "Bearer "),
                        "https://wd639-dup.example.com",
                        null),
                    testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("Bearer 토큰을 입력하세요 (authConfig.token)");
    assertThatThrownBy(
            () ->
                apiConnectionService.update(
                    other,
                    new UpdateApiConnectionRequest(
                        taken, null, null, Map.of("token", " "), null, null),
                    testUserId))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("Bearer 토큰을 입력하세요 (authConfig.token)");
    assertThatThrownBy(
            () ->
                apiConnectionService.update(
                    other,
                    new UpdateApiConnectionRequest(
                        taken, null, null, Map.of("token", "****zzzz"), null, null),
                    testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authConfig.token");
    assertAuthUnchanged(other, "BEARER", "token", "wd623-stored-token");
  }
}
