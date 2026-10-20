package com.smartfirehub.dataset.storage.tibero;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Tibero(Oracle 계열) 오류 판별 공용 헬퍼. DDL 저장소·행 저장소·커넥션 래퍼의 오류 번역이 같은 규칙으로 오류 코드를 보도록 한 곳에 둔다.
 *
 * <p>공개 클래스인 이유: {@code external} 패키지의 커넥션 래퍼(오류 번역기)도 {@link #hasCode} 를 쓴다.
 */
public final class TiberoErrors {

  private TiberoErrors() {}

  /**
   * 예외 체인(최대 10단계)에 오류 코드가 있는지 — {@link SQLException#getErrorCode()} 또는 메시지의 {@code ORA-xxxxx} 표기.
   * 커넥션 래퍼가 {@link SQLException} 을 Spring 예외로 감싸므로 원인을 따라가고, 드라이버가 코드를 비워 두고 메시지에만 싣는 경우(Tibero 코드
   * 미확정, WD-71)를 위해 메시지 표기도 받아들인다.
   */
  public static boolean hasCode(Throwable error, int code) {
    String tag = oraTag(code);
    Throwable t = error;
    for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
      if (t instanceof SQLException se && se.getErrorCode() == code) {
        return true;
      }
      String message = t.getMessage();
      if (message != null && message.contains(tag)) {
        return true;
      }
    }
    return false;
  }

  /** 오류 코드의 메시지 표기({@code 942 → "ORA-00942"}). */
  private static String oraTag(int code) {
    return String.format(Locale.ROOT, "ORA-%05d", code);
  }

  /**
   * 주어진 커넥션에서 DDL 한 문장을 실행하되, 지정한 "객체 없음" 오류는 무시한다(그 밖의 오류는 그대로 던진다). DDL 저장소와 행 저장소가 같은 규칙을 쓰도록 한
   * 곳에 둔다. SQL 문장은 로그에 남기지 않는다.
   *
   * @return 실제로 실행됐으면 true, 무시할 "없음" 오류로 건너뛰었으면 false
   */
  static boolean runIgnoring(Connection c, String sql, TiberoDialect.NotFound... ignorable)
      throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
      return true;
    } catch (SQLException e) {
      for (TiberoDialect.NotFound nf : ignorable) {
        if (nf.matches(e)) {
          return false;
        }
      }
      throw e;
    }
  }
}
