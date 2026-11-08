package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

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

  /** 안내가 있는 코드(WD-421 의 세 가지 + WD-571 추가분)만 안내를 돌려주고, 다른 코드에는 추측해 붙이지 않는다. */
  @Test
  void guide_onlyForKnownCodes() {
    assertThat(AdhocSqlErrorMessages.guide("42703"))
        .isEqualTo(AdhocSqlErrorMessages.UNDEFINED_COLUMN_GUIDE);
    assertThat(AdhocSqlErrorMessages.guide("22012"))
        .isEqualTo(AdhocSqlErrorMessages.DIVISION_BY_ZERO_GUIDE);
    assertThat(AdhocSqlErrorMessages.guide("22P02")).isNotNull();
    assertThat(AdhocSqlErrorMessages.guide("42601")).isNull();
    assertThat(AdhocSqlErrorMessages.guide("22003")).isNull();
    assertThat(AdhocSqlErrorMessages.guide(null)).isNull();
  }

  /** WD-571 — 없는 테이블·타입 불일치(함수·연산자)·날짜 형식/범위 오류에 한국어 안내가 붙는다. */
  @Test
  void guide_wd571AddedCodes() {
    assertThat(AdhocSqlErrorMessages.guide("42P01"))
        .isEqualTo(AdhocSqlErrorMessages.UNDEFINED_TABLE_GUIDE)
        .contains("테이블 이름");
    assertThat(AdhocSqlErrorMessages.guide("42883"))
        .isEqualTo(AdhocSqlErrorMessages.UNDEFINED_FUNCTION_GUIDE)
        .contains("CAST(");
    assertThat(AdhocSqlErrorMessages.guide("22007"))
        .isEqualTo(AdhocSqlErrorMessages.DATETIME_FORMAT_GUIDE)
        .contains("YYYY-MM-DD");
    assertThat(AdhocSqlErrorMessages.guide("22008"))
        .isEqualTo(AdhocSqlErrorMessages.DATETIME_FORMAT_GUIDE);
  }

  /**
   * WD-571 — 57014 는 서버 원문과 상관없이 안내 하나를 쓴다. 원문은 lc_messages 로 현지화될 수 있고(실행기도 WD-261 에서 원문 대조를 버렸다)
   * 관리자 취소(pg_cancel_backend)에서도 나오므로, 안내는 제한 시간(30초)·해결 방법과 함께 관리자 취소 가능성도 말한다.
   */
  @Test
  void guide_queryCanceled_singleGuideWithLimitSecondsAndRemedy() {
    assertThat(AdhocSqlErrorMessages.ADHOC_STATEMENT_TIMEOUT_SECONDS).isEqualTo(30);

    assertThat(AdhocSqlErrorMessages.guide("57014"))
        .isEqualTo(AdhocSqlErrorMessages.STATEMENT_TIMEOUT_GUIDE)
        .contains("30초")
        .contains("관리자")
        .contains("WHERE")
        .contains("LIMIT");
  }

  /** WD-571 — 원문이 영어가 아니어도(현지화된 lc_messages) 57014 안내는 같다 — 영어 원문 대조에 기대지 않는다. */
  @Test
  void formatWithGuide_queryCanceled_doesNotDependOnServerMessageText() {
    PSQLException english = psql("57014", "canceling statement due to statement timeout");
    PSQLException localized = psql("57014", "statement_timeout 때문에 작업을 취소합니다");
    PSQLException adminCancel = psql("57014", "canceling statement due to user request");

    assertThat(AdhocSqlErrorMessages.formatWithGuide(english))
        .startsWith(AdhocSqlErrorMessages.STATEMENT_TIMEOUT_GUIDE + "\n");
    assertThat(AdhocSqlErrorMessages.formatWithGuide(localized))
        .startsWith(AdhocSqlErrorMessages.STATEMENT_TIMEOUT_GUIDE + "\n");
    assertThat(AdhocSqlErrorMessages.formatWithGuide(adminCancel))
        .startsWith(AdhocSqlErrorMessages.STATEMENT_TIMEOUT_GUIDE + "\n");
  }

  /**
   * WD-571 — 실행기 경로: 실행기가 돌려준 원문 문자열 앞에 db_error 의 SQLState 로 고른 안내를 붙인다. 원문은 그대로 둔다(AI 에이전트·고급
   * 사용자가 읽는다). 안내가 없는 코드·SQLState 없음(검증 거부 등)이면 원문 그대로다.
   */
  @Test
  void withGuide_executorError_prependsGuideBySqlState() {
    String raw =
        "relation \"nope\" does not exist\nLINE 1: SELECT * FROM nope\n                      ^";

    assertThat(AdhocSqlErrorMessages.withGuide("42P01", raw))
        .isEqualTo(AdhocSqlErrorMessages.UNDEFINED_TABLE_GUIDE + "\n" + raw);
    assertThat(
            AdhocSqlErrorMessages.withGuide(
                "57014", "canceling statement due to statement timeout"))
        .isEqualTo(
            AdhocSqlErrorMessages.STATEMENT_TIMEOUT_GUIDE
                + "\ncanceling statement due to statement timeout");
    assertThat(AdhocSqlErrorMessages.withGuide("42601", "syntax error")).isEqualTo("syntax error");
    assertThat(AdhocSqlErrorMessages.withGuide(null, "Query must not be empty"))
        .isEqualTo("Query must not be empty");
    // 원문이 없으면 안내만 — "null" 같은 글자를 붙이지 않는다.
    assertThat(AdhocSqlErrorMessages.withGuide("42P01", null))
        .isEqualTo(AdhocSqlErrorMessages.UNDEFINED_TABLE_GUIDE);
    assertThat(AdhocSqlErrorMessages.withGuide(null, null)).isNull();
  }

  /** WD-571 — formatWithGuide: 첫 줄 한국어 안내, 둘째 줄부터 format(e) 와 글자 하나까지 같은 원문. */
  @Test
  void formatWithGuide_knownCode_prependsGuideAndKeepsFormat() {
    PSQLException e = psql("57014", "canceling statement due to statement timeout");

    String formatted = AdhocSqlErrorMessages.formatWithGuide(e);

    assertThat(formatted)
        .isEqualTo(
            AdhocSqlErrorMessages.STATEMENT_TIMEOUT_GUIDE + "\n" + AdhocSqlErrorMessages.format(e));
    assertThat(formatted.split("\n", 2)[1])
        .isEqualTo("ERROR: canceling statement due to statement timeout\nSQLState: 57014");
  }

  /** WD-571 — 안내가 없는 코드·PSQLException 이 아닌 예외는 format(e) 그대로. */
  @Test
  void formatWithGuide_unknownCodeOrNonPsql_returnsFormatAsIs() {
    PSQLException overflow = psql("22003", "integer out of range");
    assertThat(AdhocSqlErrorMessages.formatWithGuide(overflow))
        .isEqualTo(AdhocSqlErrorMessages.format(overflow))
        .startsWith("ERROR: ");

    RuntimeException plain = new RuntimeException("small error");
    assertThat(AdhocSqlErrorMessages.formatWithGuide(plain)).isEqualTo("small error");
  }

  /** WD-571 — 안내를 붙여도 2000자 상한을 지킨다. */
  @Test
  void formatWithGuide_longMessage_staysWithinMaxLength() {
    PSQLException e = psql("42P01", "relation \"" + "x".repeat(3000) + "\" does not exist");

    String formatted = AdhocSqlErrorMessages.formatWithGuide(e);

    assertThat(formatted.length()).isLessThanOrEqualTo(AdhocSqlErrorMessages.ERROR_MAX_LEN);
    assertThat(formatted)
        .startsWith(AdhocSqlErrorMessages.UNDEFINED_TABLE_GUIDE + "\n")
        .endsWith("... [truncated]");
  }

  /** 서버 구조화 오류(ServerErrorMessage)를 담은 PSQLException — 실제 드라이버가 만드는 모양(필드코드+값, NUL 구분). */
  private static PSQLException psql(String sqlState, String message) {
    return new PSQLException(
        new ServerErrorMessage("SERROR\0C" + sqlState + "\0M" + message + "\0"));
  }
}
