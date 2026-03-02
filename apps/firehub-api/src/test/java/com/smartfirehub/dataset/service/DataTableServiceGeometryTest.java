package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.ColumnStatsResponse;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DataTableServiceGeometryTest extends IntegrationTestBase {

  @Autowired private DataTableService dataTableService;
  @Autowired private DataTableRowService dataTableRowService;
  @Autowired private DSLContext dsl;

  // Seoul Gwanghwamun coordinates
  private static final double LON = 126.978;
  private static final double LAT = 37.566;

  private static final String GEOJSON_POINT =
      "{\"type\":\"Point\",\"coordinates\":[126.978,37.566]}";
  private static final String GEOJSON_POLYGON =
      "{\"type\":\"Polygon\",\"coordinates\":"
          + "[[[126.97,37.56],[126.98,37.56],[126.98,37.57],[126.97,37.57],[126.97,37.56]]]}";
  private static final String GEOJSON_LINESTRING =
      "{\"type\":\"LineString\",\"coordinates\":[[126.97,37.56],[126.98,37.57]]}";

  private final List<String> tablesToCleanup = new ArrayList<>();

  @AfterEach
  void cleanup() {
    for (String tableName : tablesToCleanup) {
      try {
        dataTableService.dropTable(tableName);
      } catch (Exception e) {
        // Ignore cleanup errors
      }
    }
    tablesToCleanup.clear();
  }

  // =========================================================================
  // DDL Tests
  // =========================================================================

  @Test
  void createTable_with_geometry_column() {
    String tableName = "test_geom_create";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));

    dataTableService.createTable(tableName, columns);

    Long columnExists =
        dsl.selectCount()
            .from("information_schema.columns")
            .where(
                "table_schema = 'data' AND table_name = '"
                    + tableName
                    + "' AND column_name = 'location'")
            .fetchOne(0, Long.class);
    assertThat(columnExists).isEqualTo(1);

    // Verify the column type is geometry
    String dataType =
        dsl.fetchOne(
                "SELECT udt_name FROM information_schema.columns"
                    + " WHERE table_schema = 'data' AND table_name = '"
                    + tableName
                    + "' AND column_name = 'location'")
            .get(0, String.class);
    assertThat(dataType).isEqualToIgnoringCase("geometry");
  }

  @Test
  void createTable_geometry_has_gist_index() {
    String tableName = "test_geom_gist_auto";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));

    dataTableService.createTable(tableName, columns);

    Long gistIndexCount =
        dsl.fetchOne(
                "SELECT COUNT(*) FROM pg_indexes"
                    + " WHERE schemaname = 'data' AND tablename = '"
                    + tableName
                    + "' AND indexdef ILIKE '%gist%'")
            .get(0, Long.class);
    assertThat(gistIndexCount).isGreaterThanOrEqualTo(1);
  }

  @Test
  void addColumn_geometry_creates_gist_index() {
    String tableName = "test_geom_addcol_gist";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> baseColumns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null));
    dataTableService.createTable(tableName, baseColumns);

    DatasetColumnRequest geomCol =
        new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null);
    dataTableService.addColumn(tableName, geomCol);

    Long gistIndexCount =
        dsl.fetchOne(
                "SELECT COUNT(*) FROM pg_indexes"
                    + " WHERE schemaname = 'data' AND tablename = '"
                    + tableName
                    + "' AND indexdef ILIKE '%gist%'")
            .get(0, Long.class);
    assertThat(gistIndexCount).isGreaterThanOrEqualTo(1);
  }

  @Test
  void alterColumnType_to_geometry_blocked() {
    String tableName = "test_geom_alter_to";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    assertThatThrownBy(
            () -> dataTableService.alterColumnType(tableName, "col1", "GEOMETRY", null, "TEXT"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GEOMETRY");
  }

  @Test
  void alterColumnType_from_geometry_blocked() {
    String tableName = "test_geom_alter_from";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    assertThatThrownBy(
            () -> dataTableService.alterColumnType(tableName, "location", "TEXT", null, "GEOMETRY"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GEOMETRY");
  }

  // =========================================================================
  // DML Tests — single row
  // =========================================================================

  @Test
  void insertRow_geojson_point() {
    String tableName = "test_geom_ins_point";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Map<String, String> columnTypes = Map.of("location", "GEOMETRY");
    Long id =
        dataTableRowService.insertRow(
            tableName, List.of("location"), Map.of("location", GEOJSON_POINT), columnTypes);

    assertThat(id).isNotNull().isPositive();
    long count = dataTableRowService.countRows(tableName);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void insertRow_geojson_polygon() {
    String tableName = "test_geom_ins_poly";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("area", "Area", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Map<String, String> columnTypes = Map.of("area", "GEOMETRY");
    Long id =
        dataTableRowService.insertRow(
            tableName, List.of("area"), Map.of("area", GEOJSON_POLYGON), columnTypes);

    assertThat(id).isNotNull().isPositive();
  }

  @Test
  void insertRow_geojson_linestring() {
    String tableName = "test_geom_ins_line";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("route", "Route", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Map<String, String> columnTypes = Map.of("route", "GEOMETRY");
    Long id =
        dataTableRowService.insertRow(
            tableName, List.of("route"), Map.of("route", GEOJSON_LINESTRING), columnTypes);

    assertThat(id).isNotNull().isPositive();
  }

  @Test
  void selectRows_geometry_as_geojson() {
    String tableName = "test_geom_select_json";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Map<String, String> columnTypes = Map.of("location", "GEOMETRY");
    dataTableRowService.insertRow(
        tableName, List.of("location"), Map.of("location", GEOJSON_POINT), columnTypes);

    List<Map<String, Object>> rows =
        dataTableRowService.queryData(
            tableName, List.of("location"), null, 0, 10, null, "ASC", columnTypes);

    assertThat(rows).hasSize(1);
    Object locationValue = rows.get(0).get("location");
    assertThat(locationValue).isNotNull();
    // ST_AsGeoJSON returns a JSON string, not WKB bytes
    assertThat(locationValue).isInstanceOf(String.class);
    String geoJson = (String) locationValue;
    assertThat(geoJson).contains("Point");
    assertThat(geoJson).contains("coordinates");
  }

  @Test
  void insertAndSelect_roundtrip() {
    String tableName = "test_geom_roundtrip";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Map<String, String> columnTypes = Map.of("location", "GEOMETRY");
    Long id =
        dataTableRowService.insertRow(
            tableName, List.of("location"), Map.of("location", GEOJSON_POINT), columnTypes);

    Map<String, Object> row =
        dataTableRowService.getRow(tableName, List.of("location"), id, columnTypes);

    String stored = (String) row.get("location");
    assertThat(stored).isNotNull();
    // Round-trip: both longitude and latitude appear in the returned GeoJSON
    assertThat(stored).contains("126.978");
    assertThat(stored).contains("37.566");
  }

  @Test
  void updateRow_geometry() {
    String tableName = "test_geom_update";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Map<String, String> columnTypes = Map.of("location", "GEOMETRY");
    Long id =
        dataTableRowService.insertRow(
            tableName, List.of("location"), Map.of("location", GEOJSON_POINT), columnTypes);

    // Update to polygon
    Map<String, Object> updateData = new HashMap<>();
    updateData.put("location", GEOJSON_POLYGON);
    dataTableRowService.updateRow(tableName, id, List.of("location"), updateData, columnTypes);

    Map<String, Object> row =
        dataTableRowService.getRow(tableName, List.of("location"), id, columnTypes);
    String stored = (String) row.get("location");
    assertThat(stored).isNotNull();
    assertThat(stored).contains("Polygon");
  }

  @Test
  void insertRow_invalid_geojson_throws() {
    String tableName = "test_geom_invalid";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Map<String, String> columnTypes = Map.of("location", "GEOMETRY");

    assertThatThrownBy(
            () ->
                dataTableRowService.insertRow(
                    tableName,
                    List.of("location"),
                    Map.of("location", "not-valid-geojson"),
                    columnTypes))
        .isInstanceOf(Exception.class);
  }

  @Test
  void insertRow_null_geometry_allowed() {
    String tableName = "test_geom_null";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Map<String, String> columnTypes = Map.of("location", "GEOMETRY");
    Map<String, Object> rowData = new HashMap<>();
    rowData.put("location", null);

    Long id = dataTableRowService.insertRow(tableName, List.of("location"), rowData, columnTypes);
    assertThat(id).isNotNull().isPositive();

    Map<String, Object> row =
        dataTableRowService.getRow(tableName, List.of("location"), id, columnTypes);
    assertThat(row.get("location")).isNull();
  }

  // =========================================================================
  // DML Tests — batch
  // =========================================================================

  @Test
  void insertBatch_with_geometry() {
    String tableName = "test_geom_batch_ins";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Map<String, String> columnTypes = Map.of("location", "GEOMETRY");

    List<Map<String, Object>> rows =
        List.of(
            Map.of("name", "Gwanghwamun", "location", GEOJSON_POINT),
            Map.of("name", "Area", "location", GEOJSON_POLYGON));

    dataTableRowService.insertBatch(tableName, List.of("name", "location"), rows, columnTypes);

    long count = dataTableRowService.countRows(tableName);
    assertThat(count).isEqualTo(2);
  }

  @Test
  void upsertBatch_with_geometry() {
    String tableName = "test_geom_upsert";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest(
                "place_id", "Place ID", "TEXT", null, false, false, null, true),
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Map<String, String> columnTypes = Map.of("location", "GEOMETRY");

    List<Map<String, Object>> rows = List.of(Map.of("place_id", "P001", "location", GEOJSON_POINT));

    DataTableRowService.UpsertResult result =
        dataTableRowService.upsertBatch(
            tableName,
            List.of("place_id", "location"),
            List.of("place_id"),
            rows,
            null,
            columnTypes);

    assertThat(result.inserted()).isEqualTo(1);
    assertThat(result.updated()).isEqualTo(0);

    // Upsert same place_id with different geometry
    List<Map<String, Object>> updateRows =
        List.of(Map.of("place_id", "P001", "location", GEOJSON_POLYGON));

    DataTableRowService.UpsertResult result2 =
        dataTableRowService.upsertBatch(
            tableName,
            List.of("place_id", "location"),
            List.of("place_id"),
            updateRows,
            null,
            columnTypes);

    assertThat(result2.inserted()).isEqualTo(0);
    assertThat(result2.updated()).isEqualTo(1);
    assertThat(dataTableRowService.countRows(tableName)).isEqualTo(1);
  }

  // =========================================================================
  // Search / Stats
  // =========================================================================

  @Test
  void search_excludes_geometry() {
    String tableName = "test_geom_search";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Map<String, String> columnTypes = Map.of("name", "TEXT", "location", "GEOMETRY");

    dataTableRowService.insertBatch(
        tableName,
        List.of("name", "location"),
        List.of(Map.of("name", "Seoul", "location", GEOJSON_POINT)),
        Map.of("location", "GEOMETRY"));

    // Search should work without throwing (GEOMETRY excluded from ILIKE)
    List<Map<String, Object>> results =
        dataTableRowService.queryData(
            tableName, List.of("name", "location"), "Seoul", 0, 10, null, "ASC", columnTypes);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).get("name")).isEqualTo("Seoul");

    // Searching by geometry content should not throw
    List<Map<String, Object>> noMatch =
        dataTableRowService.queryData(
            tableName, List.of("name", "location"), "xyz", 0, 10, null, "ASC", columnTypes);
    assertThat(noMatch).isEmpty();
  }

  @Test
  void getColumnStats_geometry() {
    String tableName = "test_geom_stats";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    Map<String, String> columnTypes = Map.of("location", "GEOMETRY");
    dataTableRowService.insertBatch(
        tableName,
        List.of("location"),
        List.of(Map.of("location", GEOJSON_POINT), Map.of("location", GEOJSON_POLYGON)),
        Map.of("location", "GEOMETRY"));

    List<DatasetColumnResponse> columnDefs =
        List.of(
            new DatasetColumnResponse(
                1L, "location", "Location", "GEOMETRY", null, true, false, null, 0, false));

    List<ColumnStatsResponse> stats = dataTableService.getColumnStats(tableName, columnDefs);

    assertThat(stats).hasSize(1);
    ColumnStatsResponse stat = stats.get(0);
    assertThat(stat.columnName()).isEqualTo("location");
    assertThat(stat.dataType()).isEqualTo("GEOMETRY");
    assertThat(stat.totalCount()).isEqualTo(2);
    assertThat(stat.nullCount()).isEqualTo(0);
    // minValue field is used for bbox in GEOMETRY stats
    assertThat(stat.minValue()).isNotNull(); // ST_Extent result (bbox)
    // topValues holds geometry type distribution
    assertThat(stat.topValues()).isNotEmpty();
  }

  // =========================================================================
  // Clone Table
  // =========================================================================

  @Test
  void cloneTable_preserves_gist_index() {
    String sourceTable = "test_geom_clone_src";
    String targetTable = "test_geom_clone_tgt";
    tablesToCleanup.add(sourceTable);
    tablesToCleanup.add(targetTable);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(sourceTable, columns);

    dataTableRowService.insertBatch(
        sourceTable,
        List.of("name", "location"),
        List.of(Map.of("name", "Seoul", "location", GEOJSON_POINT)),
        Map.of("location", "GEOMETRY"));

    List<DatasetColumnResponse> columnDefs =
        List.of(
            new DatasetColumnResponse(
                1L, "name", "Name", "TEXT", null, true, false, null, 0, false),
            new DatasetColumnResponse(
                2L, "location", "Location", "GEOMETRY", null, true, false, null, 1, false));

    dataTableService.cloneTable(sourceTable, targetTable, List.of("name", "location"), columnDefs);

    Long gistIndexCount =
        dsl.fetchOne(
                "SELECT COUNT(*) FROM pg_indexes"
                    + " WHERE schemaname = 'data' AND tablename = '"
                    + targetTable
                    + "' AND indexdef ILIKE '%gist%'")
            .get(0, Long.class);
    assertThat(gistIndexCount).isGreaterThanOrEqualTo(1);

    long count = dataTableRowService.countRows(targetTable);
    assertThat(count).isEqualTo(1);
  }

  // =========================================================================
  // PoC Spatial Queries
  // =========================================================================

  @Test
  void poc_st_dwithin() {
    String tableName = "test_geom_dwithin";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null),
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    dataTableRowService.insertBatch(
        tableName,
        List.of("name", "location"),
        List.of(
            Map.of("name", "Gwanghwamun", "location", GEOJSON_POINT),
            // Distant point: Tokyo approx 35.68, 139.69
            Map.of(
                "name",
                "Tokyo",
                "location",
                "{\"type\":\"Point\",\"coordinates\":[139.69,35.68]}")),
        Map.of("location", "GEOMETRY"));

    // ST_DWithin in geography (degrees-based): ~0.1 degrees ≈ ~11km
    String sql =
        "SELECT name FROM data.\""
            + tableName
            + "\" WHERE ST_DWithin("
            + "\"location\"::geography,"
            + " ST_SetSRID(ST_MakePoint(126.978, 37.566), 4326)::geography,"
            + " 10000"
            + // 10km radius in meters
            ")";

    var result = dsl.fetch(sql);
    assertThat(result).hasSize(1);
    assertThat(result.get(0).get("name", String.class)).isEqualTo("Gwanghwamun");
  }

  @Test
  void poc_st_distance() {
    String tableName = "test_geom_distance";
    tablesToCleanup.add(tableName);

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("location", "Location", "GEOMETRY", null, true, false, null));
    dataTableService.createTable(tableName, columns);

    dataTableRowService.insertBatch(
        tableName,
        List.of("location"),
        List.of(Map.of("location", GEOJSON_POINT)),
        Map.of("location", "GEOMETRY"));

    // ST_Distance in geography returns meters
    String sql =
        "SELECT ST_Distance("
            + "\"location\"::geography,"
            + " ST_SetSRID(ST_MakePoint(126.978, 37.566), 4326)::geography"
            + ") AS dist FROM data.\""
            + tableName
            + "\"";

    var result = dsl.fetch(sql);
    assertThat(result).hasSize(1);
    Double dist = result.get(0).get("dist", Double.class);
    // Same point: distance should be ~0 meters
    assertThat(dist).isNotNull().isLessThan(1.0);
  }
}
