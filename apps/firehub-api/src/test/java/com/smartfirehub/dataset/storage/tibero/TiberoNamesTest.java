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

  /**
   * {@code dual} 은 예약 이름이다(WD-77). 미한정 {@code DUAL} 은 OWNER 세션에서 자기 스키마를 먼저 찾으므로, 같은 이름의 데이터셋이 있으면
   * 보안 게이트가 시스템 DUAL 로 보고 건너뛴 참조가 실제로는 그 데이터셋을 읽는다.
   */
  @Test
  void validateUserName_rejectsDual_caseInsensitive() {
    for (String name : new String[] {"dual", "DUAL", "Dual"}) {
      assertThatThrownBy(() -> TiberoNames.validateUserName(name))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("'dual' 은 Tibero 예약 이름이라 데이터셋 이름으로 쓸 수 없습니다.");
    }
    assertThatCode(() -> TiberoNames.validateUserName("dual_sales")).doesNotThrowAnyException();
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

  /** 스테이징 _SEQ 인덱스 이름(WD-77): 스테이징에서 파생한 13자 고정 길이, 스테이징마다 다르고 컬럼·PK 인덱스와 접두가 다르다. */
  @Test
  void stagingSeqIndexName_derivedFromStaging_fitsLimit() {
    String stg = "STG_0123456789ABCDEF01234567";
    String ix = TiberoNames.stagingSeqIndexName(stg);
    assertThat(ix).matches("^SX_[0-9A-F]{10}$");
    assertThat(ix.length()).isLessThanOrEqualTo(TiberoDialect.MAX_IDENTIFIER_LENGTH);
    assertThat(TiberoNames.stagingSeqIndexName(stg)).isEqualTo(ix);
    assertThat(TiberoNames.stagingSeqIndexName(stg.toLowerCase())).isEqualTo(ix);
    assertThat(TiberoNames.stagingSeqIndexName("STG_0123456789ABCDEF01234568")).isNotEqualTo(ix);
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

  /** 트리거 이름은 TG_ + 10자 hex(30자 이내), 대소문자와 무관하게 안정적이며 테이블마다 다르다(WD-77). */
  @Test
  void triggerName_shortStableAndPerTable() {
    String a = TiberoNames.triggerName("GENIA_T5", "orders");
    assertThat(a).matches("^TG_[0-9A-F]{10}$");
    assertThat(a.length()).isLessThanOrEqualTo(30);
    assertThat(TiberoNames.triggerName("genia_t5", "ORDERS")).isEqualTo(a);
    assertThat(TiberoNames.triggerName("GENIA_T5", "orders2")).isNotEqualTo(a);
    assertThat(TiberoNames.triggerName("GENIA_T6", "orders")).isNotEqualTo(a);
    // 시퀀스·인덱스 이름과 해시 입력이 달라 겹치지 않는다.
    assertThat(a.substring(3))
        .isNotEqualTo(TiberoNames.sequenceName("GENIA_T5", "orders").substring(3));
  }

  /**
   * 코드리뷰 5: 스테이징 이름 판정을 한 곳에 모은다. 대소문자 무시 판정(사용자 이름 거부·임시 적재 관문)과 대문자 물리 이름 판정(옮기기·삭제 관문·고아 스윕)을 같은
   * 정규식에서 만든다.
   */
  @Test
  void stagingName_anyCase_vs_physicalUppercaseOnly() {
    String physical = TiberoNames.stagingTableName();
    String logical = physical.toLowerCase(Locale.ROOT);

    assertThat(TiberoNames.isStagingName(physical)).isTrue();
    assertThat(TiberoNames.isStagingName(logical)).isTrue();
    assertThat(TiberoNames.isStagingPhysicalName(physical)).isTrue();
    assertThat(TiberoNames.isStagingPhysicalName(logical)).isFalse();
    for (String bad :
        new String[] {
          null,
          "",
          "STG_XYZ",
          "stg_report",
          "STG_0123456789ABCDEF0123456",
          physical + "\n",
          " " + physical
        }) {
      assertThat(TiberoNames.isStagingName(bad)).as(String.valueOf(bad)).isFalse();
      assertThat(TiberoNames.isStagingPhysicalName(bad)).as(String.valueOf(bad)).isFalse();
    }
  }

  @Test
  void requireStagingName_throwsInvalidTableName_withSameMessageForBothGates() {
    String physical = TiberoNames.stagingTableName();
    assertThatCode(() -> TiberoNames.requireStagingName(physical.toLowerCase(Locale.ROOT)))
        .doesNotThrowAnyException();
    assertThatCode(() -> TiberoNames.requireStagingPhysicalName(physical))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> TiberoNames.requireStagingName("orders"))
        .isInstanceOf(com.smartfirehub.dataset.exception.InvalidTableNameException.class)
        .hasMessage("'orders' 은(는) Tibero 임시(스테이징) 테이블 이름이 아닙니다");
    assertThatThrownBy(
            () -> TiberoNames.requireStagingPhysicalName(physical.toLowerCase(Locale.ROOT)))
        .isInstanceOf(com.smartfirehub.dataset.exception.InvalidTableNameException.class);
  }
}
