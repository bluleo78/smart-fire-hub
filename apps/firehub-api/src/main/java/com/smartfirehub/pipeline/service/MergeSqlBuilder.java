package com.smartfirehub.pipeline.service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 출력 방식 MERGE 의 INSERT ... ON CONFLICT 문을 조립한다(순수 함수).
 *
 * <p>사용자 SELECT 를 서브쿼리로 감싸 INSERT 대상 컬럼만 고른다 — 사용자 SQL 이 JOIN ... ON 으로 끝날 때
 * ON CONFLICT 와 문법이 섞이는 것을 막고, 출력에 없는 여분 컬럼을 안전하게 버린다.
 * 값이 같은 행은 갱신하지 않는다(IS DISTINCT FROM) — 그래야 _updated_at 이 쓸데없이 바뀌어 하류 증분 스텝이
 * 전량 재처리하는 일이 없다.
 */
public final class MergeSqlBuilder {

  /** PostgreSQL 이 한 문장 안 같은 키 두 번 갱신 시 내는 오류 문구 — 사용자용 메시지로 번역할 때 쓴다. */
  public static final String DUPLICATE_KEY_PG_MESSAGE = "cannot affect row a second time";

  /**
   * PostgreSQL 이 {@code ON CONFLICT (컬럼...)} 에 대응하는 유니크/제외 제약이 없을 때 내는 오류 문구.
   * (Fix round 1, must 4)
   *
   * <p>저장·실행 시점 모두 {@code dataset_column.is_primary_key} 메타데이터로 PK 존재를 확인하지만,
   * {@code createPrimaryKeyIndexConcurrently} 로 만든 {@code ux_<table>_pk} 인덱스가 (동시 생성 실패 등으로)
   * INVALID 상태로 남을 수 있다 — 메타데이터는 여전히 true 인데 실제 유니크 제약은 없는 상태다. 이때
   * PostgreSQL 은 이 문구로 거부한다. 사용자용 메시지로 번역할 때 이 상수를 함께 검사한다.
   */
  public static final String NO_UNIQUE_CONSTRAINT_PG_MESSAGE =
      "there is no unique or exclusion constraint matching the ON CONFLICT specification";

  private MergeSqlBuilder() {}

  public static String build(
      String qualifiedTable, List<String> insertColumns, List<String> pkColumns, String selectSql) {
    for (String pk : pkColumns) {
      if (!insertColumns.contains(pk)) {
        throw new IllegalArgumentException("MERGE 키 컬럼이 SELECT 결과에 없습니다: " + pk);
      }
    }
    String cols = quoteJoin(insertColumns, "");
    String keys = quoteJoin(pkColumns, "");
    List<String> nonKeys = insertColumns.stream().filter(c -> !pkColumns.contains(c)).toList();

    // SqlValidator 는 후행 세미콜론을 허용한다(SqlValidatorTest.allows_trailing_semicolon 로 고정된
    // 정책) — 저장 시점 검증을 통과한 사용자 SELECT 가 세미콜론을 달고 그대로 여기까지 온다.
    // 세미콜론을 그대로 서브쿼리 괄호 안에 넣으면 "FROM (SELECT ... FROM x;) AS _src" 가 되어 PostgreSQL
    // 문법 오류가 난다 — REPLACE/APPEND 는 SELECT 를 문장 맨 끝에 이어 붙이는 형태라 이 문제가 없었지만
    // MERGE 는 SELECT 를 괄호로 감싸므로 별도로 제거해야 한다. 세미콜론이 하나뿐이라는 것은 이미
    // SqlValidator(단일 statement 요구)가 보장한다.
    String normalizedSelectSql = stripTrailingSemicolon(selectSql);

    StringBuilder sql = new StringBuilder();
    // Fix round 2, must 1 — 서브쿼리를 자기 줄에 얹는다(앞뒤에 개행). SqlValidator 는 SQL 한 줄
    // 주석(--)을 그대로 허용하는데(JSqlParser 가 파싱 시 무시할 뿐 원문에서 지우지 않음), 사용자 SELECT
    // 가 "SELECT a FROM x -- note" 로 끝나면 전부 한 줄이었던 예전 방식에서는 그 "--"가 줄 끝까지(=
    // 뒤이어 오는 ") AS _src ON CONFLICT ..." 까지) 통째로 주석 처리해 문법 오류를 냈다. 서브쿼리
    // 내용과 닫는 괄호 사이에 개행을 넣으면 한 줄 주석은 그 줄(=서브쿼리 내용이 끝나는 줄)에서만
    // 끝나고, 닫는 괄호는 다음 줄이라 영향을 받지 않는다 — REPLACE/APPEND 는 SELECT 를 문장 맨 끝에
    // 붙이므로 애초에 이 문제가 없다.
    sql.append("INSERT INTO ").append(qualifiedTable).append(" AS t (").append(cols).append(") ")
        .append("SELECT ").append(cols).append(" FROM (\n").append(normalizedSelectSql).append("\n) AS _src ")
        .append("ON CONFLICT (").append(keys).append(") ");
    if (nonKeys.isEmpty()) {
      return sql.append("DO NOTHING").toString();
    }
    String set =
        nonKeys.stream()
            .map(c -> q(c) + " = EXCLUDED." + q(c))
            .collect(Collectors.joining(", "));
    sql.append("DO UPDATE SET ").append(set)
        .append(" WHERE (").append(quoteJoin(nonKeys, "t.")).append(") IS DISTINCT FROM (")
        .append(quoteJoin(nonKeys, "EXCLUDED.")).append(")");
    return sql.toString();
  }

  /**
   * 문장 끝의(= 뒤에 공백과 주석밖에 없는) 세미콜론 하나를 제거한다. 없으면 원문 그대로다.
   *
   * <p>Fix round 2, must 1 — 세미콜론이 맨 끝이 아니라 {@code "SELECT a FROM x; -- note"} 처럼 <b>후행
   * 한 줄 주석보다 앞</b>에 올 수도 있다. {@code build()}가 서브쿼리를 자기 줄에 얹어도(위 문서 참고)
   * 이 세미콜론 자체는 여전히 괄호로 감싼 서브쿼리 안에서 문법 오류다 — 주석 뒤에 숨어 있다고 해서
   * 무해해지지 않는다.
   *
   * <p><b>코드리뷰 MEDIUM — "마지막 줄"이 아니라 "마지막 코드 줄"을 봐야 한다.</b> 예전 구현은
   * 물리적 마지막 줄만 검사해서, {@code "SELECT a FROM x;\n-- note"} 처럼 주석이 <b>다음 줄</b>에
   * 있으면(SqlValidator 가 허용하는 모양이다) 마지막 줄이 통째로 주석이라 세미콜론을 못 찾고 그대로
   * 살려 보냈다 — 실행할 때마다 문법 오류였다. 그래서 줄 단위가 아니라 <b>문자열 전체를 한 번 앞에서
   * 뒤로 훑으며</b> 따옴표·한 줄 주석 상태를 추적해 "top-level 마지막 세미콜론" 위치를 기록하고, 그
   * 뒤가 공백/주석뿐일 때만 지운다.
   *
   * <p>줄마다 상태를 초기화하며 뒤에서 앞으로 훑는 방식은 쓰지 않는다 — {@code "SELECT 'x;\n-- y' AS
   * a"} 처럼 <b>문자열 리터럴이 여러 줄에 걸치면</b> 마지막 줄이 주석처럼 보여서 리터럴 안의 세미콜론을
   * 지우게 된다. 앞에서부터 훑어야 그 상태를 놓치지 않는다.
   *
   * <p>따옴표 추적은 홑따옴표 하나로 안팎을 토글하는 방식이다(이스케이프 {@code ''} 포함, {@code
   * SqlValidator.countUnquotedDots} 와 같은 발상) — 즉 문자열 리터럴 안의 {@code --}/{@code ;} 는
   * 주석·문장 끝으로 오인하지 않는다. 다만 달러 인용({@code $$...$$}) 안까지는 추적하지 않는다 —
   * 파이프라인 SELECT 스텝이 함수 본문을 담는 경우는 없다고 보고 받아들인 한계다. 여러 statement 가
   * 섞인 입력(세미콜론 뒤에 코드가 더 있는 경우)은 애초에 SqlValidator 가 저장 시점에 거부하므로,
   * 여기서는 "뒤에 코드가 있으면 아무것도 지우지 않는다"로 보수적으로 둔다.
   */
  private static String stripTrailingSemicolon(String sql) {
    int lastSemicolon = -1; // top-level(따옴표·주석 밖) 마지막 세미콜론 위치
    boolean codeAfterSemicolon = false; // 그 세미콜론 뒤에 코드(공백·주석이 아닌 문자)가 있는가
    boolean inQuotes = false;
    boolean inLineComment = false;

    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (inLineComment) {
        if (c == '\n') {
          inLineComment = false;
        }
        continue;
      }
      if (inQuotes) {
        if (c == '\'') {
          inQuotes = false;
        }
        continue;
      }
      if (c == '\'') {
        inQuotes = true;
        codeAfterSemicolon = true;
      } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
        inLineComment = true;
        i++;
      } else if (c == ';') {
        lastSemicolon = i;
        codeAfterSemicolon = false;
      } else if (!Character.isWhitespace(c)) {
        codeAfterSemicolon = true;
      }
    }

    if (lastSemicolon < 0 || codeAfterSemicolon) {
      return sql.stripTrailing();
    }
    // 세미콜론만 들어내고 앞뒤(주석 포함)는 그대로 둔다 — 주석은 자기 줄에서 끝나므로 해가 없다.
    return (sql.substring(0, lastSemicolon).stripTrailing() + sql.substring(lastSemicolon + 1))
        .stripTrailing();
  }

  private static String q(String col) {
    return "\"" + col.replace("\"", "\"\"") + "\"";
  }

  private static String quoteJoin(List<String> cols, String prefix) {
    return cols.stream().map(c -> prefix + q(c)).collect(Collectors.joining(", "));
  }
}
