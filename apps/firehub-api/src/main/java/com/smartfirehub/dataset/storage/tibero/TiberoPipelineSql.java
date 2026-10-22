package com.smartfirehub.dataset.storage.tibero;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Tibero 테넌트 파이프라인 SQL 스텝의 적재 문장 생성기(순수 함수, WD-77).
 *
 * <p>PG 전용인 {@code OutputClearStatement.deleteAll}·{@code INSERT INTO t (cols) <sql>} 래퍼·{@code
 * MergeSqlBuilder} 의 Tibero 판이다. db-executor 는 문장을 실행만 하므로 api 가 여기서 만든다. Tibero 데이터 테이블에는 ID
 * 기본값·트리거가 없어 {@code ID} 는 테이블 전용 시퀀스 {@code NEXTVAL}, {@code _UPDATED_AT} 은 매 쓰기 {@code
 * SYSTIMESTAMP} 로 직접 채운다({@code CREATED_AT} 은 컬럼 기본값).
 *
 * <p>인자의 {@code schema} 는 이미 대문자 Tibero 스키마({@code GENIA_T<id>}), {@code table}·컬럼은 메타데이터 이름(소문자)이며
 * 내부에서 대문자 물리 이름으로 바꿔 인용한다. 사용자 SQL 결과 컬럼은 대문자 물리 이름({@code src."C"})으로 참조하므로 호출자가 결과 컬럼명 기준으로 컬럼을
 * 넘긴다.
 */
public final class TiberoPipelineSql {

  private TiberoPipelineSql() {}

  /**
   * 대상 테이블 전체 삭제. db-executor 선행문 검증 정규식({@code ^DELETE FROM "S"."T"$})과 정확히 일치해야 하므로 세미콜론·공백을 붙이지
   * 않는다. TRUNCATE 는 DDL 이라 암묵 커밋되어 롤백이 안 되므로 쓰지 않는다.
   */
  public static String deleteAll(String schema, String table) {
    return TiberoDmlSql.deleteAll(schema, table);
  }

  /**
   * 사용자 SELECT 를 인라인 뷰로 감싸 대상 컬럼만 INSERT 한다(추가·덮어쓰기 적재). 사용자 SQL 은 끝 세미콜론만 걷고 앞뒤에 개행을 둔다 — 끝이
   * {@code --} 주석이어도 닫는 괄호가 다음 줄이라 주석에 먹히지 않는다(PG 래퍼와 같은 이유). 서브쿼리 안에서 NEXTVAL 을 못 쓰므로 바깥 SELECT 에서
   * 부른다.
   */
  public static String insertSelect(
      String schema, String table, List<String> columns, String userSql) {
    String insertCols = TiberoDmlSql.cols(columns);
    String selectCols =
        columns.stream().map(c -> "src." + TiberoDmlSql.col(c)).collect(Collectors.joining(", "));
    return "INSERT INTO "
        + TiberoDmlSql.tbl(schema, table)
        + " (\"ID\", "
        + insertCols
        + ", \"_UPDATED_AT\") SELECT "
        + TiberoDmlSql.seq(schema, table)
        + ".NEXTVAL, "
        + selectCols
        + ", SYSTIMESTAMP FROM (\n"
        + TiberoSqlLexer.stripTrailingSemicolon(userSql)
        + "\n) src";
  }

  /**
   * 사용자 SELECT 를 소스로 MERGE 한다. PG 와 같이 값이 바뀐 행만 갱신하고(바뀐 행만 {@code _UPDATED_AT} 이동), 모든 컬럼이 키면
   * INSERT 만 한다. 본문은 {@link TiberoDmlSql#mergeCore} 를 재사용한다.
   *
   * @param pkColumns 키 컬럼(모두 {@code columns} 에 있어야 한다)
   */
  public static String mergeSelect(
      String schema, String table, List<String> columns, List<String> pkColumns, String userSql) {
    for (String pk : pkColumns) {
      if (!columns.contains(pk)) {
        throw new IllegalArgumentException("MERGE 키 컬럼이 SELECT 결과에 없습니다: " + pk);
      }
    }
    String using = "(\n" + TiberoSqlLexer.stripTrailingSemicolon(userSql) + "\n)";
    return TiberoDmlSql.mergeCore(schema, table, using, columns, pkColumns, false, true);
  }
}
