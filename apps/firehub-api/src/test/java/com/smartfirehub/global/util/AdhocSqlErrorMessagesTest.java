package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

/**
 * {@link AdhocSqlErrorMessages} 단위 테스트(WD-421) — PSQLException 이 없는 예외의 jOOQ 메아리 제거와 안내 선택을 확인한다.
 * 실제 DB 오류 포맷은 {@code DataTableQueryServiceTest}·{@code AnalyticsQueryExecutionServiceExtTest} 가
 * 확인한다.
 */
class AdhocSqlErrorMessagesTest {

  /** 실행 SQL 을 알면 대괄호·여러 줄이 섞인 SQL 메아리도 그 문자열 그대로 정확히 뗀다. */
  @Test
  void formatForUser_nonPsqlException_stripsExactJooqEcho() {
    String executed = "SELECT arr[1], x\nFROM t\nLIMIT 1000";
    Exception e = new DataAccessException("SQL [" + executed + "]; boom [detail]");

    assertThat(AdhocSqlErrorMessages.formatForUser(e, executed)).isEqualTo("boom [detail]");
  }

  /** 실행 SQL 을 모르면 정규식으로 맨 앞 메아리를 뗀다(여러 줄 포함). */
  @Test
  void stripJooqSqlEcho_unknownSql_fallsBackToRegex() {
    assertThat(AdhocSqlErrorMessages.stripJooqSqlEcho("SQL [SELECT 1\nLIMIT 1000]; oops", null))
        .isEqualTo("oops");
    assertThat(AdhocSqlErrorMessages.stripJooqSqlEcho("plain message", "SELECT 1"))
        .isEqualTo("plain message");
  }

  /** 안내는 42703·22012·22P02 세 가지만 — 다른 코드에는 추측해 붙이지 않는다. */
  @Test
  void guide_onlyForThreeCommonCodes() {
    assertThat(AdhocSqlErrorMessages.guide("42703"))
        .isEqualTo(AdhocSqlErrorMessages.UNDEFINED_COLUMN_GUIDE);
    assertThat(AdhocSqlErrorMessages.guide("22012"))
        .isEqualTo(AdhocSqlErrorMessages.DIVISION_BY_ZERO_GUIDE);
    assertThat(AdhocSqlErrorMessages.guide("22P02")).isNotNull();
    assertThat(AdhocSqlErrorMessages.guide("42P01")).isNull();
    assertThat(AdhocSqlErrorMessages.guide(null)).isNull();
  }
}
