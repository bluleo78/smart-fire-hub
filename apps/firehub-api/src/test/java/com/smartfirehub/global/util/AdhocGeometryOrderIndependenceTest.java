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
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 애드혹 SQL 의 geometry 조회가 <b>실행 순서에 따라</b> 깨지지 않는지 확인하는 회귀 테스트(#759).
 *
 * <p><b>결함.</b> pgjdbc 는 커넥션마다 "타입 OID → 타입 이름" 캐시(TypeInfoCache)를 두고, 이름은 <b>처음 조회한 순간의
 * search_path</b> 기준으로 정한다 — public 이 경로에 있으면 {@code geometry}, 없으면 {@code "public"."geometry"}. 애드혹
 * 분석 경로는 search_path 에 public 을 넣고, 데이터셋 SQL 탭은 데이터 스키마만 둔다. 그래서 같은 풀 커넥션에서 분석이 먼저
 * geometry 를 읽으면 그 커넥션의 캐시에 {@code geometry} 가 박히고, 이후 데이터셋 탭에서도 메타데이터 타입 이름이
 * {@code geometry} 로 보인다. jOOQ 는 이 이름을 자기 GEOMETRY 타입으로 해석해 값을 읽다 "Error while reading field" 로
 * 실패한다(분석 경로는 재시도로 가려졌지만 데이터셋 탭엔 재시도가 없다). 반대 순서면 둘 다 정상이었다.
 *
 * <p><b>테스트 방법.</b> pgjdbc 캐시는 물리 커넥션 수명 동안 남으므로, 매 시나리오 전에 Hikari 풀을 비워(softEvict) 새
 * 커넥션에서 시작한다. 두 호출은 하나의 트랜잭션(= 같은 커넥션)으로 묶어 "같은 커넥션에서 A 후 B" 를 결정적으로 만든다.
 */
class AdhocGeometryOrderIndependenceTest extends IntegrationTestBase {

  /** 데이터셋 SQL 탭은 search_path 가 데이터 스키마뿐이라 PostGIS 함수를 public 으로 한정해 부른다(이슈 재현과 동일). */
  private static final String DATASET_GEOM_SQL =
      "SELECT public.ST_SetSRID(public.ST_MakePoint(1,2),4326) AS g";

  private static final String ANALYTICS_GEOM_SQL =
      "SELECT 1 AS i, ST_SetSRID(ST_MakePoint(1,2),4326) AS g";

  @Autowired private AnalyticsQueryExecutionService analyticsService;
  @Autowired private DataTableQueryService dataTableQueryService;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DataSource dataSource;

  /** 이전 테스트가 남긴 커넥션별 타입 캐시를 버린다 — 이후 커넥션은 새로 열린다. */
  @BeforeEach
  void evictPooledConnections() {
    freshConnections();
  }

  private void freshConnections() {
    try {
      dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode json(Object response) {
    try {
      return objectMapper.readTree(objectMapper.writeValueAsString(response));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void datasetTabGeometry_stillWorks_afterAnalyticsReadGeometryOnSameConnection() {
    // 기준값: 새 커넥션에서 데이터셋 탭만 실행했을 때의 응답(이슈의 "정상" 순서)
    JsonNode baseline =
        inTenantFixture(
            () -> {
              SqlQueryResponse res = dataTableQueryService.executeQuery(DATASET_GEOM_SQL, 100);
              assertThat(res.error()).as("기준(데이터셋 먼저) 오류").isNull();
              return json(res).get("rows").get(0).get("g");
            });
    assertThat(baseline.get("value").asText()).as("기준 geometry 값(WKB hex)").isNotBlank();

    freshConnections();

    // 문제 순서: 같은 커넥션에서 분석 → 데이터셋 탭
    inTenantFixture(
        () -> {
          AnalyticsQueryResponse a = analyticsService.execute(ANALYTICS_GEOM_SQL, 100, true);
          assertThat(a.error()).as("분석 경로 오류").isNull();
          JsonNode geo = json(a).get("rows").get(0).get("g");
          assertThat(geo.isTextual()).as("분석 경로는 GeoJSON 문자열").isTrue();
          assertThat(readTree(geo.asText()).get("type").asText()).isEqualTo("Point");

          // 이슈에선 3회 모두 실패했다 — 한 번 실패가 아니라 커넥션 상태로 남는 것까지 확인한다
          for (int i = 0; i < 3; i++) {
            SqlQueryResponse d = dataTableQueryService.executeQuery(DATASET_GEOM_SQL, 100);
            assertThat(d.error()).as("분석 다음 데이터셋 탭 geometry 조회 #%d", i + 1).isNull();
            // 값(WKB hex)은 순서와 무관하게 같아야 한다. PGobject 의 type 표기는 pgjdbc 캐시 이름을 따르므로 비교하지 않는다.
            assertThat(json(d).get("rows").get(0).get("g").get("value"))
                .as("순서와 무관하게 같은 geometry 값이어야 한다")
                .isEqualTo(baseline.get("value"));
          }

          // geometry 배열도 같은 이유(_geometry 이름)로 깨지지 않아야 한다 — 분석 경로가 먼저 _geometry OID 를
          // public 이 든 search_path 로 해석해 캐시에 남기게 한 뒤 데이터셋 탭에서 읽는다(결과는 보지 않는다)
          analyticsService.execute("SELECT ARRAY[ST_MakePoint(1,2)] AS ga", 100, true);
          SqlQueryResponse arr =
              dataTableQueryService.executeQuery(
                  "SELECT ARRAY[public.ST_MakePoint(1,2)] AS ga", 100);
          assertThat(arr.error()).as("분석 다음 데이터셋 탭 geometry[] 조회").isNull();
          assertThat(json(arr).get("rows").get(0).get("ga").isNull()).isFalse();
        });
  }

  @Test
  void analyticsGeometry_isGeoJson_regardlessOfOrder() {
    // 반대 순서(데이터셋 → 분석)에서도 분석 경로는 GeoJSON 을 돌려준다
    inTenantFixture(
        () -> {
          assertThat(dataTableQueryService.executeQuery(DATASET_GEOM_SQL, 100).error()).isNull();
          AnalyticsQueryResponse a = analyticsService.execute(ANALYTICS_GEOM_SQL, 100, true);
          assertThat(a.error()).isNull();
          JsonNode geo = json(a).get("rows").get(0).get("g");
          assertThat(geo.isTextual()).isTrue();
          assertThat(readTree(geo.asText()).get("type").asText()).isEqualTo("Point");
        });
  }

  private JsonNode readTree(String s) {
    try {
      return objectMapper.readTree(s);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
