package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.storage.DataRowStore;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.tenant.repository.TenantRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link StagingTableCleanupService} 의 외부 저장소 고아 staging 스윕 단위 테스트(WD-74 A8). 스프링 컨텍스트 없이 Mockito 로
 * 협력자를 가짜로 두고, 순회·격리는 실제 {@link TenantScopedRunner}, 백엔드 선택은 실제 {@link DataStoreResolver} 로 검증한다(새
 * 컨텍스트를 만들지 않기 위해 통합 테스트에 넣지 않는다).
 */
class StagingTableCleanupExternalSweepTest {

  /** 설정({@code app.data-store.staging-min-age})으로 받는 최소 나이 — 기본값과 다른 값을 넣어 그대로 전달되는지 본다. */
  private static final Duration MIN_AGE = Duration.ofMinutes(90);

  private TenantDataStoreRepository storeRepository;
  private DataRowStore pgRowStore;
  private DataRowStore tiberoRowStore;
  private PipelineExecutionRepository executionRepository;
  private StagingTableCleanupService service;

  @BeforeEach
  void setUp() {
    storeRepository = mock(TenantDataStoreRepository.class);
    pgRowStore = mock(DataRowStore.class);
    when(pgRowStore.backend()).thenReturn(DataStoreBackend.POSTGRESQL);
    tiberoRowStore = mock(DataRowStore.class);
    when(tiberoRowStore.backend()).thenReturn(DataStoreBackend.TIBERO);
    // MySQL 은 행 저장소 구현이 없다 — 스윕이 건너뛰어야 한다(예외 없이).
    DataStoreResolver resolver =
        new DataStoreResolver(List.of(), List.of(pgRowStore, tiberoRowStore), storeRepository);
    // 기본은 "실행 중인 파이프라인 없음" — 게이트 테스트만 테넌트별로 덮어쓴다.
    executionRepository = mock(PipelineExecutionRepository.class);
    service =
        new StagingTableCleanupService(
            mock(DSLContext.class),
            mock(DataTableRowService.class),
            new TenantScopedRunner(mock(TenantRepository.class)),
            mock(TransactionTemplate.class),
            storeRepository,
            resolver,
            executionRepository,
            MIN_AGE);
  }

  @Test
  void oneTenantFails_nextTenantStillSwept_withTenantContextSet() {
    when(storeRepository.findReadyTenantIds(DataStoreBackend.TIBERO)).thenReturn(List.of(7L, 8L));
    List<Long> contextSeen = new ArrayList<>();
    when(tiberoRowStore.dropStaleStagingTables(7L, MIN_AGE))
        .thenThrow(new IllegalStateException("tenant 7 down"));
    when(tiberoRowStore.dropStaleStagingTables(8L, MIN_AGE))
        .thenAnswer(
            inv -> {
              contextSeen.add(TenantContext.require("test"));
              return 2;
            });

    int dropped = service.sweepExternalTenants(false);

    assertThat(dropped).isEqualTo(2);
    verify(tiberoRowStore).dropStaleStagingTables(8L, MIN_AGE);
    // 순회 중에는 그 테넌트가 컨텍스트에 서 있다(PG 스윕과 같은 규약).
    assertThat(contextSeen).containsExactly(8L);
  }

  /**
   * 최종 리뷰 I1: Tibero 파이프라인 스텝은 import 와 같은 {@code STG_} 스테이징을 쓰고 JobRunr 게이트에 잡히지 않는다. RUNNING 실행이
   * 있는 테넌트는 이번 주기에 건너뛰고(스테이징을 지우지 않는다), 없는 테넌트는 그대로 정리한다. 재확인 플래그를 끈 호출로 검증해, 이 게이트가 JobRunr 재확인과
   * 묶이지 않고 늘 적용됨을 함께 고정한다.
   */
  @Test
  void tenantWithRunningPipeline_isSkipped_othersStillSwept() {
    when(storeRepository.findReadyTenantIds(DataStoreBackend.TIBERO)).thenReturn(List.of(7L, 8L));
    when(executionRepository.hasRunningExecution(7L)).thenReturn(true);
    when(executionRepository.hasRunningExecution(8L)).thenReturn(false);
    when(tiberoRowStore.dropStaleStagingTables(8L, MIN_AGE)).thenReturn(3);

    int dropped = service.sweepExternalTenants(false);

    assertThat(dropped).isEqualTo(3);
    verify(tiberoRowStore, never()).dropStaleStagingTables(7L, MIN_AGE);
    verify(tiberoRowStore).dropStaleStagingTables(8L, MIN_AGE);
  }

  @Test
  void postgresAndUnregisteredBackends_areNotSweptHere() {
    when(storeRepository.findReadyTenantIds(DataStoreBackend.TIBERO)).thenReturn(List.of());
    assertThat(service.sweepExternalTenants(false)).isZero();
    // PG 는 data 스키마 스윕이 맡고, 구현이 없는 MySQL 은 묻지도 않는다.
    verify(storeRepository, never()).findReadyTenantIds(DataStoreBackend.POSTGRESQL);
    verify(storeRepository, never()).findReadyTenantIds(DataStoreBackend.MYSQL);
    verify(pgRowStore, never()).dropStaleStagingTables(anyLong(), any());
  }

  @Test
  void noReadyTenants_touchesNothing() {
    when(storeRepository.findReadyTenantIds(DataStoreBackend.TIBERO)).thenReturn(List.of());
    assertThat(service.sweepExternalTenants(false)).isZero();
    verify(tiberoRowStore, never()).dropStaleStagingTables(anyLong(), any());
  }
}
