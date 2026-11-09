package com.smartfirehub.analytics.service;

import static com.smartfirehub.analytics.service.AnalyticsServiceFixtures.NONE;
import static com.smartfirehub.analytics.service.AnalyticsServiceFixtures.RESULT;
import static com.smartfirehub.analytics.service.AnalyticsServiceFixtures.USER;
import static com.smartfirehub.analytics.service.AnalyticsServiceFixtures.chartFixture;
import static com.smartfirehub.analytics.service.AnalyticsServiceFixtures.dashFixture;
import static com.smartfirehub.analytics.service.AnalyticsServiceFixtures.schema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.analytics.dto.ChartDataResponse;
import com.smartfirehub.analytics.dto.DashboardDataResponse;
import com.smartfirehub.analytics.exception.ChartNotFoundException;
import com.smartfirehub.analytics.exception.DashboardNotFoundException;
import com.smartfirehub.analytics.service.AnalyticsServiceFixtures.ChartFixture;
import com.smartfirehub.analytics.service.AnalyticsServiceFixtures.DashFixture;
import com.smartfirehub.global.config.TransactionTemplateConfig;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import com.smartfirehub.support.TxRecordingInteractiveGate;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 화면 쿼리 허가를 「실제로 쿼리를 실행한다」가 정해진 뒤에만 얻는지(WD-625).
 *
 * <p>게이트를 다른 사용자가 꽉 채운 상태(전체 상한 1 을 사용자 99 가 쥠)에서, 쿼리를 실행하지 않고 끝나는 요청 — 없는 차트(404)·거부 판정 차트(200
 * denied)·없는 대시보드(404)·모두 거부이거나 모두 캐시된 대시보드(200) — 은 허가를 얻으려 하지도 않고(acquire 호출 없음·대기열 비어 있음) 결과를
 * 돌려줘야 한다. 진입부에서 허가를 얻던 코드는 대기 시간(30초) 동안 줄 서다 429 를 받는다. 실행이 필요한 요청은 지금처럼 기다렸다 429 다.
 *
 * <p>스프링 없이 서비스를 직접 만든다(PG 경로, 가짜 트랜잭션 매니저). 트랜잭션 구간은 ChartDashboardTxTest 가 본다.
 */
class InteractiveGatePermitPlacementTest {

  /**
   * 빠른 경로 테스트의 대기 시간 — 크게 잡아, 허가를 얻으려 했다면 결과 대신 긴 대기 뒤 429 가 되게 한다. 판정은 시간이 아니라 결과·acquire 호출 수로
   * 한다.
   */
  private static final long LONG_WAIT_MS = 30_000;

  /** 실행이 필요한 요청이 여전히 기다렸다 429 인지 볼 때의 짧은 대기 시간. */
  private static final long SHORT_WAIT_MS = 300;

  /** 게이트를 채우고 있는 다른 사용자. */
  private static final long OTHER_USER = 99L;

  /** 가짜 트랜잭션 매니저 위의 readOnly 템플릿 — 구간 자체는 이 테스트의 관심사가 아니다. */
  private static TransactionTemplate tx() {
    return TransactionTemplateConfig.readOnly(mock(PlatformTransactionManager.class));
  }

  /** 전체 상한 1·사용자 상한 3 의 기록 게이트. */
  private static TxRecordingInteractiveGate gate(long waitMs) {
    return new TxRecordingInteractiveGate(1, 3, waitMs);
  }

  private static ChartFixture chart(TxRecordingInteractiveGate gate, boolean denied) {
    return chartFixture(tx(), schema(false), gate, denied, NONE, NONE);
  }

  private static DashFixture dash(TxRecordingInteractiveGate gate, Set<Long> denied) {
    return dashFixture(tx(), schema(false), gate, denied, NONE, NONE);
  }

  /** 빠른 경로 확인 — before 이후 허가를 얻으려 하지 않았고(acquire 호출 수 불변) 아무도 줄에 서 있지 않다. */
  private static void assertNoPermitAttempt(TxRecordingInteractiveGate gate, int before) {
    assertThat(gate.acquireTx).as("허가를 얻으려 하지 않는다").hasSize(before);
    assertThat(waiting(gate)).isZero();
  }

  // 게이트의 진단용 메서드는 패키지 가시라 다른 패키지(support)의 하위 클래스 타입으로는 보이지 않는다 — 게이트 타입으로 부른다.
  private static int active(InteractiveQueryConcurrencyGate g) {
    return g.activeTotal();
  }

  private static int waiting(InteractiveQueryConcurrencyGate g) {
    return g.waitingCount();
  }

  private static Set<Long> tracked(InteractiveQueryConcurrencyGate g) {
    return g.trackedUsers();
  }

  private static void assertConcurrencyLimit(Throwable e) {
    assertThat(e).isInstanceOf(CodedApiException.class);
    CodedApiException c = (CodedApiException) e;
    assertThat(c.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(c.code()).isEqualTo(AnalyticsQueryConcurrencyGate.LIMIT_CODE);
  }

  // ---------------------------------------------------------------- 차트

  /** 없는 차트 — 게이트가 차 있어도 허가 없이 곧바로 404. */
  @Test
  void missingChart_gateFull_404WithoutPermit() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    ChartFixture f = chart(gate, false);
    when(f.repo().findById(anyLong(), anyLong())).thenReturn(Optional.empty());
    try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
      int before = gate.acquireTx.size();

      Throwable e = catchThrowable(() -> f.service().getChartData(404L, USER));

      assertThat(e).isInstanceOf(ChartNotFoundException.class);
      assertNoPermitAttempt(gate, before);
    }
    assertThat(active(gate)).isZero();
  }

  /** 거부 판정 차트 — 실행하지 않으므로 허가 없이 곧바로 200 denied. */
  @Test
  void deniedChart_gateFull_deniedWithoutPermit() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    ChartFixture f = chart(gate, true);
    try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
      int before = gate.acquireTx.size();

      ChartDataResponse r = f.service().getChartData(1L, USER);

      assertThat(r.denied()).isTrue();
      // denied 응답은 config 를 지운다(기존 계약 그대로).
      assertThat(r.chart().config()).isEmpty();
      assertNoPermitAttempt(gate, before);
    }
    verify(f.executor(), never()).executeJudgedAnalytics(any(), anyInt(), anyBoolean());
  }

  /** 실행이 필요한 차트 — 지금처럼 차례를 기다렸다 429, 실행하지 않는다. */
  @Test
  void executableChart_gateFull_stillWaitsThen429() {
    TxRecordingInteractiveGate gate = gate(SHORT_WAIT_MS);
    ChartFixture f = chart(gate, false);
    try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
      Throwable e = catchThrowable(() -> f.service().getChartData(1L, USER));

      assertConcurrencyLimit(e);
    }
    verify(f.executor(), never()).executeJudgedAnalytics(any(), anyInt(), anyBoolean());
    assertThat(active(gate)).isZero();
  }

  /** 실행이 필요한 차트 — 자리가 있으면 허가 하나로 실행하고 반납한다. */
  @Test
  void executableChart_gateFree_executesAndReleases() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    ChartFixture f = chart(gate, false);

    ChartDataResponse r = f.service().getChartData(1L, USER);

    assertThat(r.denied()).isFalse();
    assertThat(r.queryResult()).isEqualTo(RESULT);
    assertThat(gate.acquireTx).hasSize(1);
    verify(f.executor(), times(1)).executeJudgedAnalytics(any(), anyInt(), anyBoolean());
    assertThat(active(gate)).isZero();
    assertThat(tracked(gate)).isEmpty();
  }

  // ------------------------------------------------------------- 대시보드

  /** 없는 대시보드 — 게이트가 차 있어도 허가 없이 곧바로 404. */
  @Test
  void missingDashboard_gateFull_404WithoutPermit() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    DashFixture f = dash(gate, Set.of());
    when(f.dashRepo().findById(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
    try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
      int before = gate.acquireTx.size();

      Throwable e = catchThrowable(() -> f.service().getDashboardData(404L, USER));

      assertThat(e).isInstanceOf(DashboardNotFoundException.class);
      assertNoPermitAttempt(gate, before);
    }
  }

  /** 위젯 결과가 모두 캐시에 있는 대시보드 — 실행할 쿼리가 없으므로 허가 없이 200, 캐시 히트 감사는 조회자별로 히트마다 한 번 남긴다. */
  @Test
  void fullyCachedDashboard_gateFull_200WithoutPermit() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    DashFixture f = dash(gate, Set.of());
    // 자리가 있을 때 한 번 불러 두 저장 쿼리 결과를 캐시에 채운다(미스마다 허가 하나, 이 조회자가 로더를 돌렸으므로 히트 감사는 없다).
    f.service().getDashboardData(1L, USER);
    assertThat(gate.acquireTx).hasSize(2);
    verify(f.chartService(), times(2)).executeJudged(any());
    verify(f.guarded(), never()).recordDelivered(any());
    assertThat(active(gate)).isZero();

    try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
      int before = gate.acquireTx.size();

      DashboardDataResponse r = f.service().getDashboardData(1L, USER);

      assertThat(r.widgetData())
          .hasSize(2)
          .allSatisfy(w -> assertThat(w.chartData().queryResult()).isEqualTo(RESULT));
      assertNoPermitAttempt(gate, before);
    }
    // 다시 실행하지 않았고, 캐시에서 받은 두 결과마다 조회자별 감사 등급 접근을 한 번씩 남겼다(감사 의미 불변).
    verify(f.chartService(), times(2)).executeJudged(any());
    verify(f.guarded(), times(1)).recordDelivered(f.j300());
    verify(f.guarded(), times(1)).recordDelivered(f.j301());
  }

  /** 모든 위젯이 거부 판정인 대시보드 — 실행하지 않으므로 허가 없이 200(위젯 denied). */
  @Test
  void allDeniedDashboard_gateFull_200WithoutPermit() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    DashFixture f = dash(gate, Set.of(300L, 301L));
    try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
      int before = gate.acquireTx.size();

      DashboardDataResponse r = f.service().getDashboardData(1L, USER);

      assertThat(r.widgetData())
          .hasSize(2)
          .allSatisfy(w -> assertThat(w.chartData().denied()).isTrue());
      assertNoPermitAttempt(gate, before);
    }
    verify(f.chartService(), never()).executeJudged(any());
  }

  /** 캐시 미스가 있는 대시보드 — 로더가 허가를 기다렸다 429 를 그대로(감싸지 않고) 던지고, 실행하지 않는다. */
  @Test
  void dashboardWithCacheMiss_gateFull_stillWaitsThen429() {
    TxRecordingInteractiveGate gate = gate(SHORT_WAIT_MS);
    DashFixture f = dash(gate, Set.of());
    try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
      int before = gate.acquireTx.size();
      Throwable e = catchThrowable(() -> f.service().getDashboardData(1L, USER));

      // 실제 결과가 하나도 없으니(미스 2, 히트 0) 지금처럼 429 다(WD-676).
      assertConcurrencyLimit(e);
      // 빨리 끝내기(WD-676) — 첫 미스만 허가를 기다리고 두 번째 미스는 다시 기다리지 않는다.
      assertThat(gate.acquireTx).hasSize(before + 1);
    }
    verify(f.chartService(), never()).executeJudged(any());
    assertThat(active(gate)).isZero();
    // 429 로 끝난 로드는 캐시에 남지 않는다 — 자리가 나면 다시 실행한다.
    f.service().getDashboardData(1L, USER);
    verify(f.chartService(), times(2)).executeJudged(any());
  }

  /**
   * 일부만 캐시된 대시보드 — 히트는 다시 실행하지 않고 그대로 쓰며(조회자별 감사 한 번), 미스만 허가 하나로 실행한다. 이 조회자가 직접 돌린 미스 결과는 실행 지점이
   * 감사하므로 recordDelivered 를 따로 남기지 않는다.
   */
  @Test
  void partiallyCachedDashboard_executesOnlyMisses_andAuditsEachHitOnce() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    DashFixture f = dash(gate, Set.of());
    // 두 결과를 캐시에 채운다(로더 2회).
    f.service().getDashboardData(1L, USER);
    verify(f.chartService(), times(2)).executeJudged(any());
    // 저장 쿼리 301 의 SQL 이 바뀌었다 — 캐시 키(판정한 SQL 원문)가 달라 미스, 300 은 그대로 히트.
    when(f.chartRepo().findSavedQuerySqlTextById(301L)).thenReturn(Optional.of("SELECT 301 v2"));
    int before = gate.acquireTx.size();

    DashboardDataResponse r = f.service().getDashboardData(1L, USER);

    assertThat(r.widgetData())
        .hasSize(2)
        .allSatisfy(w -> assertThat(w.chartData().queryResult()).isEqualTo(RESULT));
    // 미스 하나만 실행했고, 그 실행에 허가를 한 번 얻었다.
    verify(f.chartService(), times(3)).executeJudged(any());
    verify(f.chartService(), times(2)).executeJudged(f.j301());
    assertThat(gate.acquireTx).hasSize(before + 1);
    assertThat(active(gate)).isZero();
    // 히트(300)는 조회자별로 정확히 한 번 감사, 직접 실행한 미스(301)는 따로 남기지 않는다.
    verify(f.guarded(), times(1)).recordDelivered(f.j300());
    verify(f.guarded(), never()).recordDelivered(f.j301());
  }

  // ---------------------------------------------------------------- WD-676: 부분 결과

  /** 저장 쿼리 {@code id} 의 SQL 을 바꿔 캐시 키를 달리한다 — 그 쿼리만 미스가 된다. */
  private static void changeSql(DashFixture f, long savedQueryId) {
    when(f.chartRepo().findSavedQuerySqlTextById(savedQueryId))
        .thenReturn(Optional.of("SELECT " + savedQueryId + " v2"));
  }

  /** 차트 id 의 위젯 결과. */
  private static ChartDataResponse widgetOf(DashboardDataResponse r, long chartId) {
    return r.widgetData().stream()
        .map(DashboardDataResponse.WidgetData::chartData)
        .filter(c -> c.chart().id() == chartId)
        .findFirst()
        .orElseThrow();
  }

  private static void assertLimitedWidget(ChartDataResponse c) {
    assertLimitedWidget(c, InteractiveQueryConcurrencyGate.TIMEOUT_MESSAGE);
  }

  /** 상한에 걸린 위젯 — 오류 문구는 받은 상한 예외의 문구 그대로다(WD-676). */
  private static void assertLimitedWidget(ChartDataResponse c, String message) {
    assertThat(c.denied()).isFalse();
    assertThat(c.queryResult().error()).isEqualTo(message);
    assertThat(c.queryResult().rows()).isEmpty();
    assertThat(c.exportAllowed()).isFalse();
  }

  /**
   * 일부만 캐시된 대시보드 + 게이트 가득 참 — 응답 전체를 429 로 버리지 않고 200 이다. 히트 위젯은 결과 그대로, 미스 위젯만 대기 초과 오류다. 미스는 실행하지
   * 않았고 허가 시도는 한 번이며 남은 허가는 없다.
   */
  @Test
  void partiallyCachedDashboard_gateFull_200WithLimitedWidgetOnly() {
    TxRecordingInteractiveGate gate = gate(SHORT_WAIT_MS);
    DashFixture f = dash(gate, Set.of());
    f.service().getDashboardData(1L, USER);
    verify(f.chartService(), times(2)).executeJudged(any());
    changeSql(f, 301L);

    DashboardDataResponse r;
    try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
      int before = gate.acquireTx.size();

      r = f.service().getDashboardData(1L, USER);

      assertThat(gate.acquireTx).hasSize(before + 1);
    }
    assertThat(widgetOf(r, 100L).queryResult()).isEqualTo(RESULT);
    assertLimitedWidget(widgetOf(r, 101L));
    verify(f.chartService(), times(2)).executeJudged(any());
    assertThat(active(gate)).isZero();
  }

  /**
   * 히트 1 + 미스 2 + 게이트 가득 참 — 첫 미스만 허가를 기다리고(+1) 두 번째 미스는 곧바로 대기 초과로 표시한다(빨리 끝내기). 자리가 나면 대기 초과였던
   * 쿼리를 다시 실행한다(캐시에 남지 않았다).
   */
  @Test
  void oneHitTwoMisses_gateFull_fastFailsAfterFirstLimit_thenReexecutes() {
    TxRecordingInteractiveGate gate = gate(SHORT_WAIT_MS);
    DashFixture f = dashFixture(tx(), schema(false), gate, Set.of(), NONE, NONE, 3);
    f.service().getDashboardData(1L, USER);
    verify(f.chartService(), times(3)).executeJudged(any());
    changeSql(f, 301L);
    changeSql(f, 302L);

    DashboardDataResponse r;
    try (AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(OTHER_USER)) {
      int before = gate.acquireTx.size();

      r = f.service().getDashboardData(1L, USER);

      assertThat(gate.acquireTx).as("첫 429 뒤 남은 미스는 허가를 다시 기다리지 않는다").hasSize(before + 1);
    }
    assertThat(widgetOf(r, 100L).queryResult()).isEqualTo(RESULT);
    assertLimitedWidget(widgetOf(r, 101L));
    assertLimitedWidget(widgetOf(r, 102L));
    verify(f.chartService(), times(3)).executeJudged(any());
    assertThat(active(gate)).isZero();

    // 자리가 났다 — 대기 초과였던 두 쿼리는 캐시에 없으므로 다시 실행한다.
    DashboardDataResponse again = f.service().getDashboardData(1L, USER);
    verify(f.chartService(), times(5)).executeJudged(any());
    assertThat(again.widgetData())
        .hasSize(3)
        .allSatisfy(w -> assertThat(w.chartData().queryResult()).isEqualTo(RESULT));
  }

  /**
   * 같은 코드(QUERY_CONCURRENCY_LIMIT)의 WD-553 즉시 거절(사용자 상한)이 실행 안에서 나면, 위젯 오류는 고정 대기 초과 문구가 아니라 그 예외의
   * 문구다. 다른 위젯은 캐시 히트라 응답은 200 이다.
   */
  @Test
  void immediateLimitInsideExecution_widgetCarriesThatMessage() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    DashFixture f = dash(gate, Set.of());
    f.service().getDashboardData(1L, USER);
    changeSql(f, 301L);
    when(f.chartService().executeJudged(any()))
        .thenThrow(
            new CodedApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                AnalyticsQueryConcurrencyGate.LIMIT_CODE,
                AnalyticsQueryConcurrencyGate.USER_LIMIT_MESSAGE));

    DashboardDataResponse r = f.service().getDashboardData(1L, USER);

    assertThat(widgetOf(r, 100L).queryResult()).isEqualTo(RESULT);
    assertLimitedWidget(widgetOf(r, 101L), AnalyticsQueryConcurrencyGate.USER_LIMIT_MESSAGE);
    assertThat(active(gate)).isZero();
  }

  // ---------------------------------------------------------------- WD-684: 실행 직전 재판정

  /** PG 차트 — 허가 구간 안에서 한 번 더 판정한다(앞 판정 + 재판정 = 2회). 재판정 거부면 실행하지 않고 200 denied. */
  @Test
  void pgChart_rejudgesInsidePermit_deniedRejudgmentNotExecuted() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    ChartFixture f = chart(gate, false);
    GuardedSqlExecutor.AnalyticsJudgment denied = AnalyticsServiceFixtures.judgment(true);
    GuardedSqlExecutor.AnalyticsJudgment allowed = AnalyticsServiceFixtures.judgment(false);
    when(f.executor().judgeAnalytics(any(), anyString())).thenReturn(allowed, denied);

    ChartDataResponse r = f.service().getChartData(1L, USER);

    assertThat(r.denied()).isTrue();
    assertThat(gate.acquireTx).hasSize(1);
    verify(f.executor(), times(2)).judgeAnalytics(any(), anyString());
    verify(f.executor(), never()).executeJudgedAnalytics(any(), anyInt(), anyBoolean());
    assertThat(active(gate)).isZero();
  }

  /** Tibero 차트·대시보드 — 허가를 기다리지 않으므로(게이트가 지나지 않음) 재판정 없이 앞 판정 토큰을 한 번만 판정해 실행한다. */
  @Test
  void tibero_noPermitWait_judgesOnce() {
    TxRecordingInteractiveGate gate = gate(LONG_WAIT_MS);
    ChartFixture c = chartFixture(tx(), schema(true), gate, false, NONE, NONE);
    c.service().getChartData(1L, USER);
    verify(c.executor(), times(1)).judgeAnalytics(any(), anyString());
    verify(c.executor(), times(1)).executeJudgedAnalytics(any(), anyInt(), anyBoolean());

    DashFixture d = dashFixture(tx(), schema(true), gate, Set.of(), NONE, NONE);
    d.service().getDashboardData(1L, USER);
    verify(d.chartService(), times(2)).judge(any(), anyString());
    verify(d.chartService(), times(2)).executeJudged(any());
    assertThat(gate.acquireTx).isEmpty();
  }
}
