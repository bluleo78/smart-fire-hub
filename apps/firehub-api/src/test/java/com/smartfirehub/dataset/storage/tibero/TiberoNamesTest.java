package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Tibero 이름 규칙(대문자 인용·예약어·30자·해시 파생 이름) 단위 테스트. */
class TiberoNamesTest {

  @Test
  void physical_uppercases() {
    assertThat(TiberoNames.physical("sales_2024")).isEqualTo("SALES_2024");
  }

  @Test
  void table_quotesAndQualifies() {
    assertThat(TiberoNames.table("GENIA_T5", "sales")).isEqualTo("\"GENIA_T5\".\"SALES\"");
    assertThat(TiberoNames.column("_updated_at")).isEqualTo("\"_UPDATED_AT\"");
  }

  @Test
  void validateUserName_rejectsReservedWords() {
    for (String w : new String[] {"date", "level", "comment", "size", "user", "number"}) {
      assertThatThrownBy(() -> TiberoNames.validateUserName(w))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Tibero 예약어라 사용할 수 없는 이름입니다: " + w);
    }
    assertThatCode(() -> TiberoNames.validateUserName("sales")).doesNotThrowAnyException();
  }

  @Test
  void validateUserName_rejectsStagingShapedNames_caseInsensitive() {
    // 고아 staging 스윕이 지우는 형식이라 사용자 테이블·컬럼 이름으로 쓰면 데이터가 사라질 수 있다.
    String generated = TiberoNames.stagingTableName();
    for (String name :
        new String[] {
          "stg_0123456789abcdef01234567", "STG_0123456789ABCDEF01234567", generated.toLowerCase()
        }) {
      assertThatThrownBy(() -> TiberoNames.validateUserName(name))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("내부 스테이징 테이블용으로 예약된 이름");
    }
    // 형식이 정확히 같을 때만 막는다 — 비슷한 사용자 이름은 허용한다.
    for (String ok :
        new String[] {
          "stg_report", "stg_0123456789abcdef0123456", "stg_0123456789abcdef0123456g"
        }) {
      assertThatCode(() -> TiberoNames.validateUserName(ok)).doesNotThrowAnyException();
    }
  }

  @Test
  void validateUserName_rejectsOver30() {
    assertThatCode(() -> TiberoNames.validateUserName("a".repeat(30))).doesNotThrowAnyException();
    assertThatThrownBy(() -> TiberoNames.validateUserName("a".repeat(31)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void derivedNames_atMost30_uppercase_deterministic() {
    String s = "S".repeat(30);
    String t = "t".repeat(30);
    String c1 = "c".repeat(30);
    String c2 = "d".repeat(30);
    String ix = TiberoNames.indexName(s, t, c1);
    assertThat(ix).matches("^IX_[0-9A-F]{10}$");
    assertThat(TiberoNames.indexName(s, t, c1)).isEqualTo(ix);
    assertThat(TiberoNames.indexName(s, t, c2)).isNotEqualTo(ix);
    // 입력 대소문자와 무관하게 같은 결과
    assertThat(TiberoNames.indexName(s.toLowerCase(), t.toUpperCase(), c1)).isEqualTo(ix);
    assertThat(TiberoNames.primaryKeyIndexName(s, t)).matches("^UX_[0-9A-F]{10}$");
    String sq = TiberoNames.sequenceName(s, t);
    assertThat(sq).matches("^SQ_[0-9A-F]{10}$");
    assertThat(TiberoNames.sequenceName(s, t)).isEqualTo(sq);
    assertThat(TiberoNames.stagingSequenceName("STG_ABC")).matches("^SQ_[0-9A-F]{10}$");
    assertThat(TiberoNames.stagingSequenceName("STG_ABC"))
        .isNotEqualTo(TiberoNames.stagingSequenceName("STG_ABD"));
  }

  @Test
  void turkishDefaultLocale_doesNotChangeResults() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(new Locale("tr"));
      assertThat(TiberoNames.physical("index")).isEqualTo("INDEX");
      assertThatThrownBy(() -> TiberoNames.validateUserName("index"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("예약어");
      assertThat(TiberoNames.indexName("genia_t5", "tickets", "id"))
          .isEqualTo(runUnderRoot(() -> TiberoNames.indexName("genia_t5", "tickets", "id")));
      assertThat(TiberoNames.stagingTableName()).matches("^STG_[0-9A-F]{24}$");
      assertThat(TiberoDialect.columnDdl("integer", null)).isEqualTo("NUMBER(19)");
    } finally {
      Locale.setDefault(original);
    }
  }

  private static String runUnderRoot(java.util.function.Supplier<String> s) {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.ROOT);
      return s.get();
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void quote_rejectsQuoteAndNul() {
    assertThatThrownBy(() -> TiberoNames.quote("A\"B"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TiberoNames.quote("A\0B"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TiberoNames.table("S\"", "t"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TiberoNames.quote("OK_1")).isEqualTo("\"OK_1\"");
  }

  @Test
  void stagingTableName_unique_format() {
    String a = TiberoNames.stagingTableName();
    assertThat(a).matches("^STG_[0-9A-F]{24}$");
    assertThat(TiberoNames.stagingTableName()).isNotEqualTo(a);
  }
}
