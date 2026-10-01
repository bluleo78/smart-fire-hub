package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.service.AnalyticsQueryExecutionService;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.service.DataTableQueryService;
import com.smartfirehub.support.IntegrationTestBase;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 애드혹 SQL 결과의 geometry·geography <b>배열</b>(1차원·다차원)이 500 으로 실패하지 않는지 확인하는 회귀 테스트(#760).
 *
 * <p><b>결함.</b> 직접 경로(애드혹 분석 executor 끔·데이터셋 SQL 탭)에서 jOOQ 가 geometry 배열 원소를 {@code
 * org.jooq.Geometry} 로 읽어 행 Map 에 그대로 담으면, Jackson 이 이를 직렬화하지 못해 응답이 500 이었다(1차원은
 * {@code Geometry[]}, 다차원은 #757 의 중첩 리스트 안 원소). #759 가 geometry 타입 이름을 {@code "public"."geometry"} 로
 * 고정한 뒤로 jOOQ 는 이 값을 PGobject 로 읽어 더는 실패하지 않는다 — 이 테스트는 그 해소를 고정한다.
 *
 * <p><b>형태는 단언하지 않는다.</b> 지금은 executor 경로와 같은 PG 배열 리터럴(WKB 16진수) 문자열이지만, 경로 간 값 형태
 * 정규화(#758)·SQL 탭 geometry 표시(#767)는 사람 결정 대기 중이다. 그래서 "오류 없음 + Spring ObjectMapper 직렬화 성공 +
 * 값(WKB 16진수)이 사라지지 않음" 만 본다.
 *
 * <p><b>순서·커넥션.</b> pgjdbc 의 커넥션별 타입 이름 캐시가 실행 순서에 따라 jOOQ 의 타입 해석을 바꾸므로(#759), 매
 * 시나리오 전에 Hikari 풀을 비워 새 커넥션에서 시작하고 두 경로를 같은 트랜잭션(= 같은 커넥션)에서 양쪽 순서로 실행한다.
 */
class AdhocGeometryArrayTest extends IntegrationTestBase {

  /** POINT(1 2) SRID 4326 의 EWKB 16진수 — 어떤 형태로 나가든 값이 보존됐는지 확인하는 지문. */
  private static final String POINT_WKB_HEX = "0101000020E6100000000000000000F03F0000000000000040";

  /** 애드혹 분석(search_path 에 public 포함)용 식과, 데이터셋 SQL 탭(데이터 스키마만)용 public 한정 식의 쌍. */
  private record Case(String name, String analyticsExpr, String datasetExpr) {}

  private static final List<Case> CASES =
      List.of(
          new Case(
              "geometry[]",
              "ARRAY[ST_SetSRID(ST_MakePoint(1,2),4326)]",
              "ARRAY[public.ST_SetSRID(public.ST_MakePoint(1,2),4326)]"),
          new Case(
              "geometry[][]",
              "ARRAY[[ST_SetSRID(ST_MakePoint(1,2),4326)]]",
              "ARRAY[[public.ST_SetSRID(public.ST_MakePoint(1,2),4326)]]"),
          new Case(
              "geography[]",
              "ARRAY[ST_MakePoint(1,2)::geography]",
              "ARRAY[public.ST_MakePoint(1,2)::public.geography]"),
          new Case(
              "geography[][]",
              "ARRAY[[ST_MakePoint(1,2)::geography]]",
              "ARRAY[[public.ST_MakePoint(1,2)::public.geography]]"));

  @Autowired private AnalyticsQueryExecutionService analyticsService;
  @Autowired private DataTableQueryService dataTableQueryService;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DataSource dataSource;

  /** 커넥션별 pgjdbc 타입 캐시를 버린다 — 이후 커넥션은 새로 열린다. */
  private void freshConnections() {
    try {
      dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 응답을 Spring 이 쓰는 ObjectMapper 로 직렬화(= HTTP 응답 본문 생성)한 뒤 다시 파싱해 v 값을 확인한다. 이슈의 500 은 바로
   * 이 직렬화 단계({@code No serializer found for class org.jooq.Geometry})에서 났다.
   */
  private void assertServedWithoutLoss(String label, Object response) {
    String body;
    try {
      body = objectMapper.writeValueAsString(response);
    } catch (Exception e) {
      throw new AssertionError(label + " 응답 직렬화 실패(HTTP 500 에 해당): " + e.getMessage(), e);
    }
    JsonNode v;
    try {
      v = objectMapper.readTree(body).get("rows").get(0).get("v");
    } catch (Exception e) {
      throw new AssertionError(label + " 응답이 올바른 JSON 이 아니다: " + body, e);
    }
    assertThat(v).as("%s 값", label).isNotNull();
    assertThat(v.isNull()).as("%s 값이 null 로 사라지면 안 된다", label).isFalse();
    assertThat(v.toString()).as("%s 값에 geometry(WKB) 가 보존돼야 한다", label).contains(POINT_WKB_HEX);
  }

  private void runAnalytics(Case c) {
    AnalyticsQueryResponse res = analyticsService.execute("SELECT " + c.analyticsExpr() + " AS v", 10, true);
    assertThat(res.error()).as("애드혹 분석 %s 오류", c.name()).isNull();
    assertServedWithoutLoss("애드혹 분석 " + c.name(), res);
  }

  private void runDataset(Case c) {
    SqlQueryResponse res = dataTableQueryService.executeQuery("SELECT " + c.datasetExpr() + " AS v", 10);
    assertThat(res.error()).as("데이터셋 SQL 탭 %s 오류", c.name()).isNull();
    assertServedWithoutLoss("데이터셋 SQL 탭 " + c.name(), res);
  }

  @Test
  void geometryArrays_areServed_whenAnalyticsRunsFirst() {
    for (Case c : CASES) {
      freshConnections();
      inTenantFixture(
          () -> {
            runAnalytics(c);
            runDataset(c);
          });
    }
  }

  @Test
  void geometryArrays_areServed_whenDatasetTabRunsFirst() {
    for (Case c : CASES) {
      freshConnections();
      inTenantFixture(
          () -> {
            runDataset(c);
            runAnalytics(c);
          });
    }
  }
}
