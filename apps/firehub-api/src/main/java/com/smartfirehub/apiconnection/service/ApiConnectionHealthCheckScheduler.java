package com.smartfirehub.apiconnection.service;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.apiconnection.dto.TestConnectionResponse;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
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
 * <p>10분마다 health_check_path가 설정된 API 연결을 순회하며 상태를 갱신하고, 상태 전환(UP↔DOWN) 발생 시 ApiConnectionNotifier로
 * 알림을 디스패치한다.
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
  private static final Field<String> AC_LAST_STATUS =
      field(name("api_connection", "last_status"), String.class);

  private final ApiConnectionRepository repository;
  private final ApiConnectionService connectionService;
  private final ApiConnectionNotifier notifier;

  /**
   * 헬스체크 대상 API 연결 전체를 순회하여 상태를 갱신한다.
   *
   * <p>각 연결을 독립적으로 처리하여 한 연결의 실패가 나머지 순회를 중단시키지 않도록 try-catch로 격리한다.
   */
  @Scheduled(fixedDelay = FIXED_DELAY_MS, initialDelay = INITIAL_DELAY_MS)
  public void runOnce() {
    List<Record> targets = repository.findHealthCheckable();
    log.info("API connection health check 시작: {} 대상", targets.size());

    for (Record r : targets) {
      Long id = r.get(AC_ID);
      String name = r.get(AC_NAME);
      String prevStatus = r.get(AC_LAST_STATUS);

      try {
        TestConnectionResponse result = connectionService.testConnection(id);
        String newStatus = result.ok() ? "UP" : "DOWN";
        // 상태 전환 시 Notifier가 알림을 디스패치한다 (최초 체크 및 동일 상태는 내부에서 무시됨)
        notifier.notifyStatusChange(id, name, prevStatus, newStatus, result.errorMessage());
      } catch (Exception e) {
        log.error("헬스체크 실패 — connection id={}, name={}: {}", id, name, e.getMessage());
      }
    }

    log.info("API connection health check 완료: {} 대상 처리", targets.size());
  }
}
