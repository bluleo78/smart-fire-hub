package com.smartfirehub.auth.service;

import com.smartfirehub.auth.repository.LoginAttemptRepository;
import com.smartfirehub.auth.repository.LoginIpFailureRepository;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 login_attempts row와 login_ip_failures row(로그인 IP 기준 제한, WD-578)를 주기적으로 정리한다(#144).
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

  /** 로그인 IP 기준 제한의 실패 기록(WD-578) — 계정 카운터와 같은 주기로 만료 행을 지운다. */
  private final LoginIpFailureRepository ipFailureRepository;

  /** 시작 30초 후 첫 실행, 이후 1시간 간격. */
  @Scheduled(initialDelay = 30_000, fixedDelay = 3_600_000)
  public void cleanupExpired() {
    // 두 테이블 정리는 서로 독립이다 — 앞의 삭제가 실패해도(DB 순간 장애 등) 뒤의 삭제는 돌게 따로 감싼다. 예외는 스케줄러 밖으로 내지 않고
    // 경고만 남긴다: 만료 판정은 조회 쪽 expires_at 필터가 하므로 정리가 한 주기 밀려도 정확성은 그대로이고, 다음 주기에 다시 지운다.
    purge("login_attempts", repository::deleteExpired);
    purge("login_ip_failures", ipFailureRepository::deleteExpired);
  }

  /**
   * 한 테이블의 만료 행을 지우고 결과를 로그로 남긴다. 실패는 경고만 남기고 삼킨다 — 다른 테이블 정리와 다음 주기를 막지 않기 위해서다.
   *
   * @param table 로그에 적을 테이블 이름
   * @param delete 만료 행을 지우고 지운 행 수를 돌려주는 동작
   */
  private void purge(String table, IntSupplier delete) {
    try {
      int deleted = delete.getAsInt();
      if (deleted > 0) {
        log.debug("Deleted {} expired {} rows", deleted, table);
      }
    } catch (RuntimeException e) {
      log.warn("Failed to delete expired {} rows", table, e);
    }
  }
}
