package com.smartfirehub.apiconnection.service;

import static org.jooq.impl.DSL.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartfirehub.apiconnection.dto.TestConnectionResponse;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import java.util.List;
import org.jooq.Field;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ApiConnectionHealthCheckScheduler 단위 테스트.
 * Repository, ApiConnectionService, ApiConnectionNotifier를 mock으로 격리하여
 * 스케줄러의 라우팅 로직(상태 전환 감지 → Notifier 호출)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ApiConnectionHealthCheckSchedulerTest {

  @Mock ApiConnectionRepository repository;
  @Mock ApiConnectionService connectionService;
  @Mock ApiConnectionNotifier notifier;

  @InjectMocks ApiConnectionHealthCheckScheduler scheduler;

  // 테스트용 jOOQ Record 헬퍼 필드
  private static final Field<Long> AC_ID = field(name("api_connection", "id"), Long.class);
  private static final Field<String> AC_NAME =
      field(name("api_connection", "name"), String.class);
  private static final Field<String> AC_LAST_STATUS =
      field(name("api_connection", "last_status"), String.class);

  /** findHealthCheckable()이 빈 목록을 반환하면 아무 동작도 하지 않아야 한다. */
  @Test
  void runOnce_noTargets_doesNothing() {
    when(repository.findHealthCheckable()).thenReturn(List.of());

    scheduler.runOnce();

    verifyNoInteractions(connectionService, notifier);
  }

  /** 헬스체크 성공(ok=true) 시 notifier.notifyStatusChange가 "UP"으로 호출된다. */
  @Test
  void runOnce_success_callsNotifierWithUp() throws Exception {
    Record r = mockRecord(10L, "Test-API", "DOWN");
    when(repository.findHealthCheckable()).thenReturn(List.of(r));
    when(connectionService.testConnection(10L))
        .thenReturn(new TestConnectionResponse(true, 200, 50L, null));

    scheduler.runOnce();

    verify(notifier).notifyStatusChange(10L, "Test-API", "DOWN", "UP", null);
  }

  /** 헬스체크 실패(ok=false) 시 notifier.notifyStatusChange가 "DOWN"으로 호출된다. */
  @Test
  void runOnce_failure_callsNotifierWithDown() throws Exception {
    Record r = mockRecord(20L, "Broken-API", "UP");
    when(repository.findHealthCheckable()).thenReturn(List.of(r));
    when(connectionService.testConnection(20L))
        .thenReturn(new TestConnectionResponse(false, 503, 100L, "HTTP 503"));

    scheduler.runOnce();

    verify(notifier).notifyStatusChange(20L, "Broken-API", "UP", "DOWN", "HTTP 503");
  }

  /** testConnection 예외 발생 시 해당 연결만 건너뛰고 나머지 계속 처리한다. */
  @Test
  void runOnce_exceptionOnOne_continuesOthers() throws Exception {
    Record r1 = mockRecord(1L, "API-1", "UP");
    Record r2 = mockRecord(2L, "API-2", "UP");
    when(repository.findHealthCheckable()).thenReturn(List.of(r1, r2));
    when(connectionService.testConnection(1L)).thenThrow(new RuntimeException("connection refused"));
    when(connectionService.testConnection(2L))
        .thenReturn(new TestConnectionResponse(true, 200, 30L, null));

    scheduler.runOnce();

    // API-1은 예외로 notifier 미호출
    verify(notifier, never()).notifyStatusChange(eq(1L), any(), any(), any(), any());
    // API-2는 정상 처리
    verify(notifier).notifyStatusChange(2L, "API-2", "UP", "UP", null);
  }

  /** 최초 체크(prevStatus=null)여도 notifier는 호출된다 — notifier 내부에서 early-return 처리. */
  @Test
  void runOnce_firstCheck_callsNotifierWithNullPrev() throws Exception {
    Record r = mockRecord(5L, "New-API", null);
    when(repository.findHealthCheckable()).thenReturn(List.of(r));
    when(connectionService.testConnection(5L))
        .thenReturn(new TestConnectionResponse(true, 200, 20L, null));

    scheduler.runOnce();

    verify(notifier).notifyStatusChange(5L, "New-API", null, "UP", null);
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────

  /**
   * 지정된 값을 반환하는 mock Record를 생성한다.
   * jOOQ Record 인터페이스를 mock으로 대체하여 실제 DB 없이 테스트한다.
   */
  @SuppressWarnings("unchecked")
  private Record mockRecord(Long id, String name, String lastStatus) {
    Record r = mock(Record.class);
    when(r.get(AC_ID)).thenReturn(id);
    when(r.get(AC_NAME)).thenReturn(name);
    when(r.get(AC_LAST_STATUS)).thenReturn(lastStatus);
    return r;
  }
}
