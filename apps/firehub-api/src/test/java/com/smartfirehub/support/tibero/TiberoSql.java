package com.smartfirehub.support.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Tibero 실서버 시험의 짧은 JDBC 도우미 — 테넌트 격리(WD-492) 실서버 시험들이 같은 모양을 저마다 들고 있던 것을 한곳에 모았다.
 *
 * <p>문장은 시험이 만든 고정 문자열이다(외부 입력 없음).
 */
public final class TiberoSql {

  private TiberoSql() {}

  /**
   * 문장 하나를 실행한다. {@code Void} 를 돌려주는 이유: {@code withRuntime(t, c -> exec(c, sql))} 처럼 결과를 돌려주는 람다로도
   * 쓰게 하려는 것이다.
   */
  public static Void exec(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
    return null;
  }

  /** 정확히 한 행인 조회의 첫 열 값. 행 수가 다르면 단언 실패. */
  public static long queryLong(Connection c, String sql) throws SQLException {
    List<Long> values = longs(c, sql);
    assertThat(values).as(sql).hasSize(1);
    return values.get(0);
  }

  /** 첫 열의 값 목록(NULL 은 null 로). */
  public static List<Long> longs(Connection c, String sql) throws SQLException {
    List<Long> values = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        long v = rs.getLong(1);
        values.add(rs.wasNull() ? null : v);
      }
    }
    return values;
  }

  /** 첫 열의 문자열 목록. */
  public static List<String> strings(Connection c, String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) values.add(rs.getString(1));
    }
    return values;
  }

  /** 정확히 한 행인 조회의 첫 열 문자열(NULL 이면 null). 행 수가 다르면 단언 실패. */
  public static String queryString(Connection c, String sql) throws SQLException {
    List<String> values = strings(c, sql);
    assertThat(values).as(sql).hasSize(1);
    return values.get(0);
  }
}
