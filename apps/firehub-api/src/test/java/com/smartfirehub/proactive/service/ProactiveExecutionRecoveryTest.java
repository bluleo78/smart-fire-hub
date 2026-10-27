package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.execution.ExecutionMessages;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.tenant.repository.TenantRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 프로액티브 실행 복구기의 테넌트 격리와 살아 있는 실행 보호를 검증한다(WD-258). */
class ProactiveExecutionRecoveryTest {

  private final ProactiveExecutionRegistry registry = new ProactiveExecutionRegistry();
  private final ProactiveJobExecutionRepository repository =
      mock(ProactiveJobExecutionRepository.class);

  private record Beat(long tenantId, Set<Long> ids) {}

  @Test
  void heartbeat_touchesEachTenantInsideItsContext() {
    registry.register(11L, 1L);
    registry.register(12L, 1L);
    registry.register(21L, 2L);
    List<Beat> beats = new ArrayList<>();
    doAnswer(
            call -> {
              beats.add(new Beat(TenantContext.require(), Set.copyOf(call.getArgument(0))));
              return 1;
            })
        .when(repository)
        .touchHeartbeat(anyCollection(), any(LocalDateTime.class));

    new ProactiveHeartbeatService(registry, repository).beat();

    assertThat(beats)
        .containsExactlyInAnyOrder(new Beat(1L, Set.of(11L, 12L)), new Beat(2L, Set.of(21L)));
    assertThat(TenantContext.get()).isNull();
  }

  @Test
  void sweeper_closesOnlyStaleExecutionsNotOwnedByThisJvm() {
    registry.register(11L, 1L);
    when(repository.findStaleExecutions(eq(1L), any(LocalDateTime.class)))
        .thenReturn(List.of(11L, 12L));
    when(repository.updateError(12L, ExecutionMessages.INTERRUPTED)).thenReturn(true);
    var sweeper =
        new ProactiveExecutionSweeper(
            repository, registry, mock(TenantScopedRunner.class), Duration.ofMinutes(5));

    assertThat(sweeper.sweepTenant(1L)).isEqualTo(1);

    verify(repository).updateError(12L, ExecutionMessages.INTERRUPTED);
    verify(repository, never()).updateError(11L, ExecutionMessages.INTERRUPTED);
  }

  @Test
  void heartbeat_emptyRegistry_doesNotQueryDatabase() {
    new ProactiveHeartbeatService(registry, repository).beat();
    verifyNoInteractions(repository);
  }

  /** 활성 실행이 있는 테넌트 목록(정지 워크스페이스 포함 — 실행 표에서 유도)만 돌며 고아 실행을 정리하고, 그 테넌트의 RLS 컨텍스트를 쓴다. */
  @Test
  void sweep_visitsTenantsWithActiveExecutions() {
    TenantRepository tenants = mock(TenantRepository.class);
    when(repository.tenantIdsWithActiveExecutions()).thenReturn(List.of(9L));
    doAnswer(
            call -> {
              assertThat(TenantContext.get()).isEqualTo(9L);
              return List.of(91L);
            })
        .when(repository)
        .findStaleExecutions(eq(9L), any(LocalDateTime.class));
    when(repository.updateError(91L, ExecutionMessages.INTERRUPTED)).thenReturn(true);
    var sweeper =
        new ProactiveExecutionSweeper(
            repository, registry, new TenantScopedRunner(tenants), Duration.ofMinutes(5));

    sweeper.sweep();

    verify(repository).updateError(91L, ExecutionMessages.INTERRUPTED);
    assertThat(TenantContext.get()).isNull();
  }
}
