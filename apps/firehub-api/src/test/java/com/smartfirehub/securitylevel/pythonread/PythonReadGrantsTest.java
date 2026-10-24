package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PythonReadGrants 파사드(WD-78) — 백엔드 분기와 <b>PG 호출 불변</b>을 고정한다. PG 테넌트(와 컨텍스트 없음)는
 * PythonReadGrantSync 의 같은 메서드를 인자 그대로 정확히 한 번 부르고 Tibero 판은 건드리지 않는다. Tibero 테넌트는 반대다.
 */
class PythonReadGrantsTest {

  private final DataStoreResolver resolver = mock(DataStoreResolver.class);
  private final PythonReadGrantSync pg = mock(PythonReadGrantSync.class);
  private final TiberoPythonReadGrantSync tibero = mock(TiberoPythonReadGrantSync.class);
  private final PythonReadGrants grants = new PythonReadGrants(resolver, pg, tibero);
  private final Clearance runAs = new Clearance(7L, 5L, 2, Set.of(), false, Set.of());

  @BeforeEach
  void setUp() {
    TenantContext.set(5L);
    when(resolver.backendFor(5L)).thenReturn(DataStoreBackend.POSTGRESQL);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private void tiberoTenant() {
    when(resolver.backendFor(5L)).thenReturn(DataStoreBackend.TIBERO);
  }

  @Test
  void pgTenant_everyMethodGoesToPgSyncExactlyOnce() {
    when(pg.prepareForRun(runAs, 2)).thenReturn(2);

    assertThat(grants.prepareForRun(runAs, 2)).isEqualTo(2);
    grants.syncTenant();
    grants.syncTenantForLevelsChange();
    grants.syncDataset(42L);
    grants.syncTableAfterCommit("t1");
    when(pg.slotSessionsTerminatedSince(5L, 9L)).thenReturn(true);
    assertThat(grants.slotSessionsTerminatedSince(5L, 9L)).isTrue();

    verify(pg).prepareForRun(runAs, 2);
    verify(pg).syncTenant();
    verify(pg).syncTenantForLevelsChange();
    verify(pg).syncDataset(42L);
    verify(pg).syncTableAfterCommit("t1");
    verify(pg).slotSessionsTerminatedSince(5L, 9L);
    verifyNoMoreInteractions(pg);
    verifyNoInteractions(tibero);
  }

  @Test
  void tiberoTenant_everyMethodGoesToTiberoSyncExactlyOnce() {
    tiberoTenant();
    when(tibero.prepareForRun(runAs, 2)).thenReturn(2);

    assertThat(grants.prepareForRun(runAs, 2)).isEqualTo(2);
    grants.syncTenant();
    grants.syncTenantForLevelsChange();
    grants.syncDataset(42L);
    grants.syncTableAfterCommit("t1");

    verify(tibero).prepareForRun(runAs, 2);
    verify(tibero).syncTenant();
    verify(tibero).syncTenantForLevelsChange();
    verify(tibero).syncDataset(42L);
    verify(tibero).syncTableAfterCommit("t1");
    verifyNoMoreInteractions(tibero);
    verifyNoInteractions(pg);
  }

  /** Tibero 는 세션을 끊지 않으므로 "끊겼다" 문구 판정은 항상 false 이고 PG 구현은 부르지 않는다. */
  @Test
  void tiberoTenant_neverReportsTerminatedSessions() {
    tiberoTenant();
    assertThat(grants.slotSessionsTerminatedSince(5L, 0L)).isFalse();
    verifyNoInteractions(pg, tibero);
  }

  /** 테넌트 컨텍스트가 없으면 PG 구현이 예전 그대로(예외·로그) 처리하게 맡긴다 — Tibero 로 새지 않는다. */
  @Test
  void noTenantContext_fallsThroughToPg() {
    TenantContext.clear();

    grants.syncTableAfterCommit("t1");
    grants.syncTenant();

    verify(pg).syncTableAfterCommit("t1");
    verify(pg).syncTenant();
    verifyNoInteractions(tibero);
    verifyNoInteractions(resolver);
  }

  /** prepareForRun 의 예외는 가공 없이 그대로 전달된다. */
  @Test
  void prepareForRun_propagatesAccessException() {
    when(pg.prepareForRun(any(), anyInt())).thenThrow(new PythonReadAccessException("거부"));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> grants.prepareForRun(runAs, 2))
        .isInstanceOf(PythonReadAccessException.class)
        .hasMessage("거부");
  }

  /** 백엔드 조회가 실패하면 PG 로 새지 않는다: 실행 준비는 접근 거부(fail-closed), 쓰기 훅은 커밋을 막지 않고 건너뛴다. */
  @Test
  void backendLookupFailure_failsClosed_neverFallsBackToPg() {
    when(resolver.backendFor(5L)).thenThrow(new IllegalStateException("db down"));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> grants.prepareForRun(runAs, 2))
        .isInstanceOf(PythonReadAccessException.class);
    grants.syncTableAfterCommit("t1");

    verifyNoInteractions(pg, tibero);
  }

  /**
   * 코드리뷰 2: 실패 문구용 "슬롯 세션이 끊겼나" 판정은 백엔드 조회가 실패해도 예외를 내지 않는다 — 내면 러너의 실패 처리에서 원래 스크립트 오류를 가린다. 끊기지
   * 않은 것으로 보고, 백엔드를 모르므로 PG 구현으로 보내지도 않는다.
   */
  @Test
  void slotSessionsTerminatedSince_backendLookupFailure_returnsFalseWithoutMaskingError() {
    when(resolver.backendFor(5L)).thenThrow(new IllegalStateException("db down"));

    assertThat(grants.slotSessionsTerminatedSince(5L, 0L)).isFalse();

    verifyNoInteractions(pg, tibero);
  }
}
