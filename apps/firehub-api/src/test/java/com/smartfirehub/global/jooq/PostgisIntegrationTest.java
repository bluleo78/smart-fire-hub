package com.smartfirehub.global.jooq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.smartfirehub.global.util.GeoJsonUtil;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostGIS + jOOQ 통합 PoC 테스트. 이 테스트가 전부 통과해야 다음 단계(소방 도메인 CRUD API) 진행.
 */
@Transactional
class PostgisIntegrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  // ===== PoC 시나리오 1: POINT INSERT + SELECT 왕복 =====
  @Test
  void poc1_pointInsertAndSelectRoundTrip() {
    dsl.execute(
        """
        INSERT INTO fire.organization (name, type, coordinates)
        VALUES ('서울소방재난본부', 'HQ',
                ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326))
        """);

    Record record =
        dsl.fetchOne(
            """
            SELECT name, ST_X(coordinates) as lng, ST_Y(coordinates) as lat
            FROM fire.organization WHERE name = '서울소방재난본부'
            """);

    assertThat(record).isNotNull();
    assertThat(record.get("lng", Double.class)).isCloseTo(126.9780, within(0.0001));
    assertThat(record.get("lat", Double.class)).isCloseTo(37.5665, within(0.0001));
  }

  // ===== PoC 시나리오 2: ST_DWithin 반경 검색 =====
  @Test
  void poc2_stDWithinRadiusSearch() {
    dsl.execute(
        """
        INSERT INTO fire.organization (id, name, type)
        VALUES (9999, '테스트본부', 'HQ')
        ON CONFLICT DO NOTHING
        """);

    dsl.execute(
        """
        INSERT INTO fire.hydrant (organization_id, type, location, address)
        VALUES
            (9999, 'ABOVEGROUND', ST_SetSRID(ST_MakePoint(127.0276, 37.4979), 4326), '강남역'),
            (9999, 'UNDERGROUND', ST_SetSRID(ST_MakePoint(127.0286, 37.4989), 4326), '강남역 2번출구'),
            (9999, 'ABOVEGROUND', ST_SetSRID(ST_MakePoint(127.1000, 37.5500), 4326), '멀리 있는 소화전')
        """);

    Result<?> result =
        dsl.fetch(
            """
            SELECT id, address,
                   ST_Distance(location::geography,
                               ST_SetSRID(ST_MakePoint(127.0276, 37.4979), 4326)::geography) as distance_m
            FROM fire.hydrant
            WHERE ST_DWithin(location::geography,
                             ST_SetSRID(ST_MakePoint(127.0276, 37.4979), 4326)::geography,
                             500)
            ORDER BY distance_m
            """);

    assertThat(result).hasSizeBetween(1, 2);
    assertThat(result.get(0).get("address", String.class)).contains("강남역");
  }

  // ===== PoC 시나리오 3: ST_Contains 포함 판별 =====
  @Test
  void poc3_stContainsPolygonTest() {
    dsl.execute(
        """
        INSERT INTO fire.organization (id, name, type)
        VALUES (9999, '테스트본부', 'HQ')
        ON CONFLICT DO NOTHING
        """);

    dsl.execute(
        """
        INSERT INTO fire.district (organization_id, name, boundary)
        VALUES (9999, '강남구 관할',
                ST_SetSRID(ST_GeomFromText(
                    'MULTIPOLYGON(((127.01 37.49, 127.05 37.49, 127.05 37.52, 127.01 37.52, 127.01 37.49)))'),
                4326))
        """);

    // 관할구역 내부 포인트 (강남역)
    Record inside =
        dsl.fetchOne(
            """
            SELECT ST_Contains(boundary,
                               ST_SetSRID(ST_MakePoint(127.0276, 37.4979), 4326)) as is_inside
            FROM fire.district WHERE name = '강남구 관할'
            """);
    assertThat(inside).isNotNull();
    assertThat(inside.get("is_inside", Boolean.class)).isTrue();

    // 관할구역 외부 포인트 (종로)
    Record outside =
        dsl.fetchOne(
            """
            SELECT ST_Contains(boundary,
                               ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)) as is_inside
            FROM fire.district WHERE name = '강남구 관할'
            """);
    assertThat(outside).isNotNull();
    assertThat(outside.get("is_inside", Boolean.class)).isFalse();
  }

  // ===== PoC 시나리오 4: GeoJSON 변환 왕복 =====
  @Test
  void poc4_geoJsonRoundTrip() {
    // GeoJSON → JTS Geometry
    String inputGeoJson = "{\"type\":\"Point\",\"coordinates\":[126.9780,37.5665]}";
    Geometry geom = GeoJsonUtil.fromGeoJson(inputGeoJson);

    assertThat(geom).isInstanceOf(Point.class);
    assertThat(geom.getSRID()).isEqualTo(4326);
    assertThat(((Point) geom).getX()).isCloseTo(126.9780, within(0.0001));
    assertThat(((Point) geom).getY()).isCloseTo(37.5665, within(0.0001));

    // JTS Geometry → GeoJSON
    String outputGeoJson = GeoJsonUtil.toGeoJson(geom);
    assertThat(outputGeoJson).contains("126.978");
    assertThat(outputGeoJson).contains("37.5665");

    // DB 저장 후 GeoJSON으로 변환
    dsl.execute(
        """
        INSERT INTO fire.organization (name, type, coordinates)
        VALUES ('GeoJSON 테스트', 'HQ',
                ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326))
        """);

    Record record =
        dsl.fetchOne(
            """
            SELECT ST_AsGeoJSON(coordinates) as geojson
            FROM fire.organization WHERE name = 'GeoJSON 테스트'
            """);

    assertThat(record).isNotNull();
    String dbGeoJson = record.get("geojson", String.class);
    assertThat(dbGeoJson).contains("126.978");
    assertThat(dbGeoJson).contains("37.5665");
  }

  // ===== 추가: PostGIS 확장 활성화 확인 =====
  @Test
  void postgisExtensionIsActive() {
    Record record = dsl.fetchOne("SELECT PostGIS_Version() as version");
    assertThat(record).isNotNull();
    assertThat(record.get("version", String.class)).startsWith("3.");
  }

  // ===== 추가: fire 스키마 존재 확인 =====
  @Test
  void fireSchemaExists() {
    Record record =
        dsl.fetchOne(
            """
            SELECT count(*) as cnt FROM information_schema.tables
            WHERE table_schema = 'fire'
            """);
    assertThat(record).isNotNull();
    assertThat(record.get("cnt", Long.class)).isEqualTo(7L);
  }

  // ===== 추가: 사건 유형 시드 데이터 확인 =====
  @Test
  void incidentTypeSeedData() {
    Result<?> result = dsl.fetch("SELECT * FROM fire.incident_type ORDER BY id");
    assertThat(result).hasSize(12);
  }
}
