package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 증분 커서 후보 조회(WD-77 Task 10, Ruling 11)만 {@code SYS._VT_TRANSACTION} 을 읽을 수 있고, 그 밖의 모든 문장은 {@code
 * SYS.}·{@code _VT_TRANSACTION} 참조를 거부하는지 고정한다. 이 테이블은 다른 테넌트 것까지 담은 인스턴스 전체 트랜잭션 메타데이터라 사용자 SQL 에
 * 열면 테넌트 간 노출이다. 허용은 이름이 아니라 문장 전체 일치다.
 *
 * <p><b>수용한 노출(accepted aggregate-only exposure):</b> 이 정확한 문장은 어느 테넌트의 SQL 로도 통과할 수 있고, 그 결과로
 * <b>인스턴스 전체에서 가장 오래 열려 있는 트랜잭션의 시작 시각 하나</b>(집계값, 다른 테넌트의 행·세션·SQL 은 아님)를 볼 수 있다. 증분 커서가 필요로 하는
 * 값이라 이 한 값의 노출은 받아들이기로 했다. 열 목록·WHERE·조인 등 변형은 모두 거부되므로 그 이상은 볼 수 없다.
 */
class TiberoSqlGuardSystemViewTest {

  private static final String SCHEMA = "GENIA_T5";
  private static final String CURSOR = TiberoSqlGuard.CURSOR_CANDIDATE_SQL;

  /** api 와 같은 고정값 파일 — 두 모듈의 문장이 어긋나면 여기서(그리고 api 쪽 테스트에서) 깨진다. */
  @Test
  void constant_matchesSharedFixtureWithApi() throws Exception {
    String fixture =
        new ObjectMapper()
            .readTree(
                Path.of("../firehub-api/src/test/resources/fixtures/tibero-cursor-candidate.json")
                    .toFile())
            .get("sql")
            .asText();
    assertThat(CURSOR).isEqualTo(fixture);
  }

  @Test
  void exactCursorQuery_isAllowed() {
    assertThatCode(() -> TiberoSqlGuard.requireAllowedStatement(CURSOR)).doesNotThrowAnyException();
    assertThatCode(() -> SqlRunner.validatedBody(CURSOR, SCHEMA)).doesNotThrowAnyException();
  }

  @Test
  void exactCursorQuery_allowedWithWhitespaceCaseAndTrailingSemicolonFolded() {
    String folded = "  " + CURSOR.toLowerCase().replace(" ", "\n  ") + " ;  ";
    assertThatCode(() -> TiberoSqlGuard.requireAllowedStatement(folded)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // 단순 참조·다른 열 목록
        "SELECT * FROM SYS._VT_TRANSACTION",
        "SELECT MIN(START_TIME) AS C FROM SYS._VT_TRANSACTION",
        "SELECT 1 FROM sys._vt_transaction",
        // 따옴표 한정 — 기존 SYS. 텍스트 검사를 피하는 형태도 테이블 이름 단어로 막는다
        "SELECT * FROM \"SYS\".\"_VT_TRANSACTION\"",
        "SELECT * FROM \"_VT_TRANSACTION\"",
        // 이름만 비슷한 것·다른 SYS 객체
        "SELECT * FROM SYS._VT_TRANSACTIONS",
        "SELECT * FROM SYS._VT_SESSION",
        "SELECT SYS.DBMS_RANDOM.VALUE FROM DUAL",
        "SELECT * FROM SYS.DBA_USERS",
      })
  void otherReferences_areRejected(String sql) {
    assertThatThrownBy(() -> TiberoSqlGuard.requireAllowedStatement(sql))
        .isInstanceOf(TiberoSqlGuard.Rejected.class);
  }

  @Test
  void cursorQueryWithExtraWhere_isRejected() {
    assertThatThrownBy(() -> TiberoSqlGuard.requireAllowedStatement(CURSOR + " WHERE SESS_ID > 0"))
        .isInstanceOf(TiberoSqlGuard.Rejected.class);
  }

  @Test
  void cursorQueryAsSubquery_isRejected() {
    String sql = "SELECT * FROM (" + CURSOR + ") c";
    assertThatThrownBy(() -> TiberoSqlGuard.requireAllowedStatement(sql))
        .isInstanceOf(TiberoSqlGuard.Rejected.class);
  }

  @Test
  void joinWithTenantTable_isRejected() {
    String sql =
        "SELECT o.\"ID\" FROM \"GENIA_T5\".\"ORDERS\" o, SYS._VT_TRANSACTION t WHERE t.SESS_ID = o.\"ID\"";
    assertThatThrownBy(() -> TiberoSqlGuard.requireAllowedStatement(sql))
        .isInstanceOf(TiberoSqlGuard.Rejected.class);
  }

  @Test
  void cursorQueryWithAppendedStatementOrComment_isRejected() {
    assertThatThrownBy(
            () -> TiberoSqlGuard.requireAllowedStatement(CURSOR + "; SELECT 1 FROM DUAL"))
        .isInstanceOf(TiberoSqlGuard.Rejected.class);
    assertThatThrownBy(() -> TiberoSqlGuard.requireAllowedStatement(CURSOR + " /* x */"))
        .isInstanceOf(TiberoSqlGuard.Rejected.class);
  }

  // ── 비교 정규화는 ASCII 만 접는다(minor 1) ─────────────────────────────────────────────

  /** ASCII 탭·개행은 공백처럼 접힌다. */
  @Test
  void asciiTabsAndNewlines_areFolded() {
    assertThatCode(() -> TiberoSqlGuard.requireAllowedStatement(CURSOR.replace(" ", "\t")))
        .doesNotThrowAnyException();
  }

  /**
   * 비ASCII 문자는 일치로 보지 않는다 — 로캘 대문자 변환이 {@code ſ}(→S)·{@code ı}(→I) 를 ASCII 로 접어 다른 문장을 같게 만들던 틈과,
   * 비ASCII 공백(NBSP) 변형. 모두 정확 일치가 아니므로 SYS 참조 거부로 떨어진다.
   */
  @ParameterizedTest
  @ValueSource(strings = {"ſ", "ı", " ", " "})
  void nonAsciiVariants_areNotExactMatch(String odd) {
    String variant =
        switch (odd) {
          case "ſ" -> CURSOR.replace("SELECT", "ſELECT").replace("SYSTIMESTAMP", "ſYSTIMESTAMP");
          case "ı" -> CURSOR.replace("TIMESTAMP", "TıMESTAMP");
          default -> CURSOR.replaceFirst(" ", odd);
        };
    assertThat(TiberoSqlGuard.isCursorCandidateQuery(variant)).isFalse();
    assertThatThrownBy(() -> TiberoSqlGuard.requireAllowedStatement(variant))
        .isInstanceOf(TiberoSqlGuard.Rejected.class);
  }

  /** 대체 따옴표 q'[..]' 로 리터럴을 바꾼 변형은 일치가 아니다. */
  @Test
  void alternativeQuotingVariant_isRejected() {
    String variant = CURSOR.replace("'TZH:TZM'", "q'[TZH:TZM]'");
    assertThat(TiberoSqlGuard.isCursorCandidateQuery(variant)).isFalse();
    assertThatThrownBy(() -> TiberoSqlGuard.requireAllowedStatement(variant))
        .isInstanceOf(TiberoSqlGuard.Rejected.class);
  }

  // ── 다른 위험 이름 검사는 계속 돈다(minor 4) ───────────────────────────────────────────

  /** DBMS_ 를 덧붙이면 정확 일치가 아니므로 거부된다. */
  @Test
  void appendedDangerousName_makesItNotExactAndIsRejected() {
    String sql = CURSOR + " WHERE DBMS_RANDOM.VALUE > 0";
    assertThat(TiberoSqlGuard.isCursorCandidateQuery(sql)).isFalse();
    assertThatThrownBy(() -> TiberoSqlGuard.requireAllowedStatement(sql))
        .isInstanceOf(TiberoSqlGuard.Rejected.class);
  }

  /** 정확 일치 허용(allow=true)이어도 SYS 외 위험 이름 검사(DBMS_ 등)는 그대로 동작한다. */
  @Test
  void dangerousNameCheck_stillRunsWhenCursorExceptionApplies() {
    assertThatThrownBy(
            () ->
                TiberoSqlGuard.rejectDangerousNames(
                    "SELECT DBMS_RANDOM.VALUE FROM SYS._VT_TRANSACTION", true))
        .isInstanceOf(TiberoSqlGuard.Rejected.class)
        .hasMessageContaining("DBMS_");
  }

  // ── SYS 한정자는 어떤 표기든 거부, 열·별칭 이름 SYS 는 허용(Ruling 14) ─────────────────────

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT * FROM \"SYS\".\"USER$\"",
        "SELECT * FROM \"SYS\".USER$",
        "SELECT * FROM SYS . USER$",
        "SELECT * FROM sys.user$",
        "SELECT * FROM SYS/*c*/.USER$",
      })
  void sysQualifierInAnySpelling_isRejected(String sql) {
    assertThatThrownBy(() -> TiberoSqlGuard.requireAllowedStatement(sql))
        .isInstanceOf(TiberoSqlGuard.Rejected.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT sys FROM GENIA_T5.t WHERE sys > 1",
        "SELECT a AS \"SYS\" FROM GENIA_T5.t",
        "SELECT * FROM \"sys\".\"X\"",
      })
  void sysAsColumnAliasOrQuotedLowercase_isAllowed(String sql) {
    assertThatCode(() -> TiberoSqlGuard.requireAllowedStatement(sql)).doesNotThrowAnyException();
  }
}
