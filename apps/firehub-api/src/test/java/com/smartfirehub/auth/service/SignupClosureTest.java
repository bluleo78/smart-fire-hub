package com.smartfirehub.auth.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 공개 가입 폐쇄(WD-2): 사용자 0명일 때만 가입, 그 첫 사용자는 기본 테넌트 ADMIN.
 *
 * <p><b>"사용자 0명" 을 어떻게 만드나:</b> 공유 Testcontainers DB 에는 다른 테스트의 사용자가 항상 있고
 * 지울 수 없다. 그래서 {@code existsAnyUser()} 만 스파이로 바꿔 "기준선(max id) 이후에 생긴 사용자가
 * 있는가" 로 판정하게 한다. 실제 쿼리를 쓰므로 advisory lock 직렬화(두 번째 스레드가 첫 스레드의 커밋을
 * 보는가)는 진짜로 검증된다. 비트랜잭션 — 동시성 검증을 위해 각 스레드가 자기 트랜잭션을 커밋해야 한다.
 * Hikari 풀이 2 라 스레드는 2개만 쓴다.
 *
 * <p><b>동시성 테스트가 잠금을 증명하는 방법:</b> 타이밍에 기대지 않는다. 스파이의 {@code
 * existsAnyUser()} 가 "사용자 없음" 으로 판정한 직후 일정 시간 지연시켜(= 첫 트랜잭션이 판정 후 삽입 전에
 * 머무는 구간을 인위로 늘려) 두 트랜잭션이 반드시 겹치게 만든다. advisory lock 이 있으면 두 번째 스레드는
 * 잠금에서 기다렸다가 첫 스레드의 커밋을 본 뒤 403 이 된다. 잠금이 없으면 두 번째 스레드가 그 지연 구간에
 * "사용자 없음" 을 보고 둘 다 생성되어 실패한다.
 */
class SignupClosureTest extends IntegrationTestBase {

  /** 판정 직후(삽입 전) 지연 시간. 0 이면 지연 없음. 동시성 테스트만 켠다. */
  private volatile long decisionDelayMs = 0;

  @MockitoSpyBean private UserRepository userRepository;
  @Autowired private AuthService authService;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;

  private long baselineMaxId;
  private final List<Long> created = new CopyOnWriteArrayList<>();

  @BeforeEach
  void simulateEmptySystem() {
    decisionDelayMs = 0;
    // 신선한 DB(사용자 0명)에서는 max 가 null 이다 — 0 으로 보정한다.
    Long max = dsl.select(org.jooq.impl.DSL.max(USER.ID)).from(USER).fetchOne(0, Long.class);
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
    TenantRlsTestSupport.cleanupAll(
        created.stream()
            .map(
                id ->
                    (Runnable)
                        () ->
                            TestUsers.cleanup(
                                dsl, fixtureTransactionTemplate, id, DEFAULT_TEST_TENANT_ID))
            .toArray(Runnable[]::new));
  }

  private SignupRequest req(String prefix) {
    String u = prefix + "-" + System.nanoTime() + "@example.com";
    return new SignupRequest(u, u, "Password123", "첫사용자");
  }

  @Test
  void firstSignup_withoutTenantContext_assignsDefaultTenantAdmin() {
    TenantContext.clear(); // 가입은 permitAll — 컨텍스트가 없다(옛 SignupTenantScopeTest 의 의도)

    var user = authService.signup(req("first"));
    created.add(user.id());

    List<String> roles =
        inTenantFixture(
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.fetch(
                        "select r.name from user_role ur join role r on r.id = ur.role_id where ur.user_id = ? order by r.name",
                        user.id())
                    .getValues(0, String.class));
    assertThat(roles).containsExactly("ADMIN", "USER");
    assertThat(
            dsl.fetchExists(
                dsl.selectOne()
                    .from("membership")
                    .where("user_id = ? and tenant_id = 1", user.id())))
        .isTrue();
  }

  @Test
  void signup_whenUsersExist_forbiddenWithCode_evenForTakenUsername() {
    // 신선한 DB 에는 사용자가 없을 수 있으므로 직접 삽입으로 한 명 보장하고, 스파이를 풀어 실제 판정을 쓴다.
    String taken = "taken-" + System.nanoTime() + "@example.com";
    created.add(
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                passwordEncoder,
                taken,
                taken,
                "Password123",
                "기존",
                DEFAULT_TEST_TENANT_ID)
            .id());
    reset(userRepository);

    // 이미 있는 아이디로 가입해도 409(아이디 중복)가 아니라 403 이어야 한다 — 계정 존재 여부를 흘리지 않는다.
    assertThatThrownBy(
            () -> authService.signup(new SignupRequest(taken, taken, "Password123", "x")))
        .isInstanceOf(CodedApiException.class)
        .extracting("code")
        .isEqualTo("SIGNUP_DISABLED");
    assertThatThrownBy(() -> authService.signup(req("late")))
        .isInstanceOf(CodedApiException.class)
        .extracting("code")
        .isEqualTo("SIGNUP_DISABLED");
  }

  @Test
  void concurrentFirstSignups_onlyOneSucceeds() throws Exception {
    decisionDelayMs = 700; // 두 트랜잭션이 확실히 겹치게 — 잠금이 없으면 둘 다 "사용자 없음" 을 본다
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<CompletableFuture<Object>> futures =
          List.of(req("race-a"), req("race-b")).stream()
              .map(
                  r ->
                      CompletableFuture.supplyAsync(
                          () -> {
                            try {
                              start.await();
                              var u = authService.signup(r);
                              created.add(u.id());
                              return (Object) u;
                            } catch (CodedApiException e) {
                              return e.code();
                            } catch (InterruptedException e) {
                              throw new IllegalStateException(e);
                            }
                          },
                          pool))
              .toList();
      start.countDown();
      List<Object> results = futures.stream().map(CompletableFuture::join).toList();

      assertThat(created).hasSize(1);
      assertThat(results).filteredOn("SIGNUP_DISABLED"::equals).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void signupStatus_reflectsUserExistence() {
    assertThat(authService.isSignupOpen()).isTrue();
    created.add(authService.signup(req("status")).id());
    assertThat(authService.isSignupOpen()).isFalse();
  }
}
