package com.smartfirehub.analytics.service;

import com.smartfirehub.analytics.repository.AnalyticsQueryRunRepository;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 쿼리 실행 기록(V136 analytics_query_run) 주기 정리 — 보존 기간(1시간) 지난 행을 테넌트마다 지운다.
 *
 * <p><b>왜 주기 정리인가(code-review 7).</b> 예전에는 같은 사용자의 다음 실행(삽입) 때만 그 사용자의 만료 행을 지워, 다시 실행하지 않는 사용자의
 * SQL 원문이 무기한 남았다. 내보내기는 만료 조건으로 걸러 쓰지 않지만 원문 보관 자체가 위험이라 전역으로 지운다.
 *
 * <p><b>테넌트 순회</b>: {@code @Scheduled} 에는 원 HTTP 요청이 없어 승계할 테넌트가 없다 — ACTIVE 테넌트를 순회한다. 순회하지 않으면
 * RLS 가 전 행을 가려 정리가 조용히 0행이 된다. 트랜잭션은 리포지토리 클래스 레벨 {@code @Transactional} 이 열고 그때 GUC 가 주입된다.
 *
 * <p>Spring {@code @Scheduled}(AsyncConfig 의 {@code @EnableScheduling}) 이다 — JobRunr 잡이 아니다. 지우는 것이
 * 만료 행뿐이라 임시 api 인스턴스가 함께 돌아도 결과는 같다(멱등).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsQueryRunCleanupService {

  private final AnalyticsQueryRunRepository runRepository;
  private final TenantScopedRunner tenantScopedRunner;

  /**
   * 10분마다(기본) 만료 행을 지운다. 주기·첫 지연을 프로퍼티로 뺀 이유: 테스트는 첫 실행을 스위트 길이 밖으로 밀어, 기록을 일부러 만료시킨 테스트 사이에 정리가
   * 끼어들지 않게 한다(notification.scheduler.initial_delay_ms 와 같은 자리).
   */
  @Scheduled(
      fixedDelayString = "${firehub.analytics.query-run.cleanup.interval-ms:600000}",
      initialDelayString = "${firehub.analytics.query-run.cleanup.initial-delay-ms:60000}")
  public void cleanupExpired() {
    AtomicInteger total = new AtomicInteger();
    tenantScopedRunner.forEachActiveTenant(
        tenantId -> total.addAndGet(runRepository.deleteExpired()));
    if (total.get() > 0) {
      log.info("만료된 쿼리 실행 기록 {}건 정리", total.get());
    }
  }
}
