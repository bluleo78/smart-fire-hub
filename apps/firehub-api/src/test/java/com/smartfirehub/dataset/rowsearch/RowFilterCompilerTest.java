package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 필터 → SQL 조각: 화이트리스트·타입 변환·바인딩을 검증한다(SQL 주입 방어의 핵심). */
class RowFilterCompilerTest {

  private final Map<String, String> types =
      Map.of("status", "VARCHAR", "cnt", "INTEGER", "amount", "DECIMAL", "created", "DATE",
          "done", "BOOLEAN", "geom", "GEOMETRY", "ts", "TIMESTAMP");

  @Test
  void compilesAllOperators_withBoundParams() {
    var f =
        new RowFilter(
            List.of(
                new RowFilter.Condition("status", "eq", "미처리"),
                new RowFilter.Condition("cnt", "gte", 3),
                new RowFilter.Condition("amount", "lt", "10.5"),
                new RowFilter.Condition("created", "gte", "2025-12-01"),
                new RowFilter.Condition("status", "in", List.of("a", "b")),
                new RowFilter.Condition("done", "is_null", null)));

    CompiledFilter c = RowFilterCompiler.compile(f, types);

    assertThat(c.sql())
        .isEqualTo(
            "t.\"status\" = ? AND t.\"cnt\" >= ? AND t.\"amount\" < ? AND t.\"created\" >= ?"
                + " AND t.\"status\" = ANY(?) AND t.\"done\" IS NULL");
    assertThat(c.params()).hasSize(5);
    assertThat(c.params().get(1)).isEqualTo(3L);
    assertThat(c.params().get(2)).isEqualTo(new BigDecimal("10.5"));
    assertThat(c.params().get(3)).isEqualTo(LocalDate.parse("2025-12-01"));
    assertThat(c.params().get(4)).isInstanceOf(String[].class);
    assertThat((String[]) c.params().get(4)).containsExactly("a", "b");
    var ints = RowFilterCompiler.compile(new RowFilter(List.of(new RowFilter.Condition("cnt", "in", List.of(1, "2")))), types);
    assertThat(ints.params().get(0)).isInstanceOf(Long[].class);
  }

  @Test
  void emptyFilter_compilesToNone() {
    assertThat(RowFilterCompiler.compile(RowFilter.none(), types).isEmpty()).isTrue();
  }

  @Test
  void rejectsUnknownColumn_systemColumn_geometry_badOp_badValue() {
    assertThatThrownBy(() -> one("nope", "eq", "x")).hasMessageContaining("filters[0]").hasMessageContaining("nope");
    assertThatThrownBy(() -> one("id", "eq", 1)).hasMessageContaining("filters[0]");
    assertThatThrownBy(() -> one("geom", "is_null", null)).hasMessageContaining("GEOMETRY");
    assertThatThrownBy(() -> one("status", "like", "x")).hasMessageContaining("연산자");
    assertThatThrownBy(() -> one("cnt", "eq", "abc")).hasMessageContaining("INTEGER");
    assertThatThrownBy(() -> one("status", "in", List.of())).hasMessageContaining("in");
    // 따옴표로 식별자를 탈출하려는 입력은 화이트리스트에서 막힌다
    assertThatThrownBy(() -> one("status\" OR 1=1 --", "eq", "x")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void timestamp_acceptsDateOnly_isoLocal_spaceSeparated() {
    assertThat(one("ts", "gte", "2025-12-01").params().get(0)).isEqualTo(LocalDateTime.of(2025, 12, 1, 0, 0));
    assertThat(one("ts", "gte", "2025-12-01T09:30:15").params().get(0))
        .isEqualTo(LocalDateTime.of(2025, 12, 1, 9, 30, 15));
    assertThat(one("ts", "gte", "2025-12-01 09:30").params().get(0)).isEqualTo(LocalDateTime.of(2025, 12, 1, 9, 30));
    assertThat(one("ts", "gte", "2025-12-01 09:30:15").params().get(0))
        .isEqualTo(LocalDateTime.of(2025, 12, 1, 9, 30, 15));
  }

  @Test
  void timestamp_zonedValues_areNormalizedToUtcWallClock() {
    // 오프셋·Z 가 붙은 값은 같은 순간의 UTC 벽시계 시각으로 바꾼다(JVM 기본 시간대와 무관해야 한다).
    assertThat(one("ts", "gte", "2025-12-01T09:00:00Z").params().get(0)).isEqualTo(LocalDateTime.of(2025, 12, 1, 9, 0));
    assertThat(one("ts", "gte", "2025-12-01T09:00:00+09:00").params().get(0))
        .isEqualTo(LocalDateTime.of(2025, 12, 1, 0, 0));
    var in = RowFilterCompiler.compile(
        new RowFilter(List.of(new RowFilter.Condition("ts", "in", List.of("2025-12-01", "2025-12-01T01:00:00+01:00")))),
        types);
    assertThat((LocalDateTime[]) in.params().get(0))
        .containsExactly(LocalDateTime.of(2025, 12, 1, 0, 0), LocalDateTime.of(2025, 12, 1, 0, 0));
    assertThatThrownBy(() -> one("ts", "gte", "2025-13-01")).hasMessageContaining("TIMESTAMP");
    assertThatThrownBy(() -> one("ts", "gte", "어제")).hasMessageContaining("TIMESTAMP");
  }

  @Test
  void integer_acceptsWholeNumberDecimals_rejectsFractions() {
    // 에이전트 JSON 의 5.0 은 Jackson 이 Double 로 읽어 "5.0" 이 된다 — 정수값이면 받아야 한다.
    assertThat(one("cnt", "eq", 5.0).params().get(0)).isEqualTo(5L);
    assertThat(one("cnt", "eq", "5.00").params().get(0)).isEqualTo(5L);
    assertThat(one("cnt", "eq", "-3").params().get(0)).isEqualTo(-3L);
    assertThatThrownBy(() -> one("cnt", "eq", 5.5)).hasMessageContaining("INTEGER");
    assertThatThrownBy(() -> one("cnt", "eq", "1e400")).hasMessageContaining("INTEGER");
  }

  private CompiledFilter one(String col, String op, Object v) {
    return RowFilterCompiler.compile(new RowFilter(List.of(new RowFilter.Condition(col, op, v))), types);
  }
}
