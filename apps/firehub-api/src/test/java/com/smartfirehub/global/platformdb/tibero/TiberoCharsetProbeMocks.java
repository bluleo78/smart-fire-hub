package com.smartfirehub.global.platformdb.tibero;

import static com.smartfirehub.global.platformdb.tibero.TiberoCharsetCheck.PROBE_SQL;
import static com.smartfirehub.global.platformdb.tibero.TiberoCharsetCheck.SAMPLE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/**
 * Tibero 문자셋 판정 질의({@link TiberoCharsetCheck#PROBE_SQL})용 JdbcTemplate mock 모음(WD-1182).
 *
 * <p>판정 시험과 기동 빈 시험이 같은 mock 을 쓰도록 한 곳에 둔다 — 질의·바인딩 인자가 바뀌면 여기만 고치면 된다.
 */
final class TiberoCharsetProbeMocks {

  private TiberoCharsetProbeMocks() {}

  /**
   * 질의가 한 행(왕복 문자열·바이트 수)을 돌려주는 mock. {@code bytes} 가 null 이면 SQL NULL 바이트 수로 본다({@code
   * rs.wasNull()} 이 true).
   */
  static JdbcTemplate row(String back, Integer bytes) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn(back);
    when(rs.getInt(2)).thenReturn(bytes == null ? 0 : bytes);
    when(rs.wasNull()).thenReturn(bytes == null);
    return feeding(rs);
  }

  /** 질의 결과에 행이 없는 mock. */
  static JdbcTemplate noRow() throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);
    return feeding(rs);
  }

  /** jdbc.query 가 주어진 ResultSet 을 ResultSetExtractor 에 먹이도록 맞춘다. */
  @SuppressWarnings("unchecked")
  private static JdbcTemplate feeding(ResultSet rs) {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(eq(PROBE_SQL), any(ResultSetExtractor.class), eq(SAMPLE), eq(SAMPLE)))
        .thenAnswer(inv -> inv.<ResultSetExtractor<Object>>getArgument(1).extractData(rs));
    return jdbc;
  }
}
