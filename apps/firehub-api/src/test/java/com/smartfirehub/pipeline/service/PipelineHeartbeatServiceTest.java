package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 하트비트 서비스(WD-230) 단위 테스트 — 테넌트별 컨텍스트에서 갱신하는지, 한 테넌트 실패가 다른 테넌트를 막지 않는지. */
class PipelineHeartbeatServiceTest {

  private final PipelineExecutionRegistry registry = new PipelineExecutionRegistry();
  private final PipelineExecutionRepository repository = mock(PipelineExecutionRepository.class);
  private final PipelineHeartbeatService service =
      new PipelineHeartbeatService(registry, repository);

  /** touchHeartbeat 호출 당시의 (테넌트 컨텍스트, id 집합) 기록. */
  private record Call(Long tenant, Set<Long> ids) {}

  @Test
  void beat_touchesEachTenantsExecutionsInsideThatTenantContext() {
    registry.register(1L, 10L);
    registry.register(2L, 10L);
    registry.register(3L, 20L);
    List<Call> calls = new ArrayList<>();
    doAnswer(
            inv -> {
              calls.add(new Call(TenantContext.get(), Set.copyOf(inv.getArgument(0))));
              return 1;
            })
        .when(repository)
        .touchHeartbeat(anyCollection(), any(LocalDateTime.class));

    service.beat();

    // 테넌트를 섞어 한 번에 부르면 RLS 로 조용히 0행이 갱신된다 — 테넌트당 한 번, 그 테넌트 컨텍스트 안에서.
    assertThat(calls)
        .containsExactlyInAnyOrder(new Call(10L, Set.of(1L, 2L)), new Call(20L, Set.of(3L)));
    assertThat(TenantContext.get()).isNull();
  }

  @Test
  void beat_failureInOneTenant_doesNotStopTheOthers() {
    registry.register(1L, 10L);
    registry.register(3L, 20L);
    List<Long> touchedTenants = new ArrayList<>();
    doAnswer(
            inv -> {
              touchedTenants.add(TenantContext.get());
              if (TenantContext.get() == 10L) {
                throw new IllegalStateException("tenant 10 db error");
              }
              return 1;
            })
        .when(repository)
        .touchHeartbeat(anyCollection(), any(LocalDateTime.class));

    service.beat();

    assertThat(touchedTenants).containsExactlyInAnyOrder(10L, 20L);
  }

  @Test
  void beat_emptyRegistry_doesNotTouchDb() {
    service.beat();
    verifyNoInteractions(repository);
  }
}
