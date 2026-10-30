package com.smartfirehub.apiconnection.service;

import static org.jooq.impl.DSL.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartfirehub.apiconnection.dto.TestConnectionResponse;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.support.TenantScopedRunnerStubs;
import java.util.List;
import java.util.Map;
import org.jooq.Field;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ApiConnectionHealthCheckScheduler 단위 테스트. Repository, ApiConnectionService 를 mock 으로 격리하여 스케줄러가
 * 대상마다 시스템 점검({@code testConnection(id)})을 부르는지만 검증한다.
 *
 * <p>상태 전환 알림·감사는 스케줄러가 아니라 상태를 저장하는 {@code ApiConnectionService.testConnection} 이 직전 상태(행 잠금)와 비교해
 * 보낸다(WD-189). 그 동작은 실제 DB·WireMock 을 쓰는 {@code ApiConnectionTestUrlMaskingTest} 가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ApiConnectionHealthCheckSchedulerTest {

  @Mock ApiConnectionRepository repository;
  @Mock ApiConnectionService connectionService;
  @Mock TenantScopedRunner tenantScopedRunner;

  /** 주기 실행을 끈 채(테스트 프로필과 같다, WD-451) 만든다 — runOnce() 직접 호출은 꺼져 있어도 돈다. */
  ApiConnectionHealthCheckScheduler scheduler;

  /**
   * 이 단위 테스트는 스케줄러의 라우팅 로직만 보므로 테넌트 순회는 1건으로 단순화한다. 실제 다중 테넌트 순회 검증은 {@code
   * ApiConnectionTenantTest} 가 담당한다.
   */
  @BeforeEach
  void stubSingleTenantIteration() {
    TenantScopedRunnerStubs.stubSingleTenantIteration(tenantScopedRunner, 1L);
    scheduler =
        new ApiConnectionHealthCheckScheduler(repository, connectionService, tenantScopedRunner, false);
  }

  /** 주기 실행이 꺼져 있으면 10분 주기 진입점은 아무것도 하지 않는다(WD-451 — 테스트 프로필이 끈다). */
  @Test
  void scheduledRun_disabled_doesNothing() {
    // 공통 순회 스텁을 쓰지 않는 유일한 경우다 — 엄격 스텁 검사가 「쓰지 않은 스텁」으로 실패하지 않게 비운다
    reset(tenantScopedRunner);

    scheduler.scheduledRun();

    verifyNoInteractions(tenantScopedRunner, repository, connectionService);
  }

  /** 주기 실행이 켜져 있으면(운영 기본값) 진입점이 runOnce 와 같이 대상을 점검한다. */
  @Test
  void scheduledRun_enabled_checksTargets() {
    when(repository.findHealthCheckable()).thenReturn(List.of());

    new ApiConnectionHealthCheckScheduler(repository, connectionService, tenantScopedRunner, true)
        .scheduledRun();

    // 테넌트 순회를 거쳐야 RLS 범위가 서서 대상이 보인다(클래스 주석) — 순회를 건너뛰는 회귀를 막는다
    verify(tenantScopedRunner).forEachActiveTenant(any());
    verify(repository).findHealthCheckable();
  }

  // 테스트용 jOOQ Record 헬퍼 필드
  private static final Field<Long> AC_ID = field(name("api_connection", "id"), Long.class);
  private static final Field<String> AC_NAME = field(name("api_connection", "name"), String.class);

  /** findHealthCheckable()이 빈 목록을 반환하면 아무 동작도 하지 않아야 한다. */
  @Test
  void runOnce_noTargets_doesNothing() {
    when(repository.findHealthCheckable()).thenReturn(List.of());

    scheduler.runOnce();

    verifyNoInteractions(connectionService);
  }

  /**
   * 대상마다 시스템 점검(행위자 없는 testConnection(id))을 부른다 — 사용자 행위자 오버로드를 부르면 주기 점검 전환이 사람이 한 것처럼 감사에 남는다.
   */
  @Test
  void runOnce_callsSystemTestConnectionPerTarget() {
    Record r1 = mockRecord(10L, "API-10");
    Record r2 = mockRecord(20L, "API-20");
    when(repository.findHealthCheckable()).thenReturn(List.of(r1, r2));
    when(connectionService.testConnection(anyLong())).thenReturn(okResponse());

    scheduler.runOnce();

    verify(connectionService).testConnection(10L);
    verify(connectionService).testConnection(20L);
    verify(connectionService, never()).testConnection(anyLong(), any());
  }

  /** testConnection 예외 발생 시 해당 연결만 건너뛰고 나머지 계속 처리한다. */
  @Test
  void runOnce_exceptionOnOne_continuesOthers() {
    Record r1 = mockRecord(1L, "API-1");
    Record r2 = mockRecord(2L, "API-2");
    when(repository.findHealthCheckable()).thenReturn(List.of(r1, r2));
    when(connectionService.testConnection(1L))
        .thenThrow(new RuntimeException("connection refused"));
    when(connectionService.testConnection(2L)).thenReturn(okResponse());

    scheduler.runOnce();

    // API-1 예외 뒤에도 API-2 는 점검된다
    verify(connectionService).testConnection(2L);
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────

  private static TestConnectionResponse okResponse() {
    return new TestConnectionResponse(true, 200, 30L, null, "https://x", null, Map.of(), null);
  }

  /** 지정된 값을 반환하는 mock Record를 생성한다. jOOQ Record 인터페이스를 mock으로 대체하여 실제 DB 없이 테스트한다. */
  private Record mockRecord(Long id, String name) {
    Record r = mock(Record.class);
    when(r.get(AC_ID)).thenReturn(id);
    when(r.get(AC_NAME)).thenReturn(name);
    return r;
  }
}
