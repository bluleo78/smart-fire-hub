package com.smartfirehub.dataset.storage.tibero;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tibero JDBC 값 바인딩·읽기 변환.
 *
 * <p>바인딩은 Java 값 타입 기준이다(columnTypes 힌트에는 null·PG DDL 문자열이 섞여 신뢰할 수 없다). 읽기는 ResultSetMetaData
 * 기준이다: NUMBER(1,0)→Boolean, NUMBER(19,0)→Long, 그 외 NUMBER→BigDecimal, DATE→LocalDate,
 * TIMESTAMP→LocalDateTime, 문자→String.
 */
public final class TiberoValues {

  private TiberoValues() {}

  /** Java 값 타입에 맞춰 PreparedStatement 파라미터를 바인딩한다. null 은 {@code Types.NULL} 로 보낸다. */
  public static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
    bind(ps, index, value, Types.NULL);
  }

  /**
   * {@link #bind(PreparedStatement, int, Object)} 와 같되 null 일 때 SQL 타입 힌트를 쓴다. {@code SELECT ? "C"
   * FROM DUAL} 같은 소스 절의 null 은 타입 추론이 안 될 수 있어 대상 컬럼 타입을 알면 힌트를 줄 수 있다. 행 저장소는 아직 힌트를 넘기지 않는다(Task
   * 5 실서버 확인 항목).
   */
  public static void bind(PreparedStatement ps, int index, Object value, int sqlTypeHint)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, sqlTypeHint);
    } else if (value instanceof Boolean b) {
      // Tibero 에는 BOOLEAN 컬럼이 없어 NUMBER(1) 에 1/0 으로 저장
      ps.setInt(index, b ? 1 : 0);
    } else if (value instanceof LocalDate d) {
      ps.setDate(index, java.sql.Date.valueOf(d));
    } else if (value instanceof LocalDateTime t) {
      ps.setTimestamp(index, Timestamp.valueOf(t));
    } else {
      ps.setObject(index, value);
    }
  }

  /** 메타데이터 기준으로 한 컬럼 값을 읽는다. NULL 은 항상 null. */
  public static Object read(ResultSet rs, int column) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int type = md.getColumnType(column);
    String typeName = md.getColumnTypeName(column);

    if (type == Types.NUMERIC || type == Types.DECIMAL) {
      int precision = md.getPrecision(column);
      int scale = md.getScale(column);
      if (precision == 1 && scale == 0) {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v != 0;
      }
      if (precision == 19 && scale == 0) {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : (Object) v;
      }
      return rs.getBigDecimal(column);
    }
    // Oracle 계열 DATE 는 Types.DATE 또는 (시간 포함) Types.TIMESTAMP + 타입명 DATE 로 올 수 있다
    if (type == Types.DATE) {
      java.sql.Date d = rs.getDate(column);
      return d == null ? null : d.toLocalDate();
    }
    if ("DATE".equalsIgnoreCase(typeName)) {
      Timestamp ts = rs.getTimestamp(column);
      return ts == null ? null : ts.toLocalDateTime().toLocalDate();
    }
    if (type == Types.TIMESTAMP) {
      Timestamp ts = rs.getTimestamp(column);
      return ts == null ? null : ts.toLocalDateTime();
    }
    if (type == Types.CHAR
        || type == Types.VARCHAR
        || type == Types.NVARCHAR
        || type == Types.LONGVARCHAR
        || type == Types.CLOB) {
      return rs.getString(column);
    }
    return rs.getObject(column);
  }

  /** 현재 행을 {컬럼 라벨 소문자 → 값} 맵으로 읽는다(메타데이터 이름과 맞추기 위해 소문자). */
  public static Map<String, Object> readRow(ResultSet rs) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    Map<String, Object> row = new LinkedHashMap<>();
    for (int i = 1; i <= md.getColumnCount(); i++) {
      row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), read(rs, i));
    }
    return row;
  }
}
