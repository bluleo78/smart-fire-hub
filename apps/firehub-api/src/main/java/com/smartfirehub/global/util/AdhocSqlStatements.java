package com.smartfirehub.global.util;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.exception.DataAccessException;

/**
 * 사용자가 직접 쓴 애드혹 SQL 을 <b>바인드 값 없는 정적 {@link Statement}</b> 로 실행하는 헬퍼(#753).
 *
 * <p><b>왜 {@code dsl.fetch(String)} 을 쓰지 않는가.</b> jOOQ 의 plain SQL 실행은 문자열을 {@code
 * PreparedStatement} 로 보낸다. 그러면 pgjdbc 가 리터럴 밖의 {@code ?} 를 JDBC 바인드 자리로 해석해 jsonb 키 존재 연산자 ({@code
 * ?}, {@code ?|}, {@code ?&}) 가 "No value specified for parameter 1" 로 실패했다. 정적 Statement 는 {@code
 * ?} 를 자리표시자로 보지 않으므로 원문이 그대로 서버에 간다 — {@code ??} 같은 이스케이프 변환이 없다.
 *
 * <p><b>SQL 가드와 의미 차이가 없다.</b> {@link Statement#setEscapeProcessing(boolean) escapeProcessing} 도 끈다
 * — 켜 두면 pgjdbc 가 {@code {fn ...}}·{@code {d '...'}} 같은 JDBC 이스케이프를 다시 쓴다. 두 가지를 끄면 서버가 받는 문자열은
 * 호출자가 넘긴 문자열과 <b>바이트 단위로 같다</b>. 따라서 호출자가 검증기(SqlValidator)로 본 문자열(+ 행 제한 덧붙임)이 곧 실행되는 문자열이다.
 *
 * <p><b>{@code $n} 은 여기서 해결되지 않는다.</b> pgjdbc 는 정적 Statement 도 확장 프로토콜로 보내므로 서버가 Parse 에서 {@code
 * $1} 을 추론한 뒤 Bind(값 0개)에서 08P01 로 거부하고, Hikari 가 그 커넥션을 폐기한다. 호출자는 실행 전에 {@link
 * SqlLexicalMask#findPositionalParameter} 로 걸러야 한다({@link #positionalParameterMessage}).
 *
 * <p>커넥션은 {@code dsl} 의 ConnectionProvider 에서 빌린다 — Spring 트랜잭션 안이면 그 트랜잭션의 커넥션(=테넌트 GUC·{@code SET
 * LOCAL search_path}·savepoint 가 이미 걸린 커넥션)이다.
 */
public final class AdhocSqlStatements {

  private AdhocSqlStatements() {}

  /** SELECT 류를 정적 Statement 로 실행해 jOOQ {@link Result} 로 돌려준다. */
  public static Result<Record> fetch(DSLContext dsl, String sql) {
    return dsl.connectionResult(
        conn -> {
          try (Statement st = conn.createStatement()) {
            st.setEscapeProcessing(false);
            try (ResultSet rs = st.executeQuery(sql)) {
              // 다차원 배열은 jOOQ 가 1차원으로 읽다 null 로 잃으므로 가로채 중첩 리스트로 살린다(#757).
              // PostGIS 타입 이름은 커넥션 캐시 상태와 무관하게 한 표기로 고정해 jOOQ 가 늘 PGobject 로 읽게 한다(#759)
              // 범위 밖 날짜·시각(10000년·BC·infinity·24:00 등)은 Java 날짜 객체로 바뀌며 다른 값이 되므로 PG 텍스트
              // 원문으로 둔다(#768). 다차원 배열 래퍼보다 안쪽이어야 다차원 날짜 배열도 먼저 보고 폴백한다.
              // 기하 타입(point 등)은 pgjdbc 가 double 로 다시 포맷하므로 읽는 시점에 서버 원문 텍스트로 둔다(#776).
              AdhocTemporalValues temporal =
                  new AdhocTemporalValues(
                      AdhocGeometricValues.wrap(AdhocSpatialTypeNames.wrap(rs)));
              AdhocMultiDimArrays multiDim = new AdhocMultiDimArrays(temporal.proxy());
              return temporal.apply(dsl, multiDim.apply(dsl, dsl.fetch(multiDim.proxy())));
            }
          } catch (SQLException e) {
            throw translate(sql, e);
          }
        });
  }

  /**
   * DML 을 정적 Statement 로 실행해 영향 행 수를 돌려준다.
   *
   * <p><b>왜 {@code executeUpdate} 가 아니라 {@code execute} 인가(#754).</b> pgjdbc 의 {@code
   * executeUpdate} 는 결과 집합이 돌아오면 "A result was returned when none was expected" 로 예외를 던진다 — {@code
   * INSERT/UPDATE/ DELETE … RETURNING} 이 전부 실패·롤백됐다. {@code execute} 후 {@code
   * getMoreResults}/{@code getUpdateCount} 로 모든 결과를 소비하며 센다:
   *
   * <ul>
   *   <li>결과 집합(RETURNING) → 행 수. RETURNING 은 영향받은 행마다 한 행을 돌려주므로 이것이 곧 영향 행 수다 (pgjdbc 는 {@code
   *       execute} 경로에서 결과 집합과 명령 태그의 갱신 수를 함께 주지 않는다).
   *   <li>갱신 수(일반 DML) → 그 값. 예전 {@code executeUpdate} 와 같은 값이다.
   * </ul>
   *
   * <p><b>반환 행 자체는 돌려주지 않는다(의도한 선택).</b> 응답 레코드에 {@code columns/rows} 자리는 있지만 웹 SQL 편집기는 SELECT 일
   * 때만 행 표를 그리고 그 밖에는 영향 행 수만 보여 준다. 여기서는 영향 행 수만 정확히 한다.
   *
   * <p><b>한계.</b> 본문이 SELECT 인 데이터 수정 CTE({@code WITH d AS (DELETE … RETURNING …) SELECT … FROM
   * d})는 PostgreSQL 이 CTE 안 DML 의 행 수를 보고하지 않는다(명령 태그가 {@code SELECT n}). 이 경우 돌려주는 값은 최종 SELECT 가
   * 돌려준 행 수다 — {@code SELECT value FROM d} 처럼 CTE 결과를 그대로 내면 영향 행 수와 같지만 {@code SELECT count(*)
   * FROM d} 면 1 이다. (현재는 SQL 가드가 이 형태를 파싱하지 못해 실행 전에 거부한다 — 가드가 허용하게 되면 이 한계가 드러난다.)
   *
   * <p><b>다중 문장은 여기서 막지 않는다.</b> {@code execute} 는 여러 결과를 소비할 수 있지만, 단일 문장 강제는 호출자의 사전
   * 검증(SqlValidationUtils·SqlValidator)이 실행 전에 한다 — 이 헬퍼는 검증된 문자열을 바이트 그대로 실행할 뿐이다.
   */
  public static int execute(DSLContext dsl, String sql) {
    return dsl.connectionResult(
        conn -> {
          try (Statement st = conn.createStatement()) {
            st.setEscapeProcessing(false);
            long affected = 0;
            boolean isResultSet = st.execute(sql);
            // JDBC 표준 종료 조건: 결과 집합이 아니고 갱신 수가 -1 이면 더 이상 결과가 없다.
            while (true) {
              if (isResultSet) {
                try (ResultSet rs = st.getResultSet()) {
                  while (rs.next()) {
                    affected++;
                  }
                }
              } else {
                int updateCount = st.getUpdateCount();
                if (updateCount == -1) {
                  break;
                }
                affected += updateCount;
              }
              isResultSet = st.getMoreResults();
            }
            return (int) Math.min(affected, Integer.MAX_VALUE);
          } catch (SQLException e) {
            throw translate(sql, e);
          }
        });
  }

  /**
   * SQL 에 리터럴·주석 밖의 위치 파라미터({@code $n})가 있으면 사용자에게 보여줄 거부 메시지를, 없으면 null 을 돌려준다. 애드혹 실행은 바인드 값을 받지
   * 않으므로 {@code $n} 은 채울 수 없는 자리다.
   */
  public static String positionalParameterMessage(String sql) {
    int pos = SqlLexicalMask.findPositionalParameter(sql);
    if (pos < 0) {
      return null;
    }
    int end = pos + 1;
    while (end < sql.length() && Character.isDigit(sql.charAt(end))) {
      end++;
    }
    return "위치 파라미터 "
        + sql.substring(pos, end)
        + " 는 사용할 수 없습니다 — 애드혹 SQL 은 바인드 값을 받지 않습니다. 값을 SQL 에 직접 쓰세요.";
  }

  /**
   * 오류 뒤 savepoint 로 되돌린다. 되돌리기마저 실패하면(예: 커넥션이 이미 끊김) 그 예외가 <b>원래 SQL 오류를 덮지 않도록</b> suppressed 로
   * 붙이고 원래 예외를 다시 던진다(#753 — 예전에는 "Connection is closed" 롤백 예외만 남아 원인 없는 500 이 됐다). 커넥션이 정말 죽었다면
   * 트랜잭션은 어차피 끝나므로 계속 진행할 수 없다.
   */
  public static void rollbackToSavepointOrRethrow(
      DSLContext dsl, String savepoint, Exception original) {
    try {
      dsl.execute("ROLLBACK TO SAVEPOINT " + savepoint);
    } catch (RuntimeException rollbackFailure) {
      original.addSuppressed(rollbackFailure);
      if (original instanceof RuntimeException re) {
        throw re;
      }
      throw new IllegalStateException(original.getMessage(), original);
    }
  }

  /** jOOQ plain SQL 과 같은 "SQL [...]; 원인" 모양으로 감싸 DB 원인 메시지를 보존한다. */
  private static DataAccessException translate(String sql, SQLException e) {
    return new DataAccessException("SQL [" + sql + "]; " + e.getMessage(), e);
  }
}
