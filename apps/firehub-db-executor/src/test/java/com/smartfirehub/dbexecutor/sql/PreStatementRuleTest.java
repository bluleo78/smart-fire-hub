package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 선행 문장 규칙: api 가 만드는 정확한 {@code DELETE FROM "S"."T"} 만, 그 테넌트 스키마일 때만 허용한다. */
class PreStatementRuleTest {

  private static final String SCHEMA = "GENIA_T5";

  @Test
  void exactDelete_returnsExclusiveLock() {
    assertThat(PreStatementRule.lockFor("DELETE FROM \"GENIA_T5\".\"ORDERS\"", SCHEMA))
        .isEqualTo("LOCK TABLE \"GENIA_T5\".\"ORDERS\" IN EXCLUSIVE MODE");
    assertThat(PreStatementRule.lockFor("DELETE FROM \"GENIA_T5\".\"A_1$#\"", SCHEMA))
        .isEqualTo("LOCK TABLE \"GENIA_T5\".\"A_1$#\" IN EXCLUSIVE MODE");
  }

  @Test
  void rejectsLowercaseOtherSchemaWhereSemicolonAndVariants() {
    String[] bad = {
      "delete from \"GENIA_T5\".\"ORDERS\"",
      "DELETE FROM \"GENIA_T5\".\"orders\"",
      "DELETE FROM \"GENIA_T6\".\"ORDERS\"",
      "DELETE FROM \"genia_t5\".\"ORDERS\"",
      "DELETE FROM \"GENIA_T5\".\"ORDERS\" WHERE 1 = 1",
      "DELETE FROM \"GENIA_T5\".\"ORDERS\";",
      "DELETE FROM \"GENIA_T5\".\"ORDERS\"\n",
      " DELETE FROM \"GENIA_T5\".\"ORDERS\"",
      "DELETE FROM GENIA_T5.ORDERS",
      "DELETE FROM \"GENIA_T5\".\"ORDERS\"; DROP TABLE \"GENIA_T5\".\"X\"",
      "DELETE  FROM \"GENIA_T5\".\"ORDERS\"",
      "TRUNCATE TABLE \"GENIA_T5\".\"ORDERS\"",
      "",
    };
    for (String sql : bad) {
      assertThatThrownBy(() -> PreStatementRule.lockFor(sql, SCHEMA))
          .as(sql)
          .isInstanceOf(TiberoSqlGuard.Rejected.class)
          .hasMessage("허용되지 않은 선행 문장입니다.");
    }
  }

  @Test
  void rejectsNull() {
    assertThatThrownBy(() -> PreStatementRule.lockFor(null, SCHEMA))
        .isInstanceOf(TiberoSqlGuard.Rejected.class)
        .hasMessage("허용되지 않은 선행 문장입니다.");
  }
}
