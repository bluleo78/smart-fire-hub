package com.smartfirehub.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.auth.repository.LoginAttemptRepository;
import com.smartfirehub.auth.repository.LoginIpFailureRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 만료 로그인 기록 정리 스케줄러 단위 테스트(WD-578).
 *
 * <p>두 테이블(login_attempts, login_ip_failures) 정리는 서로 독립이다 — 한쪽 삭제가 실패해도 다른 쪽은 지워야 하고, 예외가 스케줄러 밖으로
 * 새지 않아야 한다(다음 주기에 다시 시도).
 */
class LoginAttemptCleanupSchedulerTest {

  private final LoginAttemptRepository attempts = mock(LoginAttemptRepository.class);
  private final LoginIpFailureRepository ipFailures = mock(LoginIpFailureRepository.class);
  private final LoginAttemptCleanupScheduler scheduler =
      new LoginAttemptCleanupScheduler(attempts, ipFailures);

  @Test
  @DisplayName("계정 카운터 정리가 실패해도 IP 실패 기록 정리는 돈다")
  void firstDeleteFails_secondStillRuns() {
    when(attempts.deleteExpired()).thenThrow(new IllegalStateException("db down"));

    assertThatCode(scheduler::cleanupExpired).doesNotThrowAnyException();
    verify(ipFailures).deleteExpired();
  }

  @Test
  @DisplayName("IP 실패 기록 정리가 실패해도 예외가 새지 않는다")
  void secondDeleteFails_isSwallowed() {
    when(ipFailures.deleteExpired()).thenThrow(new IllegalStateException("db down"));

    assertThatCode(scheduler::cleanupExpired).doesNotThrowAnyException();
    verify(attempts).deleteExpired();
  }
}
