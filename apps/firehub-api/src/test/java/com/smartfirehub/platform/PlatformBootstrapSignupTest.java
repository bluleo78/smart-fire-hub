package com.smartfirehub.platform;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.max;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import com.smartfirehub.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 운영자 콘솔 최초 가입(WD-749): 부트스트랩 가능 여부 조회와, 사용자 0명일 때 가입 즉시 로그인되는 공개 가입.
 *
 * <p>"사용자 0명" 흉내 방식은 {@code SignupClosureTest} 와 같다 — 공유 DB 에는 다른 사용자가 항상 있으므로 {@code
 * existsAnyUser()} 만 스파이로 바꿔 "기준선(max id) 이후 사용자가 있는가" 로 판정시킨다. 비트랜잭션이라 각 요청이 자기 트랜잭션을 커밋하고, 그 덕에
 * advisory lock 경쟁도 실제로 검증된다. 이 시험이 만든 사용자는 기준선 이후 id 로 찾아 정리한다.
 */
@AutoConfigureMockMvc
class PlatformBootstrapSignupTest extends IntegrationTestBase {

  private static final String SIGNUP = "/api/platform/auth/signup";
  private static final String PASSWORD = "Password123";

  /** 판정 직후(삽입 전) 지연 시간 — 동시성 시험만 켜서 두 트랜잭션이 확실히 겹치게 한다. */
  private volatile long decisionDelayMs = 0;

  @MockitoSpyBean private UserRepository userRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwtTokenProvider;

  private long baselineMaxId;

  @BeforeEach
  void simulateEmptySystem() {
    decisionDelayMs = 0;
    Long max = dsl.select(max(USER.ID)).from(USER).fetchOne(0, Long.class);
    baselineMaxId = max == null ? 0L : max;
    doAnswer(
            inv -> {
              boolean exists =
                  dsl.fetchExists(dsl.selectOne().from(USER).where(USER.ID.gt(baselineMaxId)));
              if (!exists && decisionDelayMs > 0) {
                Thread.sleep(decisionDelayMs);
              }
              return exists;
            })
        .when(userRepository)
        .existsAnyUser();
  }

  @AfterEach
  void tearDown() {
    reset(userRepository);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // 가입·픽스처로 기준선 이후 생긴 사용자를 모두 지운다.
    List<Long> ids = dsl.select(USER.ID).from(USER).where(USER.ID.gt(baselineMaxId)).fetch(USER.ID);
    TenantRlsTestSupport.cleanupAll(
        ids.stream()
            .map(
                id ->
                    (Runnable)
                        () ->
                            TestUsers.cleanup(
                                dsl, fixtureTransactionTemplate, id, DEFAULT_TEST_TENANT_ID))
            .toArray(Runnable[]::new));
  }

  private String uniqueName(String prefix) {
    return prefix + "-" + System.nanoTime() + "@example.com";
  }

  private String body(String username, String password) {
    return "{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\",\"name\":\"첫운영자\"}"
        .formatted(username, username, password);
  }

  private MockHttpServletRequestBuilder signupRequest(String username, String password) {
    return post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(body(username, password));
  }

  private int userCountAfterBaseline() {
    return dsl.fetchCount(dsl.selectOne().from(USER).where(USER.ID.gt(baselineMaxId)));
  }

  private int superAdminRowsAfterBaseline() {
    return dsl.fetchCount(
        dsl.selectOne().from("platform_user_role").where("user_id > ?", baselineMaxId));
  }

  /** 기존 회원 한 명을 직접 삽입해 "사용자 있음" 상태로 만든다(스파이 판정이 기준선 이후를 보므로). */
  private String insertExistingUser() {
    String name = uniqueName("existing");
    TestUsers.createMember(
        dsl,
        fixtureTransactionTemplate,
        passwordEncoder,
        name,
        name,
        PASSWORD,
        "기존",
        DEFAULT_TEST_TENANT_ID);
    return name;
  }

  @Test
  void bootstrapStatus_trueWhenNoUsers_falseOtherwise() throws Exception {
    mockMvc
        .perform(get("/api/platform/auth/bootstrap-status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true));

    insertExistingUser();

    mockMvc
        .perform(get("/api/platform/auth/bootstrap-status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false));
  }

  @Test
  void signup_whenNoUsers_createsSuperAdminAndLogsIn() throws Exception {
    String username = uniqueName("boot");
    // 운영 요청은 TenantContext 가 없다 — 테스트 스레드에 남은 기본 테넌트가 감사 행의 tenant_id 를 바꾸지 않게 비운다.
    TenantContext.clear();

    MockHttpServletResponse response =
        mockMvc
            .perform(signupRequest(username, PASSWORD))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andExpect(jsonPath("$.permissions").isArray())
            .andReturn()
            .getResponse();

    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // 리프레시 토큰은 운영자 전용 쿠키로만 내려간다.
    assertThat(response.getHeaders("Set-Cookie"))
        .anyMatch(c -> c.startsWith("platformRefreshToken="));
    JsonNode json = objectMapper.readTree(response.getContentAsString());
    List<String> permissions = new ArrayList<>();
    json.get("permissions").forEach(n -> permissions.add(n.asText()));
    assertThat(permissions).contains("platform:tenant:read");

    // DB: 사용자 1명, 기본 테넌트 USER·ADMIN, 플랫폼 SUPER_ADMIN, 기본 멤버십
    assertThat(userCountAfterBaseline()).isEqualTo(1);
    long userId =
        dsl.select(USER.ID).from(USER).where(USER.USERNAME.eq(username)).fetchOne(USER.ID);
    List<String> roles =
        inTenantFixture(
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.fetch(
                        "select r.name from user_role ur join role r on r.id = ur.role_id"
                            + " where ur.user_id = ? order by r.name",
                        userId)
                    .getValues(0, String.class));
    assertThat(roles).containsExactly("ADMIN", "USER");
    assertThat(
            dsl.fetch(
                    "select pr.name from platform_user_role pur"
                        + " join platform_role pr on pr.id = pur.platform_role_id"
                        + " where pur.user_id = ?",
                    userId)
                .getValues(0, String.class))
        .containsExactly("SUPER_ADMIN");
    assertThat(
            dsl.fetchExists(
                dsl.selectOne().from("membership").where("user_id = ? and tenant_id = 1", userId)))
        .isTrue();

    // 부트스트랩 가입이 플랫폼 감사(metadata.plane=platform)로 남는다(WD-749 최종 리뷰 I-1).
    // audit_log 는 RLS 대상이고 운영자 감사는 tenant_id NULL 이라, 테넌트 없는 컨텍스트에서 읽는다.
    List<String> auditRows =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            null,
            () ->
                dsl.fetch(
                        "select metadata::text from audit_log"
                            + " where action_type = 'BOOTSTRAP_SIGNUP' and user_id = ?",
                        userId)
                    .getValues(0, String.class));
    assertThat(auditRows).hasSize(1);
    JsonNode auditMeta = objectMapper.readTree(auditRows.get(0));
    assertThat(auditMeta.get("plane").asText()).isEqualTo("platform");
    assertThat(auditMeta.get("username").asText()).isEqualTo(username);
    assertThat(auditMeta.get("roles")).hasSize(3);

    // 받은 토큰으로 바로 운영자 세션이 확인된다.
    mockMvc
        .perform(
            get("/api/platform/auth/me")
                .header("Authorization", "Bearer " + json.get("accessToken").asText()))
        .andExpect(status().isOk());
  }

  @Test
  void signup_whenUsersExist_returns403AndCreatesNothing() throws Exception {
    String existing = insertExistingUser();
    reset(userRepository); // 실제 판정(사용자 있음)을 쓴다
    // 기준선 이후 행만 센다 — 다른 시험이 공유 DB 에 남긴 행에 흔들리지 않게.
    int usersBefore = userCountAfterBaseline();
    int roleRowsBefore = superAdminRowsAfterBaseline();
    int auditBefore = bootstrapAuditCount();

    MockHttpServletResponse response =
        mockMvc
            .perform(signupRequest(existing, PASSWORD))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("SIGNUP_DISABLED"))
            .andReturn()
            .getResponse();

    assertThat(userCountAfterBaseline()).isEqualTo(usersBefore);
    assertThat(superAdminRowsAfterBaseline()).isEqualTo(roleRowsBefore);
    // 거절된 가입은 감사 행을 늘리지 않는다.
    assertThat(bootstrapAuditCount()).isEqualTo(auditBefore);
    assertThat(response.getHeaders("Set-Cookie")).isEmpty();
  }

  /** 가입 전에 같은 username 으로 로그인 실패가 임계값만큼 쌓여도 가입 직후 로그인(201)이 잠금에 막히지 않는다. */
  @Test
  void signup_afterFailedLoginsForSameUsername_resetsLockCounter() throws Exception {
    String username = uniqueName("locked");
    // 잠금 임계값(5회)만큼 실패시킨다 — 6번째는 이미 429 라 의미가 없다.
    for (int i = 0; i < 5; i++) {
      mockMvc
          .perform(
              post("/api/platform/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
          .andExpect(status().isUnauthorized());
    }

    mockMvc
        .perform(signupRequest(username, PASSWORD))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessToken").isNotEmpty());
  }

  private int bootstrapAuditCount() {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        null,
        () ->
            dsl.fetchCount(
                dsl.selectOne().from("audit_log").where("action_type = 'BOOTSTRAP_SIGNUP'")));
  }

  /** 웹 가입과 운영자 가입이 동시에 와도 같은 advisory lock 으로 직렬화되어 한 쪽만 성공한다. */
  @Test
  void concurrentWebAndAdminFirstSignup_onlyOneSucceeds() throws Exception {
    decisionDelayMs = 700;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      String webUser = uniqueName("race-web");
      String adminUser = uniqueName("race-admin");
      List<CompletableFuture<MockHttpServletResponse>> futures =
          List.of(
                  post("/api/v1/auth/signup")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(body(webUser, PASSWORD)),
                  signupRequest(adminUser, PASSWORD))
              .stream()
              .map(
                  req ->
                      CompletableFuture.supplyAsync(
                          () -> {
                            try {
                              start.await();
                              return mockMvc.perform(req).andReturn().getResponse();
                            } catch (Exception e) {
                              throw new IllegalStateException(e);
                            }
                          },
                          pool))
              .toList();
      start.countDown();
      List<MockHttpServletResponse> responses =
          futures.stream().map(CompletableFuture::join).toList();
      List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).toList();
      List<String> bodies =
          responses.stream()
              .map(
                  r -> {
                    try {
                      return r.getContentAsString();
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  })
              .toList();

      assertThat(statuses).containsExactlyInAnyOrder(201, 403);
      // 진 쪽 응답은 SIGNUP_DISABLED 여야 한다(다른 이유의 403 과 구분).
      assertThat(bodies).filteredOn(b -> b.contains("SIGNUP_DISABLED")).hasSize(1);
      assertThat(userCountAfterBaseline()).isEqualTo(1);
      assertThat(superAdminRowsAfterBaseline()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  /** 브라우저가 살아 있는 테넌트 Bearer 를 함께 보내도 평면 불일치 403 으로 막히지 않는다. */
  @Test
  void signup_withTenantBearer_isNotBlockedByPlaneFilter() throws Exception {
    String tenantToken = jwtTokenProvider.generateAccessToken(1L, "user", 1L);

    mockMvc
        .perform(
            signupRequest(uniqueName("bearer"), PASSWORD)
                .header("Authorization", "Bearer " + tenantToken))
        .andExpect(status().isCreated());
  }

  @Test
  void signup_invalidBody_returns400() throws Exception {
    mockMvc.perform(signupRequest(uniqueName("weak"), "abc")).andExpect(status().isBadRequest());

    assertThat(userCountAfterBaseline()).isZero();
  }
}
