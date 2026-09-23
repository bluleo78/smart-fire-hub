package com.smartfirehub.dataset.rowsearch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.rowsearch.RowSearchSyncService.Outcome;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.support.TenantScopedRunnerStubs;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 스윕: 테넌트마다 대상 데이터셋을 동기화하고, 한 데이터셋 실패가 나머지를 막지 않는다. 남은 행이 있으면(PARTIAL)
 * 주기 대기 없이 연속 상한 안에서 다음 라운드를 바로 돈다(#715).
 */
@ExtendWith(MockitoExtension.class)
class RowSearchSweepSchedulerTest {

  @Mock TenantScopedRunner tenantRunner;
  @Mock SearchIndexStateRepository states;
  @Mock RowSearchSyncService syncService;

  /** 테스트가 상한에 걸리지 않을 만큼 넉넉한 연속 실행 상한. */
  private static final long CAP_MS = 60_000;

  @Test
  void sweep_syncsEveryDueDataset_evenIfOneThrows() {
    TenantScopedRunnerStubs.stubSingleTenantIteration(tenantRunner, 7L);
    when(states.findDueDatasetIds()).thenReturn(List.of(1L, 2L));
    when(syncService.sync(1L)).thenThrow(new IllegalStateException("x"));

    new RowSearchSweepScheduler(tenantRunner, states, syncService, true, CAP_MS).sweep();

    verify(syncService).sync(2L);
  }

  @Test
  void sweep_disabled_doesNothing() {
    new RowSearchSweepScheduler(tenantRunner, states, syncService, false, CAP_MS).sweep();
    verify(tenantRunner, never()).forEachActiveTenant(any());
  }

  @Test
  void sweep_partial_runsNextRoundImmediately_untilCompleted() {
    TenantScopedRunnerStubs.stubSingleTenantIteration(tenantRunner, 7L);
    when(states.findDueDatasetIds()).thenReturn(List.of(1L));
    when(syncService.sync(1L)).thenReturn(Outcome.PARTIAL, Outcome.PARTIAL, Outcome.COMPLETED);

    new RowSearchSweepScheduler(tenantRunner, states, syncService, true, CAP_MS).sweep();

    // 한 번의 스윕 안에서 남은 행이 없어질 때까지 이어 처리한다(60초 대기 없음).
    verify(syncService, times(3)).sync(1L);
    // 라운드마다 대상 목록을 다시 조회해 그 사이 켜진 데이터셋도 순번에 끼운다.
    verify(states, times(3)).findDueDatasetIds();
  }

  @Test
  void sweep_allCompleted_runsSingleRound() {
    TenantScopedRunnerStubs.stubSingleTenantIteration(tenantRunner, 7L);
    when(states.findDueDatasetIds()).thenReturn(List.of(1L, 2L));
    when(syncService.sync(1L)).thenReturn(Outcome.COMPLETED);
    when(syncService.sync(2L)).thenReturn(Outcome.COMPLETED);

    new RowSearchSweepScheduler(tenantRunner, states, syncService, true, CAP_MS).sweep();

    verify(syncService, times(1)).sync(1L);
    verify(syncService, times(1)).sync(2L);
  }

  @Test
  void sweep_skippedOrFailed_doesNotLoop() {
    // SKIPPED(다른 인스턴스가 임대 중)·FAILED(백오프)·예외로 곧장 다시 돌면 헛돌기만 한다.
    TenantScopedRunnerStubs.stubSingleTenantIteration(tenantRunner, 7L);
    when(states.findDueDatasetIds()).thenReturn(List.of(1L, 2L, 3L));
    when(syncService.sync(1L)).thenReturn(Outcome.SKIPPED);
    when(syncService.sync(2L)).thenReturn(Outcome.FAILED);
    when(syncService.sync(3L)).thenThrow(new IllegalStateException("x"));

    new RowSearchSweepScheduler(tenantRunner, states, syncService, true, CAP_MS).sweep();

    verify(states, times(1)).findDueDatasetIds();
  }

  @Test
  void sweep_partial_stopsAtContinuousCap() {
    // 상한 0 이면 PARTIAL 이어도 한 라운드로 끝나고 다음 주기로 넘긴다 — 스케줄러 스레드 독점 방지.
    TenantScopedRunnerStubs.stubSingleTenantIteration(tenantRunner, 7L);
    when(states.findDueDatasetIds()).thenReturn(List.of(1L));
    when(syncService.sync(1L)).thenReturn(Outcome.PARTIAL);

    new RowSearchSweepScheduler(tenantRunner, states, syncService, true, 0).sweep();

    verify(syncService, times(1)).sync(1L);
  }

  @Test
  void sweep_continuousRounds_skipDatasetsAlreadySettledInThisSweep() {
    // 1 은 첫 라운드에 완료, 2 는 한 번 더 남음 — 연장 라운드에서 완료된 1 을 다시 돌지 않는다
    // (변경이 없어도 COUNT·삭제분 정리 스캔을 반복하는 낭비).
    TenantScopedRunnerStubs.stubSingleTenantIteration(tenantRunner, 7L);
    when(states.findDueDatasetIds()).thenReturn(List.of(1L, 2L));
    when(syncService.sync(1L)).thenReturn(Outcome.COMPLETED);
    when(syncService.sync(2L)).thenReturn(Outcome.PARTIAL, Outcome.COMPLETED);

    new RowSearchSweepScheduler(tenantRunner, states, syncService, true, CAP_MS).sweep();

    verify(syncService, times(1)).sync(1L);
    verify(syncService, times(2)).sync(2L);
  }
}
