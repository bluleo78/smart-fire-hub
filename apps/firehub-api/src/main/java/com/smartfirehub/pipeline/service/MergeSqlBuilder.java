package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.util.SqlLexicalMask;
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

    // SqlValidator 는 후행 세미콜론과 끝 주석을 허용한다(SqlValidatorTest.allows_trailing_semicolon 로 고정된
    // 정책) — 저장 시점 검증을 통과한 사용자 SELECT 가 세미콜론을 달고 그대로 여기까지 온다. 세미콜론을
    // 그대로 서브쿼리 괄호 안에 넣으면 "FROM (SELECT ... FROM x;) AS _src" 가 되어 PostgreSQL 문법 오류가
    // 난다. 그래서 주석·리터럴(블록 주석·달러 인용·E 문자열 포함)을 인지하는 SqlLexicalMask 로 끝의
    // 공백·주석·세미콜론을 걷어낸다(#746) — SqlColumnProbe 도 같은 함수를 써야 probe 는 통과했는데 MERGE 가
    // 깨지는(또는 그 반대) 틈이 없다. 세미콜론이 하나뿐이라는 것은 SqlValidator(단일 statement)가 보장한다.
    String normalizedSelectSql = SqlLexicalMask.stripTrailingCommentsAndSemicolons(selectSql);

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

  private static String q(String col) {
    return "\"" + col.replace("\"", "\"\"") + "\"";
  }

  private static String quoteJoin(List<String> cols, String prefix) {
    return cols.stream().map(c -> prefix + q(c)).collect(Collectors.joining(", "));
  }
}
