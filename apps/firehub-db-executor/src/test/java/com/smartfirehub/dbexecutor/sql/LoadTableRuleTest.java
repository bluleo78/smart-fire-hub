package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 적재 대상 검증(스펙 §3.3): Tibero 적재 대상은 그 테넌트 스키마의 스테이징 테이블({@code STG_<24hex>})뿐이다. */
class LoadTableRuleTest {

  private static final String STG = "STG_0123456789ABCDEF01234567";

  @Test
  void stagingTable_isQuotedWithSchema() {
    assertThat(LoadTableRule.requireStaging(STG, "GENIA_T5"))
        .isEqualTo("\"GENIA_T5\".\"" + STG + "\"");
  }

  @Test
  void rejectsOrdinaryTables_wrongShapes_andInjection() {
    String[] bad = {
      "ORDERS",
      "orders",
      "stg_0123456789abcdef01234567", // 소문자 — 적재 대상은 대문자 물리 이름만
      "STG_0123456789ABCDEF0123456", // 23자
      "STG_0123456789ABCDEF012345678", // 25자
      "STG_0123456789ABCDEF0123456G", // 16진 아님
      "\"GENIA_T5\".\"" + STG + "\"", // 따옴표 한정 이름
      "OTHER." + STG, // 다른 스키마 한정
      STG + "\"; DROP TABLE X; --",
      STG + "\n",
      " " + STG,
      "",
    };
    for (String t : bad) {
      assertThatThrownBy(() -> LoadTableRule.requireStaging(t, "GENIA_T5"))
          .as(t)
          .isInstanceOf(TiberoSqlGuard.Rejected.class)
          .hasMessage(LoadTableRule.REJECTED);
    }
    assertThatThrownBy(() -> LoadTableRule.requireStaging(null, "GENIA_T5"))
        .isInstanceOf(TiberoSqlGuard.Rejected.class);
  }

  @Test
  void rejectsMalformedSchema() {
    for (String s : new String[] {null, "", "a\"b", "GENIA T5", "genia_t5"}) {
      assertThatThrownBy(() -> LoadTableRule.requireStaging(STG, s))
          .as(String.valueOf(s))
          .isInstanceOf(TiberoSqlGuard.Rejected.class);
    }
  }
}
