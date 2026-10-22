package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.service.executor.ExecutorClient.DbError;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.PgError;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * {@link PipelineSqlErrorMessages} 단위 테스트(WD-111) — SQLState 분류 안내, 원문 조립(주 메시지·DETAIL·HINT 만),
 * jOOQ 래핑 예외에서 내부 SQL 제거, 실행기 오류 문자열의 위치 표시 줄 제거. 실제 파이프라인 실행으로 저장되는 문구는 {@code
 * PipelineStepErrorMessageIntegrationTest} 가 검증한다.
 */
class PipelineSqlErrorMessagesTest {

  /** 서버가 보내는 구조화 오류 — 필드 코드 S·C(SQLState)·M(메시지)·D(세부)·P(위치), NUL 구분. */
  private static PSQLException pgError(String state, String message, String detail) {
    String raw =
        "SERROR\0C"
            + state
            + "\0M"
            + message
            + "\0"
            + (detail != null ? "D" + detail + "\0" : "")
            + "P15\0";
    return new PSQLException(new ServerErrorMessage(raw));
  }

  private static PgError pg(String state) {
    return new PgError(state, "msg", null, null, null);
  }

  /** 흔한 SQLState 마다 고칠 방법을 담은 안내가 고정 문구로 나온다(분류가 깨지면 일반 안내로 떨어져 실패한다). */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "23505|같은 키 값이 이미 있습니다",
        "23505|로드 전략(REPLACE·APPEND·MERGE)과 기본 키 설정을 확인하세요",
        "23502|비워 둘 수 없는데 빈 값(NULL)이 들어갔습니다",
        "23503|연결(참조)된 항목이 없거나",
        "23514|제약 조건(CHECK)",
        "21000|키별로 한 행만 나오도록",
        "42703|SQL 에 없는 컬럼 이름이 있습니다",
        "42P01|SQL 에서 참조한 테이블이 없습니다",
        "42601|SQL 문법 오류가 있습니다",
        "42883|없는 함수이거나",
        "42804|CAST(값 AS 타입)",
        "42702|테이블 별칭을 붙여",
        "42501|SQL 문제가 아니라 권한 설정 문제",
        "57014|실행 시간 제한을 넘거나",
        "40P01|다른 작업과 충돌해",
        "22012|0 으로 나누는 계산",
        "22P02|컬럼 타입에 맞지 않는 형식의 값이 있습니다. SQL 결과 값이",
        "22001|글자 수 제한",
        "22003|숫자 범위를 넘는 값",
        "XX000|데이터베이스가 SQL 실행을 거부했습니다"
      })
  void guide_classifiesCommonSqlStates(String state, String expected) {
    assertThat(PipelineSqlErrorMessages.guide(pg(state))).contains(expected);
  }

  /** 권한 오류 안내는 SQL 을 고치라고 하지 않는다(#682 — 올바른 스키마 한정자를 떼는 사고). */
  @Test
  void permissionDenied_doesNotTellUserToFixSql() {
    assertThat(PipelineSqlErrorMessages.guide(pg("42501"))).doesNotContain("SQL 을 고쳐");
  }

  /** NOT NULL 위반은 서버가 알려 준 컬럼명을 안내에 쓴다. */
  @Test
  void notNull_namesColumnWhenKnown() {
    assertThat(
            PipelineSqlErrorMessages.guide(
                new PgError("23502", "null value in column", null, null, "amount")))
        .startsWith("'amount' 컬럼은 비워 둘 수 없는데");
  }

  /** 22xxx 사유 문장은 데이터셋 쓰기와 같은 번역기(DataExceptionTranslator)에서 온다 — 길이 상한·컬럼명까지 같다. */
  @Test
  void dataError_reusesDatasetTranslatorSentence() {
    assertThat(
            PipelineSqlErrorMessages.guide(
                new PgError(
                    "22001", "value too long for type character varying(10)", null, null, "code")))
        .startsWith("'code' 컬럼에 글자 수 제한(최대 10자)을 넘는 값이 있습니다. ");
  }

  /**
   * jOOQ 래핑 예외(메시지에 실행 SQL 전체가 메아리)에서 서버 구조화 오류만 꺼내 "접두: 안내\n원인: 원문" 을 만든다 — 내부 INSERT 문·Position 은
   * 없다.
   */
  @Test
  void describe_fromJooqWrappedException_dropsInternalSqlAndPosition() {
    Exception wrapped =
        new org.jooq.exception.DataAccessException(
            "SQL [INSERT INTO data.\"out_orders\" (\"k\") SELECT k FROM data.\"src\"]; ERROR: duplicate"
                + " key value violates unique constraint \"ux_out_orders_pk\"",
            pgError(
                "23505",
                "duplicate key value violates unique constraint \"ux_out_orders_pk\"",
                "Key (k)=(1) already exists."));

    String msg =
        PipelineSqlErrorMessages.describe(PipelineSqlErrorMessages.EXECUTION_PREFIX, wrapped);

    assertThat(msg)
        .startsWith("SQL 실행 실패: 같은 키 값이 이미 있습니다.")
        .endsWith(
            "\n원인: ERROR: duplicate key value violates unique constraint \"ux_out_orders_pk\"\n"
                + "DETAIL: Key (k)=(1) already exists.")
        .doesNotContain("INSERT INTO")
        .doesNotContain("SQL [")
        .doesNotContain("Position");
  }

  /** 서버 구조화 오류가 없으면(연결 실패 등) 근본 원인 메시지만 — 바깥 jOOQ 메시지의 SQL 은 싣지 않는다. */
  @Test
  void describe_withoutServerError_usesRootCauseMessage() {
    Exception wrapped =
        new org.jooq.exception.DataAccessException(
            "SQL [SELECT * FROM (SELECT 1) AS _probe LIMIT 0]; connection refused",
            new RuntimeException("connection refused"));

    assertThat(PipelineSqlErrorMessages.describe(PipelineSqlErrorMessages.PROBE_PREFIX, wrapped))
        .isEqualTo("SQL 컬럼 타입 분석 실패: connection refused");
  }

  /** 원문에는 HINT 도 싣는다(고칠 방법의 단서). */
  @Test
  void rawText_includesDetailAndHintOnly() {
    assertThat(
            PipelineSqlErrorMessages.rawText(
                new PgError(
                    "42883", "function foo(integer) does not exist", null, "No function.", null)))
        .isEqualTo("ERROR: function foo(integer) does not exist\nHINT: No function.");
  }

  /** 실행기 구조화 오류가 있으면 예외 경로와 같은 문구다. */
  @Test
  void describeExecutorError_withStructuredError_matchesExceptionPath() {
    PgError pg = new PgError("42703", "column \"nope\" does not exist", null, null, null);
    assertThat(
            PipelineSqlErrorMessages.describeExecutorError(
                PipelineSqlErrorMessages.EXECUTION_PREFIX,
                pg,
                "column \"nope\" does not exist\nLINE 1: INSERT"))
        .isEqualTo(
            "SQL 실행 실패: SQL 에 없는 컬럼 이름이 있습니다. 컬럼 이름 철자와 참조한 테이블에 그 컬럼이 있는지 확인하세요.\n"
                + "원인: ERROR: column \"nope\" does not exist");
  }

  /** 구조화 오류가 없으면 오류 문자열을 쓰되 위치 표시 줄과 캐럿 줄만 걷어낸다(DETAIL 은 남긴다). */
  @Test
  void describeExecutorError_withoutStructuredError_stripsPositionLines() {
    String error =
        "duplicate key value violates unique constraint \"ux\"\n"
            + "DETAIL:  Key (k)=(1) already exists.\n"
            + "LINE 1: INSERT INTO \"data\".\"out\" (\"k\") SELECT 1\n"
            + "        ^\n";
    assertThat(
            PipelineSqlErrorMessages.describeExecutorError(
                PipelineSqlErrorMessages.EXECUTION_PREFIX, null, error))
        .isEqualTo(
            "SQL 실행 실패: duplicate key value violates unique constraint \"ux\"\n"
                + "DETAIL:  Key (k)=(1) already exists.");
  }

  /**
   * MERGE 키 중복(21000 + "cannot affect row a second time")은 구조화 오류의 SQLState·주 메시지로 판정해 PK 를 짚는다.
   */
  @Test
  void mergeError_structuredDuplicateKey_namesPkColumns() {
    PgError pg =
        new PgError(
            "21000",
            "ON CONFLICT DO UPDATE command cannot affect row a second time",
            null,
            null,
            null);
    assertThat(PipelineSqlErrorMessages.describeMergeError(pg, null, List.of("code")))
        .isEqualTo("SQL 결과에 같은 키(code)가 두 번 이상 나옵니다. 키별로 한 행만 나오도록 SQL을 수정하세요.");
  }

  /**
   * 같은 21000 이라도 스칼라 하위 쿼리가 여러 행을 낸 오류는 MERGE 키 안내가 아니다 — SQLState 만으로 판정하면 오역된다. 일반 안내(guide)로
   * 넘긴다.
   */
  @Test
  void mergeError_sameSqlStateOtherCause_isNotTranslated() {
    PgError pg =
        new PgError(
            "21000",
            "more than one row returned by a subquery used as an expression",
            null,
            null,
            null);
    assertThat(
            PipelineSqlErrorMessages.describeMergeError(
                pg, "cannot affect row a second time", List.of("code")))
        .isNull();
  }

  /** PK 유니크 인덱스 무효(42P10 + ON CONFLICT 문구)는 PK 재지정 안내. */
  @Test
  void mergeError_structuredNoUniqueConstraint_asksToReassignPk() {
    PgError pg =
        new PgError(
            "42P10",
            "there is no unique or exclusion constraint matching the ON CONFLICT specification",
            null,
            null,
            null);
    assertThat(PipelineSqlErrorMessages.describeMergeError(pg, null, List.of("a", "b")))
        .isEqualTo("출력 데이터셋의 PK(a, b) 유니크 인덱스가 유효하지 않습니다. 데이터셋 설정에서 PK 를 다시 지정한 뒤 다시 실행하세요.");
  }

  /** 구조화 오류가 없으면(구버전 실행기·PG 오류를 감싸지 않은 예외) 오류 문자열 부분 일치로 폴백한다. */
  @Test
  void mergeError_withoutStructuredError_fallsBackToMessageMatch() {
    assertThat(
            PipelineSqlErrorMessages.describeMergeError(
                null,
                "ERROR: ON CONFLICT DO UPDATE command cannot affect row a second time",
                List.of("k")))
        .startsWith("SQL 결과에 같은 키(k)");
    assertThat(PipelineSqlErrorMessages.describeMergeError(null, "ERROR: boom", List.of("k")))
        .isNull();
  }

  // ── Tibero(db-executor db_error) 분기(WD-77, 스펙 §5.3) ─────────────────────────────────────

  /** Tibero 코드(음수 자체 코드·Oracle 호환 양수 코드)마다 PG 판과 같은 뜻의 안내가 나온다. */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "-10007|같은 키 값이 이미 있습니다",
        "1|같은 키 값이 이미 있습니다",
        "-4008|같은 키 값이 이미 있습니다",
        "-10021|키별로 한 행만 나오도록",
        "30926|키별로 한 행만 나오도록",
        "-10005|비워 둘 수 없는데 빈 값(NULL)이 들어갔습니다",
        "1400|비워 둘 수 없는데 빈 값(NULL)이 들어갔습니다",
        "-11048|글자 수 제한",
        "12899|글자 수 제한",
        "-8033|SQL 에서 참조한 테이블이 없습니다",
        "942|SQL 에서 참조한 테이블이 없습니다",
        "-99999|데이터베이스가 SQL 실행을 거부했습니다"
      })
  void tiberoError_classifiesKnownCodesLikePg(int code, String expected) {
    String msg =
        PipelineSqlErrorMessages.describeTiberoError(
            PipelineSqlErrorMessages.EXECUTION_PREFIX,
            new DbError(code, "JDBC" + code + ":raw"),
            "JDBC" + code + ":raw");
    assertThat(msg).startsWith("SQL 실행 실패: ").contains(expected);
    // 원문은 실행기가 준 저장소 메시지 그대로 — 구분 줄 뒤에 둔다(PG 판과 같은 모양).
    assertThat(msg).endsWith("\n원인: JDBC" + code + ":raw");
  }

  /** PG 판과 같은 안내 문장을 그대로 쓴다 — 두 판의 문구가 갈라지지 않게. */
  @Test
  void tiberoError_guideIsSameSentenceAsPg() {
    assertThat(
            PipelineSqlErrorMessages.describeTiberoError(
                PipelineSqlErrorMessages.EXECUTION_PREFIX,
                new DbError(-10007, "JDBC-10007:UNIQUE constraint violation"),
                null))
        .isEqualTo(
            "SQL 실행 실패: "
                + PipelineSqlErrorMessages.guide(pg("23505"))
                + "\n원인: JDBC-10007:UNIQUE constraint violation");
  }

  /** db_error 가 없으면(검증 거부·연결 실패처럼 DB 오류가 아니면) 실행기 문구를 그대로 쓴다 — 안내를 추측해 붙이지 않는다. */
  @Test
  void tiberoError_withoutDbError_usesExecutorMessage() {
    assertThat(
            PipelineSqlErrorMessages.describeTiberoError(
                PipelineSqlErrorMessages.PROBE_PREFIX, null, "테넌트 저장소에 연결하지 못했습니다."))
        .isEqualTo("SQL 컬럼 타입 분석 실패: 테넌트 저장소에 연결하지 못했습니다.");
  }

  /** MERGE 원본 키 중복(실서버 측정 -10021·-4008, Oracle 30926)은 PG 의 21000 과 같은 문구로 출력 PK 를 짚는다. */
  @ParameterizedTest
  @CsvSource({
    "-10021,JDBC-10021:Inconsistent set of rows in source tables.",
    "-4008,JDBC-4008:Duplicate unique key found during multi insert.",
    "30926,ORA-30926: unable to get a stable set of rows in the source tables"
  })
  void tiberoMergeError_sourceDuplicate_sameMessageAsPg(int code, String message) {
    String pgMessage =
        PipelineSqlErrorMessages.describeMergeError(
            new PgError("21000", MergeSqlBuilder.DUPLICATE_KEY_PG_MESSAGE, null, null, null),
            null,
            List.of("a", "b"));
    assertThat(
            PipelineSqlErrorMessages.describeTiberoMergeError(
                new DbError(code, message), message, List.of("a", "b")))
        .isNotNull()
        .isEqualTo(pgMessage);
  }

  /** 구조화 코드가 없으면 메시지 표기(JDBC-10021:)로 폴백하고, MERGE 문맥이 아닌 오류는 null(호출부가 일반 문구를 쓴다). */
  @Test
  void tiberoMergeError_fallbackAndNonMergeCodes() {
    assertThat(
            PipelineSqlErrorMessages.describeTiberoMergeError(
                null, "JDBC-10021:Inconsistent set of rows in source tables.", List.of("k")))
        .startsWith("SQL 결과에 같은 키(k)");
    assertThat(
            PipelineSqlErrorMessages.describeTiberoMergeError(
                new DbError(-10007, "JDBC-10007:UNIQUE constraint violation"), null, List.of("k")))
        .isNull();
    assertThat(PipelineSqlErrorMessages.describeTiberoMergeError(null, "boom", List.of("k")))
        .isNull();
  }
}
