package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.util.SqlLexicalMask;
import com.smartfirehub.pipeline.service.step.SqlOutputColumns;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 출력 데이터셋 비우기 문장을 만드는 유일한 지점.
 *
 * <p>TRUNCATE 가 아닌 DELETE 를 쓰는 이유: 실행기 테넌트 롤에 TRUNCATE 권한이 없고(실측), DELETE 는 MVCC 라 트랜잭션이 끝날 때까지 다른
 * 조회가 이전 데이터를 계속 본다. firehub-executor 의 선행 문장 검증 정규식과 형태가 정확히 일치해야 한다.
 *
 * <p><b>{@link DataSchema#qualify}를 그대로 쓰지 않는 이유.</b> {@code qualify()} 는 스키마 부분을 인용하지 않는 형태(스키마 뒤에
 * 바로 점 + 인용된 테이블명)를 돌려준다(대부분의 SQL 조립에는 문제 없다 — PostgreSQL 은 소문자 미인용 식별자와 인용된 소문자 식별자를 동일하게 해석한다).
 * 그런데 firehub-executor 의 선행 문장 화이트리스트는 {@code ^DELETE FROM "(data|data_t\d+)"\."[a-z0-9_]+"( WHERE
 * false)?$}(뒤 접미사는 WD-426 잠금 전용 형태)로 스키마·테이블 <b>양쪽 모두</b> 인용을 요구한다 — 실측(Task 3, sql_validator.py)
 * 결과다. {@code qualify()} 자체를 바꾸면 그 출력 형태에 의존하는 기존 계약(예: {@code DataSchemaResolutionTest} 의 스키마 미인용
 * 단언)이 깨지므로, 여기서는 스키마·테이블명을 직접 인용해 조립한다 — {@link DataSchema#current()} 로 스키마 식별자를 얻는 것은 그대로 "스키마명을
 * 유일한 지점에서 얻는다"는 규약을 따른다.
 *
 * <p>WD-426 부터 형태가 둘 더 있다 — 자기 출력을 읽는 PG SELECT + REPLACE 스텝은 비우기를 선행 문장이 아니라 본 문장 안에서 한다({@link
 * #reloadFromOwnOutput}). 그때 선행 문장은 출력 직렬화 잠금만 잡는 {@link #lockOnly} 이다.
 */
public final class OutputClearStatement {

  /**
   * 잠금 전용 선행 문장의 접미사(WD-426). {@link #deleteAll} 형태 뒤에 이 문자열이 <b>정확히</b> 붙은 문장만 잠금 전용으로 받는다 —
   * firehub-executor 의 {@code _PRE_STATEMENT_PATTERN} 과 API {@code SqlScriptExecutor} 의 선행 문장 검사가
   * 같은 리터럴을 쓴다(대소문자·공백·뒤 개행 변형은 모두 거부).
   */
  public static final String LOCK_ONLY_SUFFIX = " WHERE false";

  /**
   * 자기 출력 다시 채우기 문장의 사용자 SELECT CTE 이름 — 사용자 SQL 의 CTE 와 겹치지 않을 만한 이름(안쪽 CTE 가 가려도 무해하다).
   *
   * <p>이름에 firehub-executor 블록리스트 키워드(대문자 부분 문자열 검사 — DROP·ALTER·CREATE·GRANT·COPY·LOAD 등)가 들어가면
   * 실행기가 문장 전체를 거부한다 — 처음 지은 {@code _fh_reload_src} 는 "re<b>LOAD</b>" 때문에 실행기 경로에서만
   * 실패했다(test_sql_executor 가 이 문장 그대로를 실행기 검증에 넣어 고정한다).
   */
  private static final String RELOAD_SOURCE_CTE = "\"_fh_self_src\"";

  /** 자기 출력 다시 채우기 문장의 비우기 CTE 이름. */
  private static final String RELOAD_CLEAR_CTE = "\"_fh_self_clear\"";

  /** 다시 채우기 문장의 머리 — 사용자 SELECT 는 다음 줄부터 시작한다(아래 {@link #reloadFromOwnOutput} 의 개행 이유). */
  private static final String RELOAD_HEAD = "WITH " + RELOAD_SOURCE_CTE + " AS (\n";

  /** 다시 채우기 문장에서 사용자 SELECT 가 끝나는 지점 — 이 뒤는 러너가 만든 고정 꼬리다. */
  private static final String RELOAD_TAIL_START = "\n), " + RELOAD_CLEAR_CTE + " AS (DELETE FROM ";

  /** 꼬리의 비우기 대상 뒤 — 비운 행마다 1 을 돌려주고, 같은 출력을 대상으로 한 적재가 이어진다. */
  private static final String RELOAD_CLEAR_END = " RETURNING 1) INSERT INTO ";

  /**
   * 꼬리의 끝 — 컬럼 목록 뒤에 사용자 SELECT 결과를 넣는다. 끝의 count 조건은 행을 거르지 않고(늘 참) 비우기를 적재보다 먼저 끝내게 하는 데이터 의존이다 —
   * 없으면 PK 출력에서 23505 로 실패한다(이유는 {@link #reloadFromOwnOutput}).
   */
  private static final String RELOAD_LOAD_TAIL =
      ") SELECT * FROM "
          + RELOAD_SOURCE_CTE
          + " WHERE (SELECT count(*) FROM "
          + RELOAD_CLEAR_CTE
          + ") >= 0";

  /**
   * 다시 채우기 문장의 꼬리(마지막 꼬리 시작 표지부터 끝까지) 판별식 — 조립({@link #reloadFromOwnOutput})이 쓰는 같은 조각 상수를 그대로 인용해
   * 만들어 조립과 판별이 갈리지 않는다. 비우기·적재 대상은 같은 테이블이어야 하고(역참조), 컬럼 목록은 인용 식별자만 받는다. 스키마는 테넌트마다 달라 그룹으로 받고
   * {@link #reloadSourceOf} 가 현재 테넌트 스키마와 대조한다 — 그래서 패턴을 한 번만 컴파일한다.
   */
  private static final Pattern RELOAD_TAIL =
      Pattern.compile(
          Pattern.quote(RELOAD_TAIL_START)
              + "(?<schema>[a-z0-9_]+)\\.\"(?<table>[a-z0-9_]+)\""
              + Pattern.quote(RELOAD_CLEAR_END)
              + "\\k<schema>\\.\"\\k<table>\" \\(\"(?:[^\"]|\"\")+\"(?:, \"(?:[^\"]|\"\")+\")*"
              + Pattern.quote(RELOAD_LOAD_TAIL));

  private OutputClearStatement() {}

  /**
   * 출력 비우기 없이 그 출력의 직렬화 잠금(#731)만 잡게 하는 선행 문장(WD-426) — {@code DELETE FROM "<schema>"."<table>"
   * WHERE false}. 실행기(firehub-executor·로컬 {@code SqlScriptExecutor})는 선행 문장마다 대상 테이블의 advisory 잠금을
   * <b>별도 문장으로</b> 먼저 잡으므로, 이 문장을 보내면 {@link #deleteAll} 과 같은 키로 잠근 뒤 아무 행도 지우지 않는다. 비우기는 {@link
   * #reloadFromOwnOutput} 이 본 문장 안에서 한다 — 잠금이 본 문장(과 그 스냅숏)보다 먼저 잡혀야 뒤 실행이 앞 실행의 커밋을 읽는다.
   *
   * @param tableName {@link #deleteAll} 과 같다
   */
  public static String lockOnly(String tableName) {
    return deleteAll(tableName) + LOCK_ONLY_SUFFIX;
  }

  /** 잠금 전용 선행 문장인가 — 끝에 {@link #LOCK_ONLY_SUFFIX} 가 붙었는지만 본다(나머지 형태 검사는 실행기가 한다). */
  public static boolean isLockOnly(String preStatement) {
    return preStatement != null && preStatement.endsWith(LOCK_ONLY_SUFFIX);
  }

  /** 잠금 전용 접미사가 끝에 정확히 붙어 있으면 한 번만 뗀다(아니면 그대로) — 실행기가 잠금 키·선행 문장 형태를 전체 비우기와 같은 규칙으로 보게 한다. */
  public static String stripLockOnly(String statement) {
    return isLockOnly(statement)
        ? statement.substring(0, statement.length() - LOCK_ONLY_SUFFIX.length())
        : statement;
  }

  /**
   * 자기 출력을 읽는 PG SELECT + REPLACE 스텝의 본 문장(WD-426) — 사용자 SELECT 가 비우기 <b>전</b> 출력을 읽고, 옛 행을 지운 뒤
   * 결과를 넣는다. 형태:
   *
   * <pre>{@code
   * WITH "_fh_self_src" AS (
   * <사용자 SELECT>
   * ), "_fh_self_clear" AS (DELETE FROM <출력> RETURNING 1) INSERT INTO <출력> (<컬럼>)
   *   SELECT * FROM "_fh_self_src" WHERE (SELECT count(*) FROM "_fh_self_clear") >= 0
   * }</pre>
   *
   * (실제로는 꼬리가 한 줄이다.)
   *
   * <p><b>왜 한 문장인가.</b> 예전처럼 비우기를 선행 문장으로 먼저 보내면 같은 트랜잭션의 사용자 SELECT 는 자기가 지운 결과(빈 테이블)를 읽어 늘 0행인 채
   * 완료로 끝났다. 한 문장의 하위 문장(WITH 안 SELECT·DELETE 와 본 INSERT)은 모두 같은 스냅숏·같은 명령 번호로 돌기 때문에, 사용자 SELECT 는
   * DELETE 가 지운 행을 여전히 보고(비우기 전 데이터), DELETE 는 이번 INSERT 가 넣은 행을 보지 못해(옛 행만 지운다) 결과가 "옛 출력으로 계산한 새
   * 출력"이 된다.
   *
   * <p><b>왜 {@code WHERE (SELECT count(*) FROM 비우기) >= 0} 인가.</b> 본 문장이 참조하지 않는 데이터 변경 CTE 는
   * PostgreSQL 이 본 문장을 다 돈 <b>뒤에</b>(ExecPostprocessPlan) 마저 실행한다. 그러면 INSERT 가 아직 지워지지 않은 옛 행과 PK
   * 유니크 인덱스({@code ux_<table>_pk})에서 부딪혀 23505 로 실패한다(실측). 조건의 InitPlan 은 첫 행을 넣기 전에 한 번 평가되고,
   * count 가 비우기 결과를 끝까지 읽어야 하므로 DELETE 가 INSERT 보다 먼저 끝난다 — 실행 순서(문서상 "예측 불가")가 아니라 데이터 의존으로 보장한다.
   * 대가는 RETURNING 1 의 아주 작은 행을 담는 임시 저장(행당 십여 바이트, 크면 임시 파일)이다. 사용자 SELECT 쪽은 MATERIALIZED 를 쓰지 않는다
   * — 결과 전체 행을 임시 저장하게 돼 큰 출력에서 넘친다.
   *
   * <p>사용자 SELECT 는 자기 줄에 얹는다(앞뒤 개행) — 끝의 한 줄 주석({@code -- note})이 닫는 괄호와 꼬리를 통째로 주석 처리하지 않게 한다. 끝
   * 세미콜론·주석은 {@link MergeSqlBuilder} 와 같은 {@link SqlLexicalMask#stripTrailingCommentsAndSemicolons}
   * 로 걷는다.
   *
   * <p>JSqlParser 5.0 은 데이터 변경 CTE 를 파싱하지 못한다 — 그래서 로컬 실행 경로({@code SqlScriptExecutor})는 이 문장을 통째로
   * 검증하지 않고 {@link #reloadSourceOf} 로 정확한 형태를 확인한 뒤 안쪽 사용자 SELECT 만 검증한다.
   *
   * @param tableName 출력 테이블명(스키마 미포함, 인용 없음)
   * @param columns 적재할 출력 컬럼(사용자 SELECT 결과 순서와 같다 — {@code SELECT *} 가 위치로 맞춘다)
   * @param selectSql 검증·보안 판정을 통과한 사용자 SELECT
   */
  public static String reloadFromOwnOutput(
      String tableName, List<String> columns, String selectSql) {
    String qualified = DataSchema.qualify(tableName);
    return RELOAD_HEAD
        + SqlLexicalMask.stripTrailingCommentsAndSemicolons(selectSql)
        + RELOAD_TAIL_START
        + qualified
        + RELOAD_CLEAR_END
        + qualified
        + " ("
        + SqlOutputColumns.quotedColumnList(columns)
        + RELOAD_LOAD_TAIL;
  }

  /**
   * {@link #reloadFromOwnOutput} 이 만든 문장을 알아보고 출력 테이블명과 안쪽 사용자 SELECT 를 돌려준다(WD-426). 현재 테넌트 스키마의
   * 출력을 비우고 같은 출력에 넣는 <b>정확히 그 형태</b>가 아니면 빈 값이다 — 호출자({@code SqlScriptExecutor})가 안쪽 SELECT 를 검증기에
   * 다시 넣는다.
   *
   * <p>꼬리는 마지막 꼬리 시작 표지부터 문자열 끝까지 고정 형태와 통째로 맞아야 한다 — 비우기·적재 대상이 같은 테이블이고, 컬럼 목록은 인용 식별자만 들어간다. 안쪽
   * SELECT 에 같은 문구가 문자열로 들어 있어도 마지막 표지를 쓰므로 꼬리를 잘못 고르지 않는다.
   */
  public static Optional<ReloadParts> reloadSourceOf(String statement) {
    if (statement == null || !statement.startsWith(RELOAD_HEAD)) {
      return Optional.empty();
    }
    int tailStart = statement.lastIndexOf(RELOAD_TAIL_START);
    if (tailStart < RELOAD_HEAD.length()) {
      return Optional.empty();
    }
    Matcher matcher = RELOAD_TAIL.matcher(statement.substring(tailStart));
    if (!matcher.matches() || !matcher.group("schema").equals(DataSchema.current())) {
      return Optional.empty();
    }
    return Optional.of(
        new ReloadParts(
            matcher.group("table"), statement.substring(RELOAD_HEAD.length(), tailStart)));
  }

  /**
   * {@link #reloadSourceOf} 결과.
   *
   * @param tableName 비우고 다시 채우는 출력 테이블명(스키마 미포함)
   * @param selectSql 안쪽 사용자 SELECT(검증 대상)
   */
  public record ReloadParts(String tableName, String selectSql) {}

  /**
   * 출력 테이블 전체를 비우는 DELETE 문을 만든다.
   *
   * @param tableName 비울 테이블명(스키마 미포함, 인용 없음). 큰따옴표는 이중화해 인젝션을 막는다.
   * @return {@code DELETE FROM "<schema>"."<table>"} 형태의 문장 — 세미콜론·WHERE 없음.
   */
  public static String deleteAll(String tableName) {
    String schema = DataSchema.current();
    String escapedTable = tableName.replace("\"", "\"\"");
    return "DELETE FROM \"" + schema + "\".\"" + escapedTable + "\"";
  }
}
