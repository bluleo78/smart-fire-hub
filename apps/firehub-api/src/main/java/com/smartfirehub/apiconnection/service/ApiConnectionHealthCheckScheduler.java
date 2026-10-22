package com.smartfirehub.apiconnection.service;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Field;
import org.jooq.Record;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * API 연결 헬스체크 주기 실행기.
 *
 * <p>10분마다 health_check_path가 설정된 API 연결을 순회하며 상태를 갱신한다. 상태 전환(UP↔DOWN) 알림·감사는 이 클래스가 아니라 상태를 저장하는
 * {@link ApiConnectionService#testConnection} 이 저장 직후 직전 상태(행 잠금으로 읽음)와 비교해 보낸다 — 예전처럼 순회 시작 때 읽어 둔
 * 값과 비교하면 그 사이 「테스트」가 바꾼 상태 전환이 알림 없이 사라진다(WD-189).
 *
 * <p>initialDelay=60s: 애플리케이션 기동 직후 외부 API 호출을 방지하기 위해 첫 실행을 1분 뒤로 지연한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiConnectionHealthCheckScheduler {

  /** 헬스체크 반복 주기 (10분) */
  private static final long FIXED_DELAY_MS = 600_000L;

  /** 애플리케이션 기동 직후 첫 실행 지연 (1분) */
  private static final long INITIAL_DELAY_MS = 60_000L;

  // jOOQ 동적 필드 — jOOQ 코드젠 없이 api_connection 테이블 컬럼에 접근한다
  private static final Field<Long> AC_ID = field(name("api_connection", "id"), Long.class);
  private static final Field<String> AC_NAME = field(name("api_connection", "name"), String.class);

  private final ApiConnectionRepository repository;
  private final ApiConnectionService connectionService;
  private final TenantScopedRunner tenantScopedRunner;

  /**
   * ACTIVE 테넌트를 순회하며 각 테넌트의 헬스체크 대상을 점검한다.
   *
   * <p>{@code @Scheduled} 경로에는 원 HTTP 요청이 없어 승계할 테넌트가 없다. 순회하지 않으면 api_connection 의 RLS 정책이 전 행을
   * 차단해 헬스체크가 매번 대상 0건으로 돌고, 예외도 로그도 없이 UP/DOWN 알림이 영구히 누락된다.
   */
  @Scheduled(fixedDelay = FIXED_DELAY_MS, initialDelay = INITIAL_DELAY_MS)
  public void runOnce() {
    tenantScopedRunner.forEachActiveTenant(tenantId -> runOnceForTenant());
  }

  /**
   * 한 테넌트 범위의 헬스체크 본문. 호출 시점에 {@link com.smartfirehub.global.tenant.TenantContext} 가 설정돼 있어야 한다.
   *
   * <p>각 연결을 독립적으로 처리하여 한 연결의 실패가 나머지 순회를 중단시키지 않도록 try-catch로 격리한다.
   */
  private void runOnceForTenant() {
    // findHealthCheckable 은 리포지토리 클래스 레벨 @Transactional 로 자체 트랜잭션을 열므로
    // 그 시점에 GUC 가 주입된다 — 순회로 세운 TenantContext 가 실제 RLS 스코프로 이어진다.
    List<Record> targets = repository.findHealthCheckable();
    log.info("API connection health check 시작: {} 대상", targets.size());

    for (Record r : targets) {
      Long id = r.get(AC_ID);
      String name = r.get(AC_NAME);

      try {
        // 시스템 점검(행위자 없음) — 저장·상태 전환 알림·감사는 testConnection 안에서 한 번에 처리된다.
        connectionService.testConnection(id);
      } catch (Exception e) {
        log.error("헬스체크 실패 — connection id={}, name={}: {}", id, name, e.getMessage());
      }
    }

    log.info("API connection health check 완료: {} 대상 처리", targets.size());
  }
}
