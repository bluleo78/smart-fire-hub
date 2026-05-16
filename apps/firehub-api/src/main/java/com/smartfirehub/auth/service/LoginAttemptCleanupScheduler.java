package com.smartfirehub.auth.service;

import com.smartfirehub.auth.repository.LoginAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 login_attempts row를 주기적으로 정리한다(#144).
 *
 * <p>lazy expiry(`expires_at > now()` 필터)만으로도 정확성은 보장되지만, 누적된 만료 row가 인덱스 크기를 키우는 것을 막기 위해 1시간 주기로
 * 일괄 삭제한다. 테스트 컨텍스트에서는 {@code app.login-attempts.cleanup.enabled=false}로 비활성화한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.login-attempts.cleanup.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class LoginAttemptCleanupScheduler {

  private final LoginAttemptRepository repository;

  /** 시작 30초 후 첫 실행, 이후 1시간 간격. */
  @Scheduled(initialDelay = 30_000, fixedDelay = 3_600_000)
  public void cleanupExpired() {
    int deleted = repository.deleteExpired();
    if (deleted > 0) {
      log.debug("Deleted {} expired login_attempts rows", deleted);
    }
  }
}
