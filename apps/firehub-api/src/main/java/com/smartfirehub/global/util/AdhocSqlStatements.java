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
 * <p><b>왜 {@code dsl.fetch(String)} 을 쓰지 않는가.</b> jOOQ 의 plain SQL 실행은 문자열을 {@code PreparedStatement}
 * 로 보낸다. 그러면 pgjdbc 가 리터럴 밖의 {@code ?} 를 JDBC 바인드 자리로 해석해 jsonb 키 존재 연산자
 * ({@code ?}, {@code ?|}, {@code ?&}) 가 "No value specified for parameter 1" 로 실패했다. 정적 Statement 는
 * {@code ?} 를 자리표시자로 보지 않으므로 원문이 그대로 서버에 간다 — {@code ??} 같은 이스케이프 변환이 없다.
 *
 * <p><b>SQL 가드와 의미 차이가 없다.</b> {@link Statement#setEscapeProcessing(boolean) escapeProcessing} 도 끈다 —
 * 켜 두면 pgjdbc 가 {@code {fn ...}}·{@code {d '...'}} 같은 JDBC 이스케이프를 다시 쓴다. 두 가지를 끄면 서버가
 * 받는 문자열은 호출자가 넘긴 문자열과 <b>바이트 단위로 같다</b>. 따라서 호출자가 검증기(SqlValidator)로 본
 * 문자열(+ 행 제한 덧붙임)이 곧 실행되는 문자열이다.
 *
 * <p><b>{@code $n} 은 여기서 해결되지 않는다.</b> pgjdbc 는 정적 Statement 도 확장 프로토콜로 보내므로 서버가
 * Parse 에서 {@code $1} 을 추론한 뒤 Bind(값 0개)에서 08P01 로 거부하고, Hikari 가 그 커넥션을 폐기한다. 호출자는
 * 실행 전에 {@link SqlLexicalMask#findPositionalParameter} 로 걸러야 한다({@link #positionalParameterMessage}).
 *
 * <p>커넥션은 {@code dsl} 의 ConnectionProvider 에서 빌린다 — Spring 트랜잭션 안이면 그 트랜잭션의 커넥션(=테넌트
 * GUC·{@code SET LOCAL search_path}·savepoint 가 이미 걸린 커넥션)이다.
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
              return dsl.fetch(rs);
            }
          } catch (SQLException e) {
            throw translate(sql, e);
          }
        });
  }

  /** DML 을 정적 Statement 로 실행해 영향 행 수를 돌려준다. */
  public static int execute(DSLContext dsl, String sql) {
    return dsl.connectionResult(
        conn -> {
          try (Statement st = conn.createStatement()) {
            st.setEscapeProcessing(false);
            return st.executeUpdate(sql);
          } catch (SQLException e) {
            throw translate(sql, e);
          }
        });
  }

  /**
   * SQL 에 리터럴·주석 밖의 위치 파라미터({@code $n})가 있으면 사용자에게 보여줄 거부 메시지를, 없으면 null 을 돌려준다.
   * 애드혹 실행은 바인드 값을 받지 않으므로 {@code $n} 은 채울 수 없는 자리다.
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
   * 오류 뒤 savepoint 로 되돌린다. 되돌리기마저 실패하면(예: 커넥션이 이미 끊김) 그 예외가 <b>원래 SQL 오류를 덮지
   * 않도록</b> suppressed 로 붙이고 원래 예외를 다시 던진다(#753 — 예전에는 "Connection is closed" 롤백 예외만
   * 남아 원인 없는 500 이 됐다). 커넥션이 정말 죽었다면 트랜잭션은 어차피 끝나므로 계속 진행할 수 없다.
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
