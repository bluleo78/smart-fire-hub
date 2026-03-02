package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.SpatialFilter;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SpatialQueryTest extends IntegrationTestBase {

  @Autowired private DataTableService dataTableService;
  @Autowired private DataTableRowService dataTableRowService;

  // test table names
  private static final String TABLE_POINTS = "sq_test_points";
  private static final String TABLE_POLYGON = "sq_test_polygon";

  // coordinates
  private static final double GWANGHWAMUN_LON = 126.978;
  private static final double GWANGHWAMUN_LAT = 37.566;

  private static final double SEOUL_STATION_LON = 126.972;
  private static final double SEOUL_STATION_LAT = 37.555;

  private static final double GANGNAM_LON = 127.028;
  private static final double GANGNAM_LAT = 37.498;

  private static final double TOKYO_LON = 139.69;
  private static final double TOKYO_LAT = 35.68;

  // GeoJSON helpers
  private static String point(double lon, double lat) {
    return "{\"type\":\"Point\",\"coordinates\":[" + lon + "," + lat + "]}";
  }

  private final List<String> tablesToCleanup = new ArrayList<>();

  @BeforeEach
  void setUp() {
    // Points table: name (TEXT) + location (GEOMETRY)
    List<DatasetColumnRequest> pointCols =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(TABLE_POINTS, pointCols);
    tablesToCleanup.add(TABLE_POINTS);

    Map<String, String> geomType = Map.of("location", "GEOMETRY");
    dataTableRowService.insertBatch(
        TABLE_POINTS,
        List.of("name", "location"),
        List.of(
            Map.of("name", "광화문", "location", point(GWANGHWAMUN_LON, GWANGHWAMUN_LAT)),
            Map.of("name", "서울역", "location", point(SEOUL_STATION_LON, SEOUL_STATION_LAT)),
            Map.of("name", "강남역", "location", point(GANGNAM_LON, GANGNAM_LAT)),
            Map.of("name", "도쿄", "location", point(TOKYO_LON, TOKYO_LAT))),
        geomType);

    // Polygon table: name (TEXT) + area (GEOMETRY)
    List<DatasetColumnRequest> polyCols =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("area", "Area", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(TABLE_POLYGON, polyCols);
    tablesToCleanup.add(TABLE_POLYGON);

    // A polygon covering roughly the Seoul city center (Gwanghwamun / Seoul Station area)
    String seoulCenter =
        "{\"type\":\"Polygon\",\"coordinates\":"
            + "[[[126.96,37.54],[126.99,37.54],[126.99,37.58],[126.96,37.58],[126.96,37.54]]]}";
    // A polygon in Gangnam
    String gangnamPoly =
        "{\"type\":\"Polygon\",\"coordinates\":"
            + "[[[127.01,37.48],[127.05,37.48],[127.05,37.52],[127.01,37.52],[127.01,37.48]]]}";

    Map<String, String> geomTypeArea = Map.of("area", "GEOMETRY");
    dataTableRowService.insertBatch(
        TABLE_POLYGON,
        List.of("name", "area"),
        List.of(
            Map.of("name", "서울중심부", "area", seoulCenter),
            Map.of("name", "강남구역", "area", gangnamPoly)),
        geomTypeArea);
  }

  @AfterEach
  void cleanup() {
    for (String tableName : tablesToCleanup) {
      try {
        dataTableService.dropTable(tableName);
      } catch (Exception e) {
        // ignore
      }
    }
    tablesToCleanup.clear();
  }

  // helper: build DatasetColumnResponse list for TABLE_POINTS
  private List<DatasetColumnResponse> pointColumns() {
    return List.of(
        new DatasetColumnResponse(1L, "name", "Name", "TEXT", null, true, false, null, 0, false),
        new DatasetColumnResponse(
            2L, "location", "Location", "GEOMETRY", null, true, false, null, 1, false));
  }

  private Map<String, String> pointColumnTypes() {
    return Map.of("name", "TEXT", "location", "GEOMETRY");
  }

  // helper: build DatasetColumnResponse list for TABLE_POLYGON
  private List<DatasetColumnResponse> polygonColumns() {
    return List.of(
        new DatasetColumnResponse(1L, "name", "Name", "TEXT", null, true, false, null, 0, false),
        new DatasetColumnResponse(
            2L, "area", "Area", "GEOMETRY", null, true, false, null, 1, false));
  }

  private Map<String, String> polygonColumnTypes() {
    return Map.of("name", "TEXT", "area", "GEOMETRY");
  }

  // =========================================================================
  // Nearby tests
  // =========================================================================

  /** 1. nearby 2km → 광화문+서울역 반환, 강남/도쿄 제외 */
  @Test
  void nearby_returns_points_within_radius() {
    SpatialFilter filter =
        new SpatialFilter.Nearby("location", GWANGHWAMUN_LON, GWANGHWAMUN_LAT, 2000);

    List<Map<String, Object>> rows =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, null, "ASC", 0, 10, filter);

    List<String> names = rows.stream().map(r -> (String) r.get("name")).toList();
    assertThat(names).containsExactlyInAnyOrder("광화문", "서울역");
    assertThat(names).doesNotContain("강남역", "도쿄");
  }

  /** 2. nearby 중심=(0,0) → 빈 결과 */
  @Test
  void nearby_returns_empty_for_no_matches() {
    SpatialFilter filter = new SpatialFilter.Nearby("location", 0.0, 0.0, 1000);

    List<Map<String, Object>> rows =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, null, "ASC", 0, 10, filter);

    assertThat(rows).isEmpty();
  }

  /** 3. nearby → 거리순 정렬 (서울역 < 강남역) */
  @Test
  void nearby_orders_by_distance() {
    // 10km radius from Gwanghwamun → gets Gwanghwamun, Seoulstation, Gangnam (not Tokyo)
    SpatialFilter filter =
        new SpatialFilter.Nearby("location", GWANGHWAMUN_LON, GWANGHWAMUN_LAT, 10000);

    List<Map<String, Object>> rows =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, null, "ASC", 0, 10, filter);

    List<String> names = rows.stream().map(r -> (String) r.get("name")).toList();
    // Gwanghwamun is closest (distance ~0), then Seoulstation (~1.3km), then Gangnam (~8km)
    assertThat(names).contains("서울역", "강남역");
    int seoulIdx = names.indexOf("서울역");
    int gangnamIdx = names.indexOf("강남역");
    assertThat(seoulIdx).isLessThan(gangnamIdx);
  }

  /** 4. nearby → _distance 필드 포함, 서울역 거리 ≈ 1300m */
  @Test
  void nearby_includes_distance_in_rows() {
    SpatialFilter filter =
        new SpatialFilter.Nearby("location", GWANGHWAMUN_LON, GWANGHWAMUN_LAT, 10000);

    List<Map<String, Object>> rows =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, null, "ASC", 0, 10, filter);

    // Find 서울역 row
    Map<String, Object> seoulRow =
        rows.stream().filter(r -> "서울역".equals(r.get("name"))).findFirst().orElseThrow();

    assertThat(seoulRow).containsKey("_distance");
    Object dist = seoulRow.get("_distance");
    assertThat(dist).isNotNull();
    double distMeters = ((Number) dist).doubleValue();
    // Seoul Station is ~1.3km from Gwanghwamun; allow 500m tolerance
    assertThat(distMeters).isBetween(800.0, 1800.0);
  }

  /** 5. nearby 페이지네이션 */
  @Test
  void nearby_pagination_works() {
    // 10km → Gwanghwamun + Seoulstation + Gangnam (3 results)
    SpatialFilter filter =
        new SpatialFilter.Nearby("location", GWANGHWAMUN_LON, GWANGHWAMUN_LAT, 10000);

    // page 0, size 2
    List<Map<String, Object>> page0 =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, null, "ASC", 0, 2, filter);
    // page 1, size 2
    List<Map<String, Object>> page1 =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, null, "ASC", 1, 2, filter);

    assertThat(page0).hasSize(2);
    assertThat(page1).hasSize(1);

    // Counts
    long total =
        dataTableRowService.countRows(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, filter);
    assertThat(total).isEqualTo(3);
  }

  /** 6. nearby + text search: search="서울" + 10km → 서울역만 */
  @Test
  void nearby_with_text_search() {
    SpatialFilter filter =
        new SpatialFilter.Nearby("location", GWANGHWAMUN_LON, GWANGHWAMUN_LAT, 10000);

    List<Map<String, Object>> rows =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), "서울", null, "ASC", 0, 10, filter);

    List<String> names = rows.stream().map(r -> (String) r.get("name")).toList();
    assertThat(names).containsExactly("서울역");
  }

  /** 7. GEOMETRY 컬럼 없으면 에러 */
  @Test
  void nearby_rejects_no_geometry_column() {
    // table with only TEXT column
    String tableName = "sq_no_geom";
    tablesToCleanup.add(tableName);
    dataTableService.createTable(
        tableName,
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null)));

    List<DatasetColumnResponse> cols =
        List.of(
            new DatasetColumnResponse(
                1L, "name", "Name", "TEXT", null, true, false, null, 0, false));
    Map<String, String> types = Map.of("name", "TEXT");

    SpatialFilter filter = new SpatialFilter.Nearby(null, GWANGHWAMUN_LON, GWANGHWAMUN_LAT, 1000);

    assertThatThrownBy(
            () ->
                dataTableRowService.queryData(
                    tableName, cols, types, null, null, "ASC", 0, 10, filter))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // =========================================================================
  // Bbox tests
  // =========================================================================

  /** 8. bbox → bbox 내 포인트 반환 */
  @Test
  void bbox_returns_points_within_envelope() {
    // bbox covering Gwanghwamun + Seoulstation area, excluding Gangnam and Tokyo
    SpatialFilter filter = new SpatialFilter.Bbox("location", 126.96, 37.54, 126.99, 37.58);

    List<Map<String, Object>> rows =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, null, "ASC", 0, 10, filter);

    List<String> names = rows.stream().map(r -> (String) r.get("name")).toList();
    assertThat(names).containsExactlyInAnyOrder("광화문", "서울역");
    assertThat(names).doesNotContain("강남역", "도쿄");
  }

  /** 9. bbox Polygon 교차 */
  @Test
  void bbox_works_with_polygon_data() {
    // bbox intersecting only Seoul center polygon
    SpatialFilter filter = new SpatialFilter.Bbox("area", 126.97, 37.55, 126.98, 37.57);

    List<Map<String, Object>> rows =
        dataTableRowService.queryData(
            TABLE_POLYGON,
            polygonColumns(),
            polygonColumnTypes(),
            null,
            null,
            "ASC",
            0,
            10,
            filter);

    List<String> names = rows.stream().map(r -> (String) r.get("name")).toList();
    assertThat(names).contains("서울중심부");
    assertThat(names).doesNotContain("강남구역");
  }

  /** 10. bbox → 빈 결과 */
  @Test
  void bbox_returns_empty_for_no_matches() {
    // bbox in the middle of the ocean
    SpatialFilter filter = new SpatialFilter.Bbox("location", 0.0, 0.0, 1.0, 1.0);

    List<Map<String, Object>> rows =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, null, "ASC", 0, 10, filter);

    assertThat(rows).isEmpty();
  }

  /** 11. bbox 페이지네이션 */
  @Test
  void bbox_pagination_works() {
    // bbox covering all Korea points (광화문, 서울역, 강남역) — not Tokyo
    SpatialFilter filter = new SpatialFilter.Bbox("location", 126.9, 37.4, 127.1, 37.6);

    List<Map<String, Object>> page0 =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, null, "ASC", 0, 2, filter);
    List<Map<String, Object>> page1 =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, null, "ASC", 1, 2, filter);

    assertThat(page0).hasSize(2);
    assertThat(page1).hasSize(1);

    long total =
        dataTableRowService.countRows(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, filter);
    assertThat(total).isEqualTo(3);
  }

  /** 12. bbox + text search: search="역" + bbox → 서울역만 */
  @Test
  void bbox_with_text_search() {
    // bbox covering Gwanghwamun area (both 광화문 and 서울역 are within)
    SpatialFilter filter = new SpatialFilter.Bbox("location", 126.96, 37.54, 126.99, 37.58);

    List<Map<String, Object>> rows =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), "역", null, "ASC", 0, 10, filter);

    List<String> names = rows.stream().map(r -> (String) r.get("name")).toList();
    // "역" matches "서울역" only (광화문 does not contain "역")
    assertThat(names).containsExactly("서울역");
  }

  // =========================================================================
  // Auto-select geometry column tests
  // =========================================================================

  /** 13. nearby — spatialColumn=null → 첫 번째 GEOMETRY 컬럼 자동 선택 */
  @Test
  void nearby_auto_selects_geometry_column() {
    SpatialFilter filter = new SpatialFilter.Nearby(null, GWANGHWAMUN_LON, GWANGHWAMUN_LAT, 2000);

    List<Map<String, Object>> rows =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, null, "ASC", 0, 10, filter);

    List<String> names = rows.stream().map(r -> (String) r.get("name")).toList();
    assertThat(names).containsExactlyInAnyOrder("광화문", "서울역");
  }

  /** 14. bbox — spatialColumn=null → 첫 번째 GEOMETRY 컬럼 자동 선택 */
  @Test
  void bbox_auto_selects_geometry_column() {
    SpatialFilter filter = new SpatialFilter.Bbox(null, 126.96, 37.54, 126.99, 37.58);

    List<Map<String, Object>> rows =
        dataTableRowService.queryData(
            TABLE_POINTS, pointColumns(), pointColumnTypes(), null, null, "ASC", 0, 10, filter);

    List<String> names = rows.stream().map(r -> (String) r.get("name")).toList();
    assertThat(names).containsExactlyInAnyOrder("광화문", "서울역");
  }
}
