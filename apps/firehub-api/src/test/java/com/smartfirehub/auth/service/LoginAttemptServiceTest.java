package com.smartfirehub.auth.service;

import static com.smartfirehub.jooq.Tables.LOGIN_ATTEMPTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.auth.repository.LoginAttemptRepository;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * LoginAttemptService 통합 테스트(#144).
 *
 * <p>실제 PostgreSQL(smartfirehub_test)을 사용하여 영속 잠금 로직을 검증한다. 각 테스트는 UUID 기반
 * username으로 격리되어 병렬 실행이나 잔여 데이터에 영향받지 않는다.
 */
class LoginAttemptServiceTest extends IntegrationTestBase {

  @Autowired private LoginAttemptService loginAttemptService;
  @Autowired private LoginAttemptRepository loginAttemptRepository;
  @Autowired private DSLContext dsl;

  private String uniqueUser;

  @BeforeEach
  void setUp() {
    // 테스트 간 격리를 위해 username을 UUID로 분리
    uniqueUser = "user-" + UUID.randomUUID() + "@test.com";
  }

  @Test
  void isBlocked_returnsFalse_whenNoAttempts() {
    assertThat(loginAttemptService.isBlocked(uniqueUser)).isFalse();
  }

  @Test
  void isBlocked_returnsFalse_after4Attempts() {
    for (int i = 0; i < 4; i++) loginAttemptService.loginFailed(uniqueUser);
    assertThat(loginAttemptService.isBlocked(uniqueUser)).isFalse();
  }

  @Test
  void isBlocked_returnsTrue_after5Attempts() {
    for (int i = 0; i < 5; i++) loginAttemptService.loginFailed(uniqueUser);
    assertThat(loginAttemptService.isBlocked(uniqueUser)).isTrue();
  }

  @Test
  void loginSucceeded_resetsCounter() {
    for (int i = 0; i < 5; i++) loginAttemptService.loginFailed(uniqueUser);
    assertThat(loginAttemptService.isBlocked(uniqueUser)).isTrue();

    loginAttemptService.loginSucceeded(uniqueUser);
    assertThat(loginAttemptService.isBlocked(uniqueUser)).isFalse();

    // DB에서 row가 완전히 삭제되었는지 확인
    int rows =
        dsl.fetchCount(
            dsl.selectOne().from(LOGIN_ATTEMPTS).where(LOGIN_ATTEMPTS.USERNAME.eq(uniqueUser)));
    assertThat(rows).isZero();
  }

  @Test
  void isBlocked_isolatesUsernames() {
    // uniqueUser 5회 실패해도 다른 사용자에게 영향 없어야 함
    String other = "other-" + UUID.randomUUID() + "@test.com";
    for (int i = 0; i < 5; i++) loginAttemptService.loginFailed(uniqueUser);

    assertThat(loginAttemptService.isBlocked(uniqueUser)).isTrue();
    assertThat(loginAttemptService.isBlocked(other)).isFalse();
  }

  @Test
  void getAttempts_returnsZero_whenExpired() {
    // 5회 실패 후 만료 시간을 과거로 강제 설정하면 isBlocked는 false를 반환해야 함
    for (int i = 0; i < 5; i++) loginAttemptService.loginFailed(uniqueUser);
    dsl.update(LOGIN_ATTEMPTS)
        .set(LOGIN_ATTEMPTS.EXPIRES_AT, LocalDateTime.now().minusMinutes(1))
        .where(LOGIN_ATTEMPTS.USERNAME.eq(uniqueUser))
        .execute();

    assertThat(loginAttemptService.isBlocked(uniqueUser)).isFalse();
  }

  @Test
  void incrementAttempts_isAtomic_underConcurrency()
      throws ExecutionException, InterruptedException {
    // 5개의 비동기 스레드가 동시에 loginFailed를 호출해도 카운터가 정확히 5여야 함
    CompletableFuture<?>[] futures = new CompletableFuture[5];
    for (int i = 0; i < 5; i++) {
      futures[i] = CompletableFuture.runAsync(() -> loginAttemptService.loginFailed(uniqueUser));
    }
    CompletableFuture.allOf(futures).get();

    int attempts =
        dsl.select(LOGIN_ATTEMPTS.ATTEMPTS)
            .from(LOGIN_ATTEMPTS)
            .where(LOGIN_ATTEMPTS.USERNAME.eq(uniqueUser))
            .fetchOne(LOGIN_ATTEMPTS.ATTEMPTS);
    assertThat(attempts).isEqualTo(5);
    assertThat(loginAttemptService.isBlocked(uniqueUser)).isTrue();
  }

  @Test
  void deleteExpired_removesOnlyExpiredRows() {
    // 만료 row만 삭제되고 유효 row는 유지되어야 함
    String expired1 = "exp1-" + UUID.randomUUID() + "@test.com";
    String expired2 = "exp2-" + UUID.randomUUID() + "@test.com";
    String fresh = "fresh-" + UUID.randomUUID() + "@test.com";

    loginAttemptService.loginFailed(expired1);
    loginAttemptService.loginFailed(expired2);
    loginAttemptService.loginFailed(fresh);

    LocalDateTime past = LocalDateTime.now().minusMinutes(1);
    dsl.update(LOGIN_ATTEMPTS)
        .set(LOGIN_ATTEMPTS.EXPIRES_AT, past)
        .where(LOGIN_ATTEMPTS.USERNAME.in(expired1, expired2))
        .execute();

    int deleted = loginAttemptRepository.deleteExpired();

    // 다른 테스트 잔여 만료 row도 함께 삭제될 수 있으므로 >= 2 검증
    assertThat(deleted).isGreaterThanOrEqualTo(2);
    int freshRow =
        dsl.fetchCount(
            dsl.selectOne().from(LOGIN_ATTEMPTS).where(LOGIN_ATTEMPTS.USERNAME.eq(fresh)));
    assertThat(freshRow).isEqualTo(1);
  }
}
