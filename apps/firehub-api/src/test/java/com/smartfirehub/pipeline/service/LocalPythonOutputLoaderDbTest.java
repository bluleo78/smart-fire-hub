package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 PYTHON 출력 적재가 실제 NUMERIC 컬럼에 비유한수를 executor 와 같게 넣는지(CR6). executor 는 Decimal NaN/±Infinity 를
 * psycopg2 가 전부 {@code 'NaN'::numeric} 으로 보내므로, 같은 stdout 이 로컬 경로에서도 NaN 행이 되어야 한다(예전엔 NULL).
 */
@Transactional
class LocalPythonOutputLoaderDbTest extends IntegrationTestBase {

  @Autowired private LocalPythonOutputLoader loader;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  private Long userId;

  private Long user() {
    if (userId == null) {
      userId =
          dsl.insertInto(USER)
              .set(USER.USERNAME, "local_py_nan_user")
              .set(USER.PASSWORD, "password")
              .set(USER.NAME, "Local Py NaN")
              .set(USER.EMAIL, "local_py_nan@example.com")
              .returning(USER.ID)
              .fetchOne()
              .getId();
    }
    return userId;
  }

  private void createTable(String name, List<DatasetColumnRequest> columns) {
    datasetService.createDataset(
        new CreateDatasetRequest(name, name, null, null, "TABLE", "SOURCE", columns, null), user());
  }

  private static DatasetColumnRequest col(String name, String type) {
    return new DatasetColumnRequest(name, name, type, null, true, false, null);
  }

  @Test
  void nonFiniteDecimal_loadsAsNumericNaN() {
    createTable("local_py_nan", List.of(col("k", "INTEGER"), col("v", "DECIMAL")));

    // Python json.dumps 가 내는 그대로의 토큰 + 문자열 철자 + 유한수
    String stdout =
        "[{\"k\":1,\"v\":NaN},{\"k\":2,\"v\":Infinity},{\"k\":3,\"v\":-Infinity},"
            + "{\"k\":4,\"v\":\"nan\"},{\"k\":5,\"v\":1.5}]";
    long loaded = loader.load("local_py_nan", stdout, Map.of("k", "INTEGER", "v", "DECIMAL"));

    assertThat(loaded).isEqualTo(5);
    List<String> values =
        dsl.fetch("SELECT v::text FROM " + DataSchema.qualify("local_py_nan") + " ORDER BY k")
            .getValues(0, String.class);
    assertThat(values).containsExactly("NaN", "NaN", "NaN", "NaN", "1.500000");
  }

  /**
   * 오프셋·Z 가 붙은 TIMESTAMP 값이 적재를 깨지 않고 세션 시간대 시각으로 들어간다 — 예전엔 OffsetDateTime 이 varchar 로 바인딩돼
   * "timestamp 인데 character varying" 으로 적재 전체가 실패했다. executor 는 psycopg2 timestamptz → 세션 TimeZone
   * 변환이라 같은 규칙이다.
   */
  @Test
  void offsetTimestamp_loadsAsSessionLocalTime() {
    createTable("local_py_tz", List.of(col("k", "INTEGER"), col("ts", "TIMESTAMP")));
    String stdout =
        "[{\"k\":1,\"ts\":\"2026-10-10T01:02:03+09:00\"},{\"k\":2,\"ts\":\"2026-10-10T01:02:03Z\"}]";

    assertThat(loader.load("local_py_tz", stdout, Map.of("k", "INTEGER", "ts", "TIMESTAMP")))
        .isEqualTo(2);
    java.time.format.DateTimeFormatter f =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    String kst =
        java.time.OffsetDateTime.parse("2026-10-10T01:02:03+09:00")
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(f);
    String utc =
        java.time.OffsetDateTime.parse("2026-10-10T01:02:03Z")
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(f);
    assertThat(
            dsl.fetch("SELECT ts::text FROM " + DataSchema.qualify("local_py_tz") + " ORDER BY k")
                .getValues(0, String.class))
        .containsExactly(kst, utc);
  }

  /** 변환 타입을 하나씩 담은 특수 컬럼(이름, 데이터셋 타입) — 좁은 표·넓은 표에 같은 이름으로 만든다. */
  private static final List<List<String>> SPECIAL =
      List.of(
          List.of("k", "INTEGER"),
          List.of("d", "DECIMAL"),
          List.of("dn", "DECIMAL"),
          List.of("ts", "TIMESTAMP"),
          List.of("tz", "TIMESTAMP"),
          List.of("dt", "DATE"),
          List.of("b", "BOOLEAN"),
          List.of("t", "TEXT"),
          List.of("g", "GEOMETRY"));

  private static final String SPECIAL_JSON =
      "\"d\":\"12.5\",\"dn\":NaN,\"ts\":\"2026-10-10T01:02:03.456789\","
          + "\"tz\":\"2026-10-10T01:02:03+09:00\",\"dt\":\"2026-10-10\",\"b\":\"yes\","
          + "\"t\":\"a?b'c -- d\",\"g\":\"{\\\"type\\\":\\\"Point\\\",\\\"coordinates\\\":[127.1,37.5]}\"";

  /**
   * 넓은 출력(CR7): 컬럼 70개 × 500행 = 바인드 35,000개는 jOOQ 의 PostgreSQL 인라인 문턱(32767)을 넘어 값이 SQL 리터럴로 렌더링된다.
   * 그 경로에서도 변환된 값(NaN·시각·오프셋 시각·날짜·불리언·따옴표/물음표/주석 문자열·GeoJSON)이 바인드 경로(좁은 표)와 같게 저장되는지 확인한다.
   */
  @Test
  void wideOutput_inlinedPath_storesSameValuesAsBoundPath() {
    Map<String, String> types = new java.util.LinkedHashMap<>();
    List<DatasetColumnRequest> narrowCols = new java.util.ArrayList<>();
    for (List<String> c : SPECIAL) {
      types.put(c.get(0), c.get(1));
      narrowCols.add(col(c.get(0), c.get(1)));
    }
    createTable("local_py_narrow", narrowCols);
    loader.load("local_py_narrow", "[{\"k\":0," + SPECIAL_JSON + "}]", types);

    List<DatasetColumnRequest> wideCols = new java.util.ArrayList<>(narrowCols);
    Map<String, String> wideTypes = new java.util.LinkedHashMap<>(types);
    StringBuilder filler = new StringBuilder();
    for (int i = 0; wideCols.size() < 70; i++) {
      wideCols.add(col("f" + i, "TEXT"));
      wideTypes.put("f" + i, "TEXT");
      filler.append(",\"f").append(i).append("\":\"x\"");
    }
    createTable("local_py_wide", wideCols);
    StringBuilder stdout = new StringBuilder("[");
    for (int r = 0; r < 500; r++) {
      stdout.append(r == 0 ? "" : ",").append("{\"k\":").append(r).append(',');
      stdout.append(SPECIAL_JSON).append(filler).append('}');
    }
    stdout.append(']');
    assertThat(loader.load("local_py_wide", stdout.toString(), wideTypes)).isEqualTo(500);

    String select =
        "SELECT d::text, dn::text, ts::text, tz::text, dt::text, b::text, t, ST_AsText(g) FROM ";
    var narrow = dsl.fetchOne(select + DataSchema.qualify("local_py_narrow")).intoList();
    var wideRows = dsl.fetch(select + DataSchema.qualify("local_py_wide"));
    assertThat(wideRows).hasSize(500);
    assertThat(narrow).doesNotContainNull();
    for (var row : wideRows) {
      assertThat(row.intoList()).isEqualTo(narrow);
    }
  }
}
