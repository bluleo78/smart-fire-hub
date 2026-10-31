package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * ChartService 추가 통합 테스트. 기존 ChartServiceTest에서 커버되지 않은 분기: - executeQueryForCache(null) — null SQL
 * → 빈 응답 반환 - executeQueryForCache("") — blank SQL → 빈 응답 반환 - getChartData: SavedQuerySqlText not
 * found (L123)
 */
@Transactional
class ChartServiceExtTest extends IntegrationTestBase {

  @Autowired private ChartService chartService;
  @Autowired private SavedQueryService savedQueryService;
  @Autowired private DSLContext dsl;
  @Autowired private ClearanceResolver clearanceResolver;

  private Long ownerUserId;
  private Long savedQueryId;

  @BeforeEach
  void setUp() {
    ownerUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(
                DSL.field(DSL.name("user", "username"), String.class),
                "chart_ext_owner_" + System.nanoTime())
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Chart Ext Owner")
            .set(
                DSL.field(DSL.name("user", "email"), String.class),
                "chart_ext_" + System.nanoTime() + "@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    SavedQueryResponse sq =
        savedQueryService.create(
            new CreateSavedQueryRequest("Ext SQ", null, "SELECT 1 AS n", null, null, false),
            ownerUserId);
    savedQueryId = sq.id();
  }

  /** 캐시 채움은 조회자 자격으로 실행된다(보안 등급 S2) — 테이블을 참조하지 않는 SQL 이라 자격 수준과 무관하게 허용된다. */
  private Clearance viewer() {
    return clearanceResolver.resolve(ownerUserId);
  }

  // ── executeQueryForCache: null SQL → 빈 응답 (L107-108 커버) ─────────────────

  @Test
  void executeQueryForCache_nullSql_returnsEmptyResponse() {
    // null SQL → 즉시 빈 AnalyticsQueryResponse 반환
    AnalyticsQueryResponse result = chartService.executeQueryForCache(viewer(), null);

    assertThat(result).isNotNull();
    assertThat(result.queryType()).isEqualTo("SELECT");
    assertThat(result.columns()).isEmpty();
    assertThat(result.rows()).isEmpty();
    assertThat(result.totalRows()).isEqualTo(0);
  }

  @Test
  void executeQueryForCache_blankSql_returnsEmptyResponse() {
    // blank SQL → 즉시 빈 AnalyticsQueryResponse 반환
    AnalyticsQueryResponse result = chartService.executeQueryForCache(viewer(), "   ");

    assertThat(result).isNotNull();
    assertThat(result.queryType()).isEqualTo("SELECT");
    assertThat(result.columns()).isEmpty();
    assertThat(result.rows()).isEmpty();
  }

  @Test
  void executeQueryForCache_validSql_returnsQueryResult() {
    // 유효한 SQL → 실제 실행 결과 반환
    AnalyticsQueryResponse result =
        chartService.executeQueryForCache(viewer(), "SELECT 42 AS answer");

    assertThat(result).isNotNull();
    assertThat(result.totalRows()).isGreaterThan(0);
  }

  // ── WD-454: 차트 데이터·대시보드 위젯 경로(상한 1000행)의 truncated 는 상한을 넘었을 때만 true ──────

  /** 차트의 저장 쿼리를 sql 로 만들고 getChartData 결과의 쿼리 응답을 돌려준다. */
  private AnalyticsQueryResponse chartDataFor(String sql) {
    SavedQueryResponse sq =
        savedQueryService.create(
            new CreateSavedQueryRequest("WD454 SQ", null, sql, null, null, false), ownerUserId);
    ChartResponse chart =
        chartService.create(
            new CreateChartRequest("WD454 Chart", null, sq.id(), "LINE", Map.of(), false),
            ownerUserId);
    return chartService.getChartData(chart.id(), ownerUserId).queryResult();
  }

  @Test
  void getChartData_exactlyCapRows_notTruncated() {
    AnalyticsQueryResponse r = chartDataFor("SELECT g FROM generate_series(1,1000) g");

    assertThat(r.error()).isNull();
    assertThat(r.rows()).hasSize(1000);
    assertThat(r.totalRows()).isEqualTo(1000);
    assertThat(r.truncated()).isFalse();
  }

  @Test
  void getChartData_overCapRows_truncatedWithCapRows() {
    AnalyticsQueryResponse r = chartDataFor("SELECT g FROM generate_series(1,3000) g");

    assertThat(r.error()).isNull();
    assertThat(r.rows()).hasSize(1000);
    assertThat(r.totalRows()).isEqualTo(1000);
    assertThat(r.truncated()).isTrue();
  }

  @Test
  void getChartData_userLimitEqualToCap_notTruncated() {
    AnalyticsQueryResponse r = chartDataFor("SELECT g FROM generate_series(1,3000) g LIMIT 1000");

    assertThat(r.rows()).hasSize(1000);
    assertThat(r.truncated()).isFalse();
  }

  /** 사용자 LIMIT 이 상한보다 크면 존중한다(#749) — 서버가 자르지 않았으므로 잘림 아님. */
  @Test
  void getChartData_userLimitAboveCap_honoredNotTruncated() {
    AnalyticsQueryResponse r = chartDataFor("SELECT g FROM generate_series(1,3000) g LIMIT 5000");

    assertThat(r.rows()).hasSize(3000);
    assertThat(r.truncated()).isFalse();
  }

  @Test
  void getChartData_smallResult_notTruncated() {
    AnalyticsQueryResponse r = chartDataFor("SELECT g FROM generate_series(1,50) g");

    assertThat(r.rows()).hasSize(50);
    assertThat(r.truncated()).isFalse();
  }

  /** 대시보드 위젯 캐시 채움 경로도 같은 규칙이다. */
  @Test
  void executeQueryForCache_exactlyCapRows_notTruncated_overCap_truncated() {
    AnalyticsQueryResponse exact =
        chartService.executeQueryForCache(viewer(), "SELECT g FROM generate_series(1,1000) g");
    AnalyticsQueryResponse over =
        chartService.executeQueryForCache(viewer(), "SELECT g FROM generate_series(1,1001) g");

    assertThat(exact.rows()).hasSize(1000);
    assertThat(exact.truncated()).isFalse();
    assertThat(over.rows()).hasSize(1000);
    assertThat(over.truncated()).isTrue();
  }

  // ── create → ChartNotFoundException after insert (L51) — 간접 검증 ─────────
  // NOTE: insert 후 findById가 empty를 반환하는 상황은 DB 정합성 위반이므로
  // 직접 테스트 불가. 대신 정상 경로가 동작함을 검증한다.

  @Test
  void create_normalPath_returnsCreatedChart() {
    ChartResponse chart =
        chartService.create(
            new CreateChartRequest("Normal Chart", null, savedQueryId, "LINE", Map.of(), false),
            ownerUserId);

    assertThat(chart.id()).isNotNull();
    assertThat(chart.name()).isEqualTo("Normal Chart");
  }
}
