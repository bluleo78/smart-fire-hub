package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 행 검색 결과의 원본 행 조회({@link SearchSourceReader#fetchRows})가 범위 밖 DATE/TIMESTAMP 를 다른 날짜로 바꾸지 않고 PG
 * 텍스트 원문으로 돌려주는지 검증한다(#775 — #769 와 같은 결함의 행 검색 경로).
 *
 * <p>수정 전에는 {@code dsl.fetch} 가 읽은 {@code java.sql.Date}/{@code Timestamp} 를 그대로 응답에 실어 infinity →
 * 8994-08-17, 10000년 → 0000, BC 소실, 1582 전환 공백·DST 공백 이동이 오류 없이 일어났다. 행 검색 API 응답과 AI 에이전트의 {@code
 * search_dataset_rows} 도구가 이 값을 그대로 쓴다.
 *
 * <p><b>회귀 가드</b>: 정상 값은 수정 전 경로({@code dsl.fetch} + {@code record.get})로 읽은 Java 값과 JSON 이 바이트 단위로
 * 같아야 한다.
 */
@Transactional
class SearchSourceReaderTemporalTest extends IntegrationTestBase {

  private static final String TABLE = "zz_rs_temporal_775";
  private static final List<String> COLS = List.of("label", "d", "ts");

  @Autowired private SearchSourceReader reader;
  @Autowired private DataTableService dataTableService;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  /** 라벨 → id. */
  private final Map<String, Long> ids = new LinkedHashMap<>();

  /** 라벨 → 수정 전 경로로 읽은 행(Java 값). */
  private Map<String, Map<String, Object>> preFix;

  /** 라벨 → 컬럼 → PG {@code ::text} 원문. */
  private Map<String, Map<String, String>> pgText;

  /** 라벨 → 범위 밖·재현 불가라 PG 원문이어야 하는 컬럼. */
  private Map<String, Set<String>> abnormal;

  @BeforeEach
  void setUp() {
    dataTableService.createTable(
        TABLE,
        List.of(
            new DatasetColumnRequest("label", "Label", "TEXT", null, true, false, null),
            new DatasetColumnRequest("d", "D", "DATE", null, true, false, null),
            new DatasetColumnRequest("ts", "TS", "TIMESTAMP", null, true, false, null)));
    String[][] rows = {
      {"ok1", "'2024-01-01'", "'2024-01-01 10:00:00.123'"},
      {"ok2", "'2024-06-30'", "'2024-06-30 23:59:59'"},
      {"nul", "NULL", "NULL"},
      {"big", "'10000-01-01'", "'10000-01-01 00:00'"},
      {"bc", "'0044-03-15 BC'", "'0044-03-15 10:00 BC'"},
      {"inf", "'infinity'", "'-infinity'"},
      {"ninf", "'-infinity'", "'infinity'"},
      {"gap", "'1582-10-10'", "NULL"},
      {"dst", "NULL", "'1988-05-08 02:30'"},
    };
    for (String[] r : rows) {
      Long id =
          dsl.fetchOne(
                  "INSERT INTO "
                      + DataSchema.qualify(TABLE)
                      + " (label, d, ts) VALUES ('"
                      + r[0]
                      + "', "
                      + r[1]
                      + "::date, "
                      + r[2]
                      + "::timestamp) RETURNING id")
              .get(0, Long.class);
      ids.put(r[0], id);
    }

    abnormal = new HashMap<>();
    for (String l : List.of("big", "bc", "inf", "ninf")) abnormal.put(l, Set.of("d", "ts"));
    abnormal.put("gap", Set.of("d"));
    // Asia/Seoul 은 1988-05-08 02:00~03:00 이 DST 공백 — java.sql.Timestamp 가 시각을 민다. 그 밖 시간대는 정상 값
    abnormal.put(
        "dst", "Asia/Seoul".equals(TimeZone.getDefault().getID()) ? Set.of("ts") : Set.of());

    // 수정 전 경로 — fetchRows 가 쓰던 그대로(dsl.fetch + record.get)
    preFix = new HashMap<>();
    for (var rec : dsl.fetch("SELECT label, d, ts FROM " + DataSchema.qualify(TABLE))) {
      Map<String, Object> m = new HashMap<>();
      m.put("d", rec.get("d"));
      m.put("ts", rec.get("ts"));
      preFix.put(rec.get("label", String.class), m);
    }
    pgText = new HashMap<>();
    for (var rec : dsl.fetch("SELECT label, d::text, ts::text FROM " + DataSchema.qualify(TABLE))) {
      Map<String, String> m = new HashMap<>();
      m.put("d", rec.get(1, String.class));
      m.put("ts", rec.get(2, String.class));
      pgText.put(rec.get(0, String.class), m);
    }
  }

  @Test
  void fetchRows_outOfRangeCellsArePgText_normalCellsUnchanged() throws Exception {
    Map<Long, Map<String, Object>> rows = reader.fetchRows(TABLE, COLS, ids.values());
    assertThat(rows).hasSize(ids.size());
    for (var e : ids.entrySet()) {
      String label = e.getKey();
      Map<String, Object> row = rows.get(e.getValue());
      // 요청한 컬럼만 요청 순서대로 — 기존 계약 유지
      assertThat(row.keySet()).containsExactlyElementsOf(COLS);
      assertThat(row.get("label")).isEqualTo(label);
      for (String col : List.of("d", "ts")) {
        Object v = row.get(col);
        if (abnormal.getOrDefault(label, Set.of()).contains(col)) {
          assertThat(v).as("%s.%s PG 원문", label, col).isEqualTo(pgText.get(label).get(col));
        } else {
          assertThat(objectMapper.writeValueAsString(v))
              .as("%s.%s 정상 값 형태 불변", label, col)
              .isEqualTo(objectMapper.writeValueAsString(preFix.get(label).get(col)));
        }
      }
    }
    // 대표 값은 리터럴로도 고정한다(시간대 무관 값)
    assertThat(rows.get(ids.get("inf")).get("d")).isEqualTo("infinity");
    assertThat(rows.get(ids.get("inf")).get("ts")).isEqualTo("-infinity");
    assertThat(rows.get(ids.get("big")).get("d")).isEqualTo("10000-01-01");
    assertThat(rows.get(ids.get("bc")).get("d")).isEqualTo("0044-03-15 BC");
    assertThat(rows.get(ids.get("bc")).get("ts")).isEqualTo("0044-03-15 10:00:00 BC");
    assertThat(rows.get(ids.get("gap")).get("d")).isEqualTo("1582-10-10");
    assertThat(objectMapper.writeValueAsString(rows.get(ids.get("ok1")).get("d")))
        .isEqualTo("\"2024-01-01\"");
  }

  @Test
  void fetchRows_emptyColumnsAndMissingIds_keepContract() {
    // 반환 컬럼이 없으면 id 만 조회하고, 없는 id 는 결과에 없다
    Map<Long, Map<String, Object>> rows =
        reader.fetchRows(TABLE, List.of(), List.of(ids.get("inf"), -1L));
    assertThat(rows).containsOnlyKeys(ids.get("inf"));
    assertThat(rows.get(ids.get("inf"))).isEmpty();
  }
}
