package com.smartfirehub.dataset.rowsearch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.support.TenantScopedRunnerStubs;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 스윕: 테넌트마다 대상 데이터셋을 동기화하고, 한 데이터셋 실패가 나머지를 막지 않는다. */
@ExtendWith(MockitoExtension.class)
class RowSearchSweepSchedulerTest {

  @Mock TenantScopedRunner tenantRunner;
  @Mock SearchIndexStateRepository states;
  @Mock RowSearchSyncService syncService;

  @Test
  void sweep_syncsEveryDueDataset_evenIfOneThrows() {
    TenantScopedRunnerStubs.stubSingleTenantIteration(tenantRunner, 7L);
    when(states.findDueDatasetIds()).thenReturn(List.of(1L, 2L));
    when(syncService.sync(1L)).thenThrow(new IllegalStateException("x"));

    new RowSearchSweepScheduler(tenantRunner, states, syncService, true).sweep();

    verify(syncService).sync(2L);
  }

  @Test
  void sweep_disabled_doesNothing() {
    new RowSearchSweepScheduler(tenantRunner, states, syncService, false).sweep();
    verify(tenantRunner, never()).forEachActiveTenant(any());
  }
}
