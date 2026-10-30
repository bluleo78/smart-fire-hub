package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
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

  /**
   * WD-210: 정상 JSON 은 적재되고, 이어서 JSON 이 아닌 stdout(디버그 print 뒤 JSON)은 적재 전에 실패해 이미 있던 행이 그대로 남는다 —
   * 실패가 적재 전이라 출력에 아무것도 쓰지 않는다(REPLACE 는 러너가 임시 테이블을 버려 원본을 지킨다).
   */
  @Test
  void notJsonStdout_failsWithoutTouchingExistingRows() {
    createTable("local_py_notjson", List.of(col("k", "INTEGER")));
    Map<String, String> types = Map.of("k", "INTEGER");
    assertThat(loader.load("local_py_notjson", "[{\"k\":1},{\"k\":2}]\n", types)).isEqualTo(2);

    assertThatThrownBy(() -> loader.load("local_py_notjson", "start\n[{\"k\":3}]\n", types))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("스크립트 출력이 JSON 이 아니어서 결과를 적재하지 않았습니다.");
    assertThat(
            dsl.fetch("SELECT k FROM " + DataSchema.qualify("local_py_notjson") + " ORDER BY k")
                .getValues(0, Integer.class))
        .containsExactly(1, 2);
  }

  /**
   * WD-431: 실제 DB 에서 — 값 하나라도 컬럼 타입으로 바꿀 수 없으면 행·키·값·타입을 짚는 문구로 적재 전에 실패하고, 앞 행(변환되는 행)도 넣지 않아 이미
   * 있던 행이 그대로다(예전엔 그 칸을 NULL 로 넣고 성공했다). 불리언 'Y' 도 거짓이 아니라 실패다.
   */
  @Test
  void conversionFailure_failsWithoutTouchingExistingRows() {
    createTable("local_py_conv", List.of(col("k", "INTEGER"), col("b", "BOOLEAN")));
    Map<String, String> types = Map.of("k", "INTEGER", "b", "BOOLEAN");
    assertThat(loader.load("local_py_conv", "[{\"k\":1,\"b\":\"yes\"},{\"k\":2,\"b\":0}]", types))
        .isEqualTo(2);

    assertThatThrownBy(
            () ->
                loader.load(
                    "local_py_conv", "[{\"k\":3,\"b\":true},{\"k\":\"x\",\"b\":false}]", types))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage(
            "Python 실행 실패: 출력 JSON 의 2번째 행 'k' 값 'x' 을(를) 정수(INTEGER)로 변환할 수 없습니다."
                + " 출력 데이터셋 컬럼 타입에 맞는 값으로 출력해 주세요.");
    assertThatThrownBy(() -> loader.load("local_py_conv", "[{\"k\":4,\"b\":\"Y\"}]", types))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("참/거짓(BOOLEAN)으로 변환할 수 없습니다(허용 값: true/false/1/0/yes/no)");
    assertThat(
            dsl.fetch(
                    "SELECT k || ':' || b FROM "
                        + DataSchema.qualify("local_py_conv")
                        + " ORDER BY k")
                .getValues(0, String.class))
        .containsExactly("1:true", "2:false");
  }

  /**
   * WD-211: 실제 DB 에서 — 데이터셋에 없는 키는 적재 전에 그 키를 짚는 한국어 문구로 실패하고 이미 있던 행은 그대로다(예전엔 jOOQ bad SQL
   * grammar 원문에 내부 스키마·INSERT 가 실렸다). VARCHAR 길이 초과(22001)는 DataExceptionTranslator 사유(길이 — PG 는 이
   * 오류에 컬럼을 싣지 않는다)로 안내하고 SQL 원문은 싣지 않는다.
   */
  @Test
  void unknownKeyAndDataError_failWithKoreanMessages() {
    datasetService.createDataset(
        new CreateDatasetRequest(
            "local_py_wd211",
            "local_py_wd211",
            null,
            null,
            "TABLE",
            "SOURCE",
            List.of(
                col("k", "INTEGER"),
                new DatasetColumnRequest("code", "code", "VARCHAR", 3, true, false, null)),
            null),
        user());
    Map<String, String> types = Map.of("k", "INTEGER", "code", "VARCHAR");
    assertThat(loader.load("local_py_wd211", "[{\"k\":1,\"code\":\"abc\"}]", types)).isEqualTo(1);

    assertThatThrownBy(() -> loader.load("local_py_wd211", "[{\"nocol\":\"a\"}]", types))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage(
            "Python 실행 실패: 출력 JSON 의 'nocol' 가 출력 데이터셋 컬럼에 없습니다. 데이터셋 컬럼 이름과 같은 키로 출력해 주세요.");

    // 적재 전 실패라 기존 행이 그대로다. (아래 데이터 오류는 테스트 트랜잭션을 중단시키므로 DB 조회는 그 앞에서 한다.)
    assertThat(
            dsl.fetch("SELECT k FROM " + DataSchema.qualify("local_py_wd211") + " ORDER BY k")
                .getValues(0, Integer.class))
        .containsExactly(1);

    assertThatThrownBy(
            () -> loader.load("local_py_wd211", "[{\"k\":2,\"code\":\"toolong\"}]", types))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage(
            "Python 실행 실패: 출력 결과를 데이터셋에 적재하지 못했습니다: 글자 수 제한(최대 3자)을 넘는 값이 있습니다. 출력 JSON"
                + " 의 값이 데이터셋 컬럼의 타입·형식·길이에 맞는지 확인해 주세요.")
        .hasMessageNotContaining("INSERT")
        .hasMessageNotContaining("jOOQ");
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
