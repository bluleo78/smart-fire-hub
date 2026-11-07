package com.smartfirehub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.exception.AccountLockedException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.repository.LoginAttemptRepository;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.platform.service.PlatformAuthService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 로그인 동시 요청 커넥션 풀 교착 회귀 테스트(WD-552).
 *
 * <p>예전에는 {@code login} 의 바깥 트랜잭션이 커넥션 1개를 쥔 채 실패 카운터 쓰기(REQUIRES_NEW)가 두 번째 커넥션을 요구해, 동시 요청 수가 풀
 * 크기(테스트 2개) 이상이면 모두 두 번째 커넥션을 기다리며 교착했다(Hikari 30초 대기 → 500). 카운터 쓰기가 바깥 트랜잭션에 참여하게 바뀐 뒤로는 요청당 커넥션
 * 1개라 풀보다 많은 동시 요청도 차례로 끝나야 한다.
 *
 * <p>카운터가 바깥 트랜잭션에 실리므로, 실패 예외에서 롤백하지 않는 계약(noRollbackFor)이 지켜져야 카운터가 남는다 — 테넌트·운영자 두 평면 모두 단언한다.
 *
 * <p>클래스 {@code @Transactional} 을 두지 않는다: 테스트 트랜잭션이 커넥션을 쥐면 풀 계산이 달라지고, 카운터 커밋 여부를 볼 수 없다. 같은 이유로
 * 로그인은 {@link TenantContext} 없이 부른다 — 운영의 permitAll {@code /auth/login} 에는 컨텍스트가
 * 없다(LoginAuditTenantTest 와 같은 재현). 격리는 고유 아이디로 하고, 테스트가 커밋한 행은 끝에 모두 지운다.
 */
class LoginConnectionPoolTest extends IntegrationTestBase {

  /** 풀 크기(2)보다 충분히 많은 동시 요청 수. */
  private static final int CONCURRENCY = 8;

  /** 교착이 없으면 넉넉히 끝나는 시간. 교착이면 Hikari 대기(30초)에 걸려 이 안에 끝나지 않는다. */
  private static final long TIMEOUT_SECONDS = 10;

  private static final String PASSWORD = "Password123";

  @Autowired private AuthService authService;
  @Autowired private PlatformAuthService platformAuthService;
  @Autowired private LoginAttemptRepository loginAttemptRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;

  /** 테스트가 쓴 아이디들 — 끝에 없는 아이디 실패 감사 행(user_id NULL)과 login_attempts 행을 지운다. */
  private final List<String> usedUsernames = new ArrayList<>();

  /** 테스트가 만든 사용자 id — 끝에 {@link TestUsers#cleanup} 으로 지운다(플랫폼 롤·역할은 사용자 삭제에 CASCADE). */
  private final List<Long> createdUserIds = new ArrayList<>();

  /** 테스트가 커밋한 행을 모두 지운다. 없는 아이디 실패 감사 행은 user_id 가 없어 아이디로, NULL 테넌트 컨텍스트에서 지운다. */
  @AfterEach
  void tearDown() {
    for (Long userId : createdUserIds) {
      TestUsers.cleanup(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID);
    }
    if (!usedUsernames.isEmpty()) {
      String[] usernames = usedUsernames.toArray(new String[0]);
      inTenantFixture(
          (Long) null,
          () -> {
            dsl.execute("delete from audit_log where username = any(?)", (Object) usernames);
          });
      dsl.execute("delete from login_attempts where username = any(?)", (Object) usernames);
    }
    TenantContext.clear();
  }

  /** 없는 아이디로 동시에 실패 로그인 — 모두 자격 증명 오류로 제시간에 끝나야 하고(교착이면 타임아웃), 동시성 아래에서도 아이디마다 카운터 1 이 커밋돼야 한다. */
  @Test
  void concurrentFailedTenantLogins_doNotExhaustPool() throws Exception {
    List<String> usernames = new ArrayList<>();
    for (int i = 0; i < CONCURRENCY; i++) {
      usernames.add(uniqueUsername("wd552-unk" + i));
    }
    TenantContext.clear();

    List<Object> outcomes =
        runConcurrently(i -> () -> authService.login(new LoginRequest(usernames.get(i), "wrong")));

    assertThat(outcomes)
        .hasSize(CONCURRENCY)
        .allMatch(o -> o instanceof InvalidCredentialsException);
    for (String username : usernames) {
      assertThat(loginAttemptRepository.getAttempts(username))
          .as("카운터 커밋: %s", username)
          .isEqualTo(1);
    }
  }

  /** 같은 계정으로 동시에 성공 로그인(성공 경로의 카운터 clear) — 모두 성공해야 한다. */
  @Test
  void concurrentSuccessfulTenantLogins_doNotExhaustPool() throws Exception {
    String username = uniqueUsername("wd552-ok");
    var member =
        TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            username,
            username,
            PASSWORD,
            "WD-552",
            DEFAULT_TEST_TENANT_ID);
    createdUserIds.add(member.id());
    TenantContext.clear();

    List<Object> outcomes =
        runConcurrently(i -> () -> authService.login(new LoginRequest(username, PASSWORD)));

    assertThat(outcomes).hasSize(CONCURRENCY).allMatch(o -> o instanceof TokenResponse);
  }

  /**
   * 운영자 로그인 실패도 카운터를 남기고, 5회면 잠겨 6번째 시도(올바른 비밀번호)는 잠금 예외가 난다 — PlatformAuthService.login 의
   * noRollbackFor 회귀.
   */
  @Test
  void failedPlatformLogin_persistsCounterAndLocks() {
    String username = uniqueUsername("wd552-pfail");
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, username, passwordEncoder.encode(PASSWORD));
    createdUserIds.add(userId);
    TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, userId);
    TenantContext.clear();

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> platformAuthService.login(new LoginRequest(username, "wrong")))
          .isInstanceOf(InvalidCredentialsException.class);
    }
    assertThat(loginAttemptRepository.getAttempts(username)).isEqualTo(5);

    assertThatThrownBy(() -> platformAuthService.login(new LoginRequest(username, PASSWORD)))
        .isInstanceOf(AccountLockedException.class);
  }

  /**
   * 고유 아이디를 만들고 정리 목록에 올린다. 카운터 키가 소문자로 정규화되므로(WD-269) 소문자만 쓴다. username 컬럼이 50자라 UUID 앞부분만 붙인다.
   */
  private String uniqueUsername(String prefix) {
    String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    usedUsernames.add(username);
    return username;
  }

  /**
   * 작업 {@link #CONCURRENCY} 개를 고정 스레드풀에서 같은 순간에 출발시키고(CountDownLatch) 결과를 모은다. 작업이 던진 예외는 결과로 돌려준다.
   * 전체가 {@link #TIMEOUT_SECONDS} 안에 끝나지 않으면 {@code TimeoutException} 으로 실패한다 — 교착의 신호다.
   */
  private List<Object> runConcurrently(IntFunction<Callable<Object>> taskFactory)
      throws InterruptedException, ExecutionException, TimeoutException {
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Object>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENCY; i++) {
        Callable<Object> task = taskFactory.apply(i);
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    return task.call();
                  } catch (Exception e) {
                    return e;
                  }
                }));
      }
      start.countDown();

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
      List<Object> outcomes = new ArrayList<>();
      for (Future<Object> future : futures) {
        long remaining = Math.max(0, deadline - System.nanoTime());
        outcomes.add(future.get(remaining, TimeUnit.NANOSECONDS));
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }
}
