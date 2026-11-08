package com.smartfirehub.analytics.service;

import static com.smartfirehub.analytics.service.AnalyticsServiceFixtures.RESULT;
import static com.smartfirehub.analytics.service.AnalyticsServiceFixtures.USER;
import static com.smartfirehub.analytics.service.AnalyticsServiceFixtures.chartFixture;
import static com.smartfirehub.analytics.service.AnalyticsServiceFixtures.dashFixture;
import static com.smartfirehub.analytics.service.AnalyticsServiceFixtures.schema;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.ChartDataResponse;
import com.smartfirehub.analytics.dto.DashboardDataResponse;
import com.smartfirehub.global.config.TransactionTemplateConfig;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TxRecordingInteractiveGate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 차트 데이터·대시보드 데이터의 트랜잭션 구간 검증(WD-207). Tibero 실행(최대 35초)은 PG 트랜잭션 밖에서, RLS 가 걸린 조회는 readOnly 트랜잭션
 * 안에서 해야 한다. 클래스에 @Transactional 을 붙이지 않고 실제 트랜잭션 매니저를 써서, mock 이 호출 시점의 트랜잭션 여부를 기록한다. 허가를 얻지 않아야
 * 하는 경우(거부·전부 캐시)는 InteractiveGatePermitPlacementTest 가 본다.
 */
class ChartDashboardTxTest extends IntegrationTestBase {

  @Autowired private PlatformTransactionManager transactionManager;

  /** 호출 시점의 (트랜잭션 활성, readOnly) 를 기록하는 훅. */
  private static Runnable record(List<Boolean> active, List<Boolean> readOnly) {
    return () -> {
      active.add(TransactionSynchronizationManager.isActualTransactionActive());
      readOnly.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    };
  }

  /** 조회(read)·실행(exec) 시점의 트랜잭션 상태와 허가 시점의 트랜잭션 여부(gateTx). */
  private record Recorded(
      List<Boolean> readTx,
      List<Boolean> readOnly,
      List<Boolean> execTx,
      List<Boolean> execReadOnly,
      TxRecordingInteractiveGate gate) {
    Recorded() {
      this(
          new ArrayList<>(),
          new ArrayList<>(),
          new ArrayList<>(),
          new ArrayList<>(),
          new TxRecordingInteractiveGate());
    }

    List<Boolean> gateTx() {
      return gate.acquireTx;
    }
  }

  // ---------------------------------------------------------------- 차트

  private ChartService chartService(boolean tibero, Recorded r) {
    return chartFixture(
            TransactionTemplateConfig.readOnly(transactionManager),
            schema(tibero),
            r.gate(),
            false,
            record(r.readTx(), r.readOnly()),
            record(r.execTx(), r.execReadOnly()))
        .service();
  }

  /** Tibero: 차트·SQL 조회는 readOnly 트랜잭션 안, 실행은 트랜잭션 밖, 허가 없음. */
  @Test
  void chart_tibero_executesOutsideTx() {
    Recorded r = new Recorded();

    ChartDataResponse res = chartService(true, r).getChartData(1L, USER);

    assertThat(res.queryResult()).isEqualTo(RESULT);
    assertThat(r.readTx()).containsExactly(true, true);
    assertThat(r.readOnly()).containsExactly(true, true);
    assertThat(r.execTx()).containsExactly(false);
    // WD-599: Tibero 는 PG 풀 커넥션을 쥐지 않으므로 동시 실행 허가를 얻지 않는다.
    assertThat(r.gateTx()).isEmpty();
  }

  /** PG: 조회는 readOnly 트랜잭션 안, 허가는 트랜잭션 밖, 실행은 허가 뒤 연 readOnly 트랜잭션 안(WD-625). */
  @Test
  void chart_pg_permitOutsideTx_executesInsideReadOnlyTx() {
    Recorded r = new Recorded();

    chartService(false, r).getChartData(1L, USER);

    assertThat(r.readTx()).containsExactly(true, true);
    assertThat(r.readOnly()).containsExactly(true, true);
    assertThat(r.gateTx()).containsExactly(false);
    assertThat(r.execTx()).containsExactly(true);
    assertThat(r.execReadOnly()).containsExactly(true);
  }

  // ------------------------------------------------------------- 대시보드

  private AnalyticsDashboardService dashService(boolean tibero, Set<Long> denied, Recorded r) {
    return dashFixture(
            TransactionTemplateConfig.readOnly(transactionManager),
            schema(tibero),
            r.gate(),
            denied,
            record(r.readTx(), r.readOnly()),
            record(r.execTx(), r.execReadOnly()))
        .service();
  }

  /** Tibero: 저장 쿼리 2개 실행이 모두 트랜잭션 밖, 모든 조회는 readOnly 트랜잭션 안, 허가 없음. */
  @Test
  void dashboard_tibero_twoWidgets_allExecutionsOutsideTx() {
    Recorded r = new Recorded();

    DashboardDataResponse res = dashService(true, Set.of(), r).getDashboardData(1L, USER);

    assertThat(res.widgetData()).hasSize(2);
    assertThat(r.execTx()).containsExactly(false, false);
    assertThat(r.gateTx()).isEmpty();
    assertThat(r.readTx()).isNotEmpty().containsOnly(true);
    assertThat(r.readOnly()).containsOnly(true);
  }

  /** Tibero: 거부된 저장 쿼리의 위젯만 denied 이고 다른 위젯은 결과를 가진다(기존 계약). */
  @Test
  void dashboard_tibero_deniedWidget_stillReturns() {
    Recorded r = new Recorded();

    DashboardDataResponse res = dashService(true, Set.of(300L), r).getDashboardData(1L, USER);

    assertThat(res.widgetData()).hasSize(2);
    ChartDataResponse denied = res.widgetData().get(0).chartData();
    ChartDataResponse ok = res.widgetData().get(1).chartData();
    assertThat(denied.denied()).isTrue();
    assertThat(ok.denied()).isFalse();
    assertThat(ok.queryResult()).isEqualTo(RESULT);
    assertThat(r.execTx()).containsExactly(false);
  }

  /**
   * PG: 조회는 readOnly 트랜잭션 안. 캐시 미스(저장 쿼리 2개)마다 허가를 트랜잭션 밖에서 하나씩 얻고, 실행은 그 뒤 연 readOnly 트랜잭션
   * 안(WD-625).
   */
  @Test
  void dashboard_pg_permitPerMissOutsideTx_executesInsideReadOnlyTx() {
    Recorded r = new Recorded();

    dashService(false, Set.of(), r).getDashboardData(1L, USER);

    assertThat(r.readTx()).isNotEmpty().containsOnly(true);
    assertThat(r.readOnly()).containsOnly(true);
    assertThat(r.gateTx()).containsExactly(false, false);
    assertThat(r.execTx()).containsExactly(true, true);
    assertThat(r.execReadOnly()).containsExactly(true, true);
  }
}
